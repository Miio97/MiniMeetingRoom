package client.media;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.*;
import dev.onvoid.webrtc.media.audio.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Separate capture/playout factories: a receive anchor cannot commit the capture factory to CUSTOM
 * input.
 */
public final class NativeAudioSession implements AutoCloseable {
    private final AudioDeviceModuleBase audio;
    private final PeerConnectionFactory factory;
    private final AudioTrackSource source;
    private final AudioTrack track;
    private final Set<Peer> peers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final boolean physicalPlayback;
    private final AudioDevice playbackDevice;
    private boolean playbackStarted;
    private final boolean physicalCapture;

    public NativeAudioSession(
            boolean headless, boolean capture, AudioDevice microphone, AudioDevice speaker) {
        AudioDeviceModuleBase adm =
                headless ? new HeadlessAudioDeviceModule() : new AudioDeviceModule();
        PeerConnectionFactory created = null;
        AudioTrackSource input = null;
        AudioTrack local = null;
        AudioDevice output = null;
        try {
            // Factory initialization selects Windows' communications defaults.
            // Apply the user's devices afterwards, before any stream opens, so an
            // HDMI communications endpoint cannot silently replace their speakers.
            created = new PeerConnectionFactory(adm);
            if (!headless && capture) {
                AudioDevice selected =
                        microphone != null
                                ? microphone
                                : MediaDevices.getDefaultAudioCaptureDevice();
                if (selected == null) throw new IllegalStateException("MICROPHONE_OPEN_FAILED");
                adm.setRecordingDevice(selected);
            }
            if (!headless && !capture) {
                AudioDevice selected =
                        speaker != null ? speaker : MediaDevices.getDefaultAudioRenderDevice();
                if (selected == null) throw new IllegalStateException("AUDIO_OUTPUT_FAILED");
                output = selected;
                adm.setPlayoutDevice(selected);
            }
            if (capture && !headless) {
                AudioOptions options = microphoneOptions();
                input = created.createAudioSource(options);
            } else input = new CustomAudioSource();
            local =
                    created.createAudioTrack(
                            capture ? "microphone" : "receive-audio-anchor", input);
        } catch (RuntimeException | Error failure) {
            if (local != null) local.dispose();
            if (input != null) input.dispose();
            if (created != null) created.dispose();
            adm.dispose();
            throw failure;
        }
        audio = adm;
        factory = created;
        source = input;
        track = local;
        physicalPlayback = !headless && !capture;
        physicalCapture = !headless && capture;
        playbackDevice = output;
    }

    public Peer publisher() {
        return new Peer(track, true, (data, bits, rate, channels, frames) -> {});
    }

    static AudioOptions microphoneOptions() {
        AudioOptions options = new AudioOptions();
        // A send-only factory has no render stream. Windows' built-in AEC then
        // refuses StartRecording. Do not open an idle speaker to satisfy it.
        options.echoCancellation = false;
        options.autoGainControl = false;
        options.noiseSuppression = true;
        options.highpassFilter = true;
        return options;
    }

    public Peer subscriber(java.util.function.LongConsumer received) {
        return new Peer(
                track, false, (data, bits, rate, channels, frames) -> received.accept(frames));
    }

    public void push(byte[] pcm) {
        if (source instanceof CustomAudioSource custom && !closed.get())
            custom.pushAudio(pcm, 16, 48000, 1, 480);
    }

    public void pushSystemAudio(byte[] pcm) {
        if (pcm.length != 1920) throw new IllegalArgumentException("SYSTEM_AUDIO_FAILED");
        if (source instanceof CustomAudioSource custom && !closed.get())
            custom.pushAudio(pcm, 16, 48000, 2, 480);
    }

    public final class Peer implements PeerConnectionObserver, AutoCloseable {
        private final RTCPeerConnection connection;
        private final AudioTrackSink binding;
        private final AtomicBoolean closing = new AtomicBoolean();
        private final CompletableFuture<Void> gathered = new CompletableFuture<>();
        private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
        private final List<RTCRtpReceiver> receivers = new CopyOnWriteArrayList<>();
        private final Object trackLifecycle = new Object();
        private final Object statsLifecycle = new Object();
        private volatile AudioTrack remote;
        private volatile RTCPeerConnectionState state = RTCPeerConnectionState.NEW;
        private final boolean sending;
        private RTCRtpSender sender;
        private boolean inputPaused;
        private volatile boolean presentationPaused;

