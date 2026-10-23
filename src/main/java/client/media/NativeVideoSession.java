package client.media;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.*;
import dev.onvoid.webrtc.media.audio.*;
import dev.onvoid.webrtc.media.video.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Video-only native owner; dummy ADM never opens microphone or speaker devices. */
public final class NativeVideoSession implements AutoCloseable {
    private final AudioDeviceModule audio = new AudioDeviceModule(AudioLayer.kDummyAudio);
    private final PeerConnectionFactory factory;
    private final CustomVideoSource anchorSource;
    private final VideoTrack anchor;
    private final Set<Peer> peers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    public NativeVideoSession() {
        PeerConnectionFactory created = null; CustomVideoSource source = null; VideoTrack track = null;
        try { created = new PeerConnectionFactory(audio); source = new CustomVideoSource(); track = created.createVideoTrack("receive-anchor", source); }
        catch (RuntimeException | Error failure) { if (track != null) track.dispose(); if (source != null) source.dispose(); if (created != null) created.dispose(); audio.dispose(); throw failure; }
        factory = created; anchorSource = source; anchor = track;
    }
    public Peer publisher(VideoInput input, VideoFrameMailbox preview) {
        return publisher(input,preview,600000,Math.min(30,input.fps()));
    }
    public Peer publisher(VideoInput input, VideoFrameMailbox preview, int bitrate, int fps) {
        VideoTrack track = factory.createVideoTrack("camera-video", input.source());
        try { Peer peer=new Peer(track, true, preview, bitrate, fps);peer.sourceWidth=input.width();peer.sourceHeight=input.height();peer.sourceFps=input.fps();return peer; } catch (RuntimeException failure) { track.dispose(); throw failure; }
    }
    public Peer subscriber(VideoFrameMailbox output) { return new Peer(anchor, false, output, 700000, 15); }
    /** Local capture track without a PeerConnection; pre-join never publishes camera. */
    public Preview preview(VideoInput input, VideoFrameMailbox output) { return new Preview(input, output); }
    public final class Preview implements AutoCloseable {
        private final VideoTrack track;
        private final VideoFrameMailbox output;
        private final VideoTrackSink binding;
        private final AtomicBoolean closing = new AtomicBoolean();
        private Preview(VideoInput input, VideoFrameMailbox output) {
            if (closed.get()) throw new IllegalStateException("VIDEO_OWNER_CLOSED");
            this.output = output; binding = output.bind();
            track = factory.createVideoTrack("camera-preview", input.source());
            try { track.addSink(binding); }
            catch (RuntimeException failure) { track.dispose(); throw failure; }
        }
        @Override public void close() {
            if (closing.compareAndSet(false, true)) { track.removeSink(binding); output.pause(); track.dispose(); }
        }
    }
    public final class Peer implements PeerConnectionObserver, AutoCloseable {
        private final RTCPeerConnection connection;
        private final boolean publisher;
        private final VideoTrack local;
        private final VideoFrameMailbox sink;
        private final VideoTrackSink binding;
        private final AtomicBoolean closing = new AtomicBoolean();
        private final CompletableFuture<Void> gathered = new CompletableFuture<>();
        private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
        private final List<RTCRtpReceiver> receivers = new CopyOnWriteArrayList<>();
        private final Object trackLifecycle = new Object();
        private final Object nativeLifecycle = new Object();
        private RTCRtpSender sender;
        private int sourceWidth,sourceHeight,sourceFps=30;
        private final SingleFlightStats statsFlight=new SingleFlightStats();
        private volatile VideoTrack remote;
        private volatile RTCPeerConnectionState state = RTCPeerConnectionState.NEW;
        private Peer(VideoTrack track, boolean publisher, VideoFrameMailbox sink, int bitrate, int fps) {
            if (closed.get()) throw new IllegalStateException("VIDEO_OWNER_CLOSED");
            this.publisher = publisher; this.local = track; this.sink = sink;
            this.binding = sink.bind();
            connection = factory.createPeerConnection(new RTCConfiguration(), this);
            if (connection == null) throw new IllegalStateException("VIDEO_PEER_FAILED");
            try {
                RTCRtpTransceiverInit init = new RTCRtpTransceiverInit();
                init.direction = publisher ? RTCRtpTransceiverDirection.SEND_ONLY : RTCRtpTransceiverDirection.RECV_ONLY;
                if (publisher) {
                    RTCRtpEncodingParameters encoding = new RTCRtpEncodingParameters(); encoding.active = true;
                    encoding.maxBitrate = bitrate; encoding.maxFramerate = (double) fps; init.sendEncodings = List.of(encoding);
                }
                RTCRtpTransceiver transceiver = connection.addTransceiver(track, init);
                try { if (publisher) sender = transceiver.getSender();
                    transceiver.setCodecPreferences(factory.getRtpSenderCapabilities(MediaType.VIDEO).getCodecs().stream()
                        .filter(codec -> "VP8".equalsIgnoreCase(codec.getName())).toList()); }
                finally { transceiver.dispose(); }
                if (publisher) track.addSink(binding);
                peers.add(this);
            } catch (RuntimeException failure) { connection.close(); if (sender != null) sender.dispose(); throw failure; }
        }
        public boolean failed() { return state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.CLOSED; }
        public boolean connected() { return state == RTCPeerConnectionState.CONNECTED; }
        @Override public void onIceCandidate(RTCIceCandidate candidate) { }
        @Override public void onIceGatheringChange(RTCIceGatheringState value) { if (value == RTCIceGatheringState.COMPLETE) gathered.complete(null); }
        @Override public void onConnectionChange(RTCPeerConnectionState value) { state = value; }
        @Override public void onTrack(RTCRtpTransceiver transceiver) {
            try {
                synchronized (trackLifecycle) {
                    if (closing.get()) return;
                    RTCRtpReceiver receiver = transceiver.getReceiver(); receivers.add(receiver);
                    if (receiver.getTrack() instanceof VideoTrack video && remote == null) { remote = video; video.addSink(binding); }
                }
            } finally { transceiver.dispose(); }
        }
        @Override public void onAddTrack(RTCRtpReceiver receiver, MediaStream[] streams) { receiver.dispose(); }
        @Override public void onRemoveTrack(RTCRtpReceiver receiver) { receiver.dispose(); }
        public String offer() throws Exception {
            CompletableFuture<RTCSessionDescription> offer = new CompletableFuture<>(); pending.add(offer);
            connection.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
                public void onSuccess(RTCSessionDescription value) { offer.complete(value); }
                public void onFailure(String ignored) { offer.completeExceptionally(new IllegalStateException("VIDEO_OFFER_FAILED")); }
            });
            try { set(offer.get(10, TimeUnit.SECONDS), false); gathered.get(10, TimeUnit.SECONDS); return connection.getLocalDescription().sdp; }
            finally { pending.remove(offer); }
        }
        public void answer(String sdp) throws Exception { set(new RTCSessionDescription(RTCSdpType.ANSWER, sdp), true); }
        public void quality(AdaptiveVideoPolicy.Profile profile) {
            quality(profile,NetworkSample.Limitation.UNKNOWN,NetworkStatsConfig.defaults());
        }
        public void quality(AdaptiveVideoPolicy.Profile profile,NetworkSample.Limitation reason,NetworkStatsConfig config) {
            synchronized (nativeLifecycle) {
                if (closing.get() || sender == null) return;
                RTCRtpSendParameters parameters = sender.getParameters();
                if (parameters.encodings == null || parameters.encodings.isEmpty()) throw new IllegalStateException("VIDEO_ENCODINGS_UNAVAILABLE");
                for (var encoding : parameters.encodings) {
                    encoding.active = profile != AdaptiveVideoPolicy.Profile.VOICE_PRIORITY;
                    encoding.maxBitrate = switch(profile) {case HD -> config.hdBitrate();case LOW -> config.lowBitrate();default -> config.savingBitrate();};
                    encoding.maxFramerate = (double)Math.min(sourceFps,reason==NetworkSample.Limitation.CPU_LIMITED?15:profile.fps);
                    encoding.scaleResolutionDownBy = Math.max(1,Math.max((double)sourceWidth/profile.width,(double)sourceHeight/profile.height));
                }
                sender.setParameters(parameters);
                if (profile != AdaptiveVideoPolicy.Profile.VOICE_PRIORITY) sender.generateKeyFrame();
            }
        }
        /** Preserve a screen publication's own bitrate/size settings during voice-only fallback. */
        public void pauseTransmission(boolean pause) {
            synchronized (nativeLifecycle) {
                if (closing.get() || sender == null) return;
                var parameters = sender.getParameters();
                for (var encoding : parameters.encodings) encoding.active = !pause;
                sender.setParameters(parameters);
                if (!pause) sender.generateKeyFrame();
            }
        }
        public CompletableFuture<RTCStatsReport> statsAsync() {
            return statsFlight.request(callback -> {
                synchronized (nativeLifecycle) {
                    if(closing.get()) {callback.accept(null);return;}
                    connection.getStats(callback::accept);
                }
            });
        }
        public RTCStatsReport stats(long timeoutMs) {
            try {return statsAsync().get(timeoutMs,TimeUnit.MILLISECONDS);} catch(Exception failure) {return null;}
        }
        private void set(RTCSessionDescription value, boolean remoteDescription) throws Exception {
            CompletableFuture<Void> result = new CompletableFuture<>(); pending.add(result);
            SetSessionDescriptionObserver observer = new SetSessionDescriptionObserver() {
                public void onSuccess() { result.complete(null); }
                public void onFailure(String ignored) { result.completeExceptionally(new IllegalStateException("VIDEO_DESCRIPTION_FAILED")); }
            };
            try { if (remoteDescription) connection.setRemoteDescription(value, observer); else connection.setLocalDescription(value, observer); result.get(10, TimeUnit.SECONDS); }
            catch (Error failure) {
                // The JNI SDP parser can throw a plain java.lang.Error before invoking
                // its observer. Normalize that operation failure without swallowing VM errors.
                if (failure.getClass() != Error.class) throw failure;
                throw new IllegalStateException("VIDEO_DESCRIPTION_FAILED", failure);
            }
            finally { pending.remove(result); }
        }
        @Override public void close() {
            if (!closing.compareAndSet(false, true)) return;
            statsFlight.close();
            pending.forEach(future -> future.completeExceptionally(new IllegalStateException("VIDEO_PEER_CLOSED")));
            gathered.completeExceptionally(new IllegalStateException("VIDEO_PEER_CLOSED"));
            synchronized (trackLifecycle) {
                if (publisher) local.removeSink(binding);
                VideoTrack received = remote; if (received != null) received.removeSink(binding);
            }
            sink.pause();
            synchronized (nativeLifecycle) { connection.close(); if (sender != null) { sender.dispose(); sender = null; } }
            receivers.forEach(RTCRtpReceiver::dispose); receivers.clear();
            if (publisher) local.dispose(); peers.remove(this);
        }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        peers.forEach(Peer::close); anchor.dispose(); anchorSource.dispose(); factory.dispose(); audio.dispose();
    }
}