        public void pausePresentation(boolean paused) {
            if (presentationPaused != paused) rtpWatchdog.reset(System.nanoTime());
            presentationPaused = paused;
        }

        public boolean presentationPaused() {
            return presentationPaused;
        }

        private final AudioRtpWatchdog rtpWatchdog = new AudioRtpWatchdog(System.nanoTime());

        private Peer(AudioTrack track, boolean publisher, AudioTrackSink sink) {
            if (closed.get()) throw new IllegalStateException("VIDEO_OWNER_CLOSED");

            this.binding =
                    (data, bits, rate, channels, frames) -> {
                        if (!closing.get() && !presentationPaused && frames > 0)
                            sink.onData(data, bits, rate, channels, frames);
                    };
            sending = publisher;
            connection = factory.createPeerConnection(new RTCConfiguration(), this);
            if (connection == null) throw new IllegalStateException("VIDEO_PEER_FAILED");
            try {
                RTCRtpTransceiverInit init = new RTCRtpTransceiverInit();
                init.direction =
                        publisher
                                ? RTCRtpTransceiverDirection.SEND_ONLY
                                : RTCRtpTransceiverDirection.RECV_ONLY;
                if (publisher) {
                    RTCRtpEncodingParameters encoding = new RTCRtpEncodingParameters();
                    encoding.active = true;
                    encoding.maxBitrate = 64000;
                    init.sendEncodings = List.of(encoding);
                }
                RTCRtpTransceiver transceiver = connection.addTransceiver(track, init);
                try {
                    if (publisher) sender = transceiver.getSender();
                    transceiver.setCodecPreferences(
                            factory.getRtpSenderCapabilities(MediaType.AUDIO).getCodecs().stream()
                                    .filter(codec -> "opus".equalsIgnoreCase(codec.getName()))
                                    .toList());
                } finally {
                    transceiver.dispose();
                }

                peers.add(this);
            } catch (RuntimeException failure) {
                connection.close();
                if (sender != null) sender.dispose();
                throw failure;
            }
        }

        public boolean failed() {
            return state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.CLOSED;
        }

        public boolean connected() {
            return state == RTCPeerConnectionState.CONNECTED;
        }

        /** Stop the recording device as well as detaching its RTP sender. */
        public void pauseInput() {
            synchronized (statsLifecycle) {
                if (!sending || closing.get() || inputPaused) return;
                // StopRecording closes the capture endpoint. Merely muting a track would still
                // record.
                if (physicalCapture) audio.stopRecording();
                sender.replaceTrack(null);
                inputPaused = true;
            }
        }

        public void resumeInput() {
            synchronized (statsLifecycle) {
                if (!sending || closing.get() || !inputPaused)
                    throw new IllegalStateException("AUDIO_REUSE_UNAVAILABLE");
                sender.replaceTrack(track);
                if (physicalCapture) {
                    audio.initRecording();
                    audio.startRecording();
                }
                inputPaused = false;
                rtpWatchdog.reset(System.nanoTime());
            }
        }

        public boolean stalled() {
            if (presentationPaused || inputPaused) return false;
            statsAsync()
                    .thenAccept(
                            report -> {
                                if (report == null || closing.get()) return;
                                long packets = 0;
                                boolean found = false;
                                for (var stat : report.getStats().values()) {
                                    if (stat.getType()
                                            != (sending
                                                    ? RTCStatsType.OUTBOUND_RTP
                                                    : RTCStatsType.INBOUND_RTP)) continue;
                                    var count =
                                            stat.getAttributes()
                                                    .get(
                                                            sending
                                                                    ? "packetsSent"
                                                                    : "packetsReceived");
                                    if (count instanceof Number n) {
                                        packets += n.longValue();
                                        found = true;
                                    }
                                }
                                if (found) rtpWatchdog.observe(packets, System.nanoTime());
                            });
            return rtpWatchdog.stalled(System.nanoTime());
        }

        @Override
        public void onIceCandidate(RTCIceCandidate candidate) {}

        @Override
        public void onIceGatheringChange(RTCIceGatheringState value) {
            if (value == RTCIceGatheringState.COMPLETE) gathered.complete(null);
        }

        @Override
        public void onConnectionChange(RTCPeerConnectionState value) {
            state = value;
        }

        @Override
        public void onTrack(RTCRtpTransceiver transceiver) {
            try {
                synchronized (trackLifecycle) {
                    if (closing.get()) return;
                    RTCRtpReceiver receiver = transceiver.getReceiver();
                    receivers.add(receiver);
                    if (receiver.getTrack() instanceof AudioTrack audioTrack && remote == null) {
                        remote = audioTrack;
                        audioTrack.addSink(binding);
                        // Device lifecycle runs on the audio owner after SDP completes.
                    }
                }
            } finally {
                transceiver.dispose();
            }
        }

        @Override
        public void onAddTrack(RTCRtpReceiver receiver, MediaStream[] streams) {
            receiver.dispose();
        }

        @Override
        public void onRemoveTrack(RTCRtpReceiver receiver) {
            receiver.dispose();
        }

        public String offer() throws Exception {
            CompletableFuture<RTCSessionDescription> offer = new CompletableFuture<>();
            pending.add(offer);
            connection.createOffer(
                    new RTCOfferOptions(),
                    new CreateSessionDescriptionObserver() {
                        public void onSuccess(RTCSessionDescription value) {
                            offer.complete(value);
                        }

                        public void onFailure(String ignored) {
                            offer.completeExceptionally(
                                    new IllegalStateException("VIDEO_OFFER_FAILED"));
                        }
                    });
            try {
                set(offer.get(10, TimeUnit.SECONDS), false);
                gathered.get(10, TimeUnit.SECONDS);
                return connection.getLocalDescription().sdp;
            } finally {
                pending.remove(offer);
            }
        }

        public void answer(String sdp) throws Exception {
            set(new RTCSessionDescription(RTCSdpType.ANSWER, sdp), true);
            // Decoding callbacks can run even when Windows' render session is inactive.
            // Start the shared receiver ADM once, outside WebRTC's signaling callback.
            if (!sending && physicalPlayback && !playbackStarted) {
                try {
                    // WebRTC initializes its first audio channel with the Windows
                    // communications device. Reopen after SDP to apply the actual
                    // selected endpoint, rather than only changing its index.
                    audio.stopPlayout();
                    audio.setPlayoutDevice(playbackDevice);
                    audio.initPlayout();
                    audio.startPlayout();
                    playbackStarted = true;
                } catch (RuntimeException | Error failure) {
                    throw new IllegalStateException("AUDIO_OUTPUT_FAILED", failure);
                }
            }
        }

        private final SingleFlightStats statsFlight = new SingleFlightStats();

        public CompletableFuture<RTCStatsReport> statsAsync() {
            return statsFlight.request(
                    callback -> {
                        synchronized (statsLifecycle) {
                            if (closing.get()) {
                                callback.accept(null);
                                return;
                            }
                            connection.getStats(callback::accept);
                        }
                    });
        }

        public RTCStatsReport stats(long timeoutMs) {
            try {
                return statsAsync().get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception failure) {
                return null;
            }
        }

        private void set(RTCSessionDescription value, boolean remoteDescription) throws Exception {
            CompletableFuture<Void> result = new CompletableFuture<>();
            pending.add(result);
            SetSessionDescriptionObserver observer =
                    new SetSessionDescriptionObserver() {
                        public void onSuccess() {
                            result.complete(null);
                        }

                        public void onFailure(String ignored) {
                            result.completeExceptionally(
                                    new IllegalStateException("VIDEO_DESCRIPTION_FAILED"));
                        }
                    };
            try {
                if (remoteDescription) connection.setRemoteDescription(value, observer);
                else connection.setLocalDescription(value, observer);
                result.get(10, TimeUnit.SECONDS);
            } finally {
                pending.remove(result);
            }
        }

        @Override
        public void close() {
            if (!closing.compareAndSet(false, true)) return;
            statsFlight.close();
            pending.forEach(
                    future ->
                            future.completeExceptionally(
                                    new IllegalStateException("VIDEO_PEER_CLOSED")));
            gathered.completeExceptionally(new IllegalStateException("VIDEO_PEER_CLOSED"));
            synchronized (trackLifecycle) {
                AudioTrack received = remote;
                if (received != null) received.removeSink(binding);
            }

            synchronized (statsLifecycle) {
                connection.close();
                if (sender != null) {
                    sender.dispose();
                    sender = null;
                }
            }
            receivers.forEach(RTCRtpReceiver::dispose);
            receivers.clear();
            peers.remove(this);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        peers.forEach(Peer::close);
        // Closing the last peer already stops WebRTC's ADM. A second explicit
        // stop fails on Windows; factory disposal owns the remaining teardown.
        track.dispose();
        source.dispose();
        factory.dispose();
        audio.dispose();
    }
}
