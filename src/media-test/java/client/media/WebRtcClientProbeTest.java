package client.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import common.diagnostics.Diagnostics;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.logging.Logging;
import dev.onvoid.webrtc.media.MediaStream;
import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.audio.*;
import dev.onvoid.webrtc.media.video.*;
import java.nio.ByteBuffer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.OS;

/**
 * Opt-in native CLIENT feasibility check, not an SFU test: two local peers exchange
 * generated PCM and I420 using ICE/DTLS-SRTP. No camera, microphone or speaker is
 * opened. The sources are created here; no external media file is required.
 * API/ownership reference: devopvoid/webrtc-java v0.19.0 TestMediaCall/TestBase
 * and media/audio/HeadlessADMIntegrationTest,
 * https://github.com/devopvoid/webrtc-java/tree/v0.19.0/webrtc/src/test/java/dev/onvoid/webrtc
 */
@EnabledOnOs(OS.WINDOWS)
class WebRtcClientProbeTest {
    private static final long CALLBACK_SECONDS = 5;

    @Test
    @Timeout(60)
    @EnabledIfEnvironmentVariable(named = "MMR_MEDIA_PROBE_SECRET", matches = ".{32,128}")
    void generatedMediaCrossesPionWithoutTranscoding() throws Exception {
        try (Diagnostics diagnostics = Diagnostics.start("client-media-probe"); ProbeApi api = new ProbeApi()) {
            Logging.logToDebug(Logging.Severity.NONE);
            String phase = "connect";
            try (Call call = new Call()) {
                call.connectToSfu(api);
                diagnostics.event("sfu.probe.connected", Map.of("active_peer_connections", 2,
                        "transport", "pion", "status", "dtls_connected"));
                phase = "generated_media";
                call.feedForThreeSeconds();
                call.assertReceived();
                assertMediaStats(call.publisher.stats(), RTCStatsType.OUTBOUND_RTP);
                assertMediaStats(call.subscriber.stats(), RTCStatsType.INBOUND_RTP);
                diagnostics.event("media.probe.received", Map.of("cycle", 1,
                        "video_frames_cumulative", call.videoFrames.get(),
                        "audio_chunks_cumulative", call.audioChunks.get(), "transport", "pion"));
                phase = "close";
                api.request("DELETE", "/session", null);
                call.close();
                JsonObject stats = api.request("GET", "/stats", null);
                assertEquals(0, stats.get("active_peer_connections").getAsInt(), "SFU transport cleanup failed");
                assertTrue(stats.get("closed").getAsBoolean(), "SFU session is still open");
                assertTrue(!stats.get("cleanup_timed_out").getAsBoolean(), "SFU workers missed the cleanup deadline");
                for (String kind : List.of("audio", "video")) {
                    JsonObject track = stats.getAsJsonObject("tracks").getAsJsonObject(kind);
                    assertTrue(track.get("ingress_packets").getAsLong() > 0, "SFU received no media");
                    assertTrue(track.get("ingress_payload_bytes").getAsLong() > 0, "SFU received no codec payload");
                    assertEquals(track.get("ingress_packets").getAsLong(), track.get("forwarded_packets").getAsLong(), "SFU packet conservation failed");
                    assertEquals(track.get("ingress_payload_bytes").getAsLong(), track.get("forwarded_payload_bytes").getAsLong(), "SFU payload byte conservation failed");
                    assertTrue(track.get("ingress_payload_sha256").getAsString().equals(
                            track.get("forwarded_payload_sha256").getAsString()), "SFU changed codec payload");
                    var measured = new java.util.LinkedHashMap<String, Object>();
                    measured.put("kind", kind.equals("audio") ? "AUDIO" : "VIDEO");
                    measured.put("cycle", 1);
                    measured.put("transport", "pion");
                    for (String counter : List.of("ingress_packets", "ingress_payload_bytes", "forwarded_packets",
                            "forwarded_payload_bytes", "queue_packets", "queue_bytes", "queue_drops",
                            "expired_drops", "write_errors", "read_errors", "pli_requests")) {
                        long value = track.get(counter).getAsLong();
                        String metric = counter.equals("ingress_payload_bytes") ? "ingress_codec_bytes"
                                : counter.equals("forwarded_payload_bytes") ? "forwarded_codec_bytes" : counter;
                        measured.put(metric, value);
                        if (counter.startsWith("queue_") || counter.equals("expired_drops") || counter.equals("write_errors")) {
                            assertEquals(0, value, "SFU dropped or queued media after cleanup");
                        }
                    }
                    diagnostics.event("sfu.probe.stats", measured);
                }
                diagnostics.event("sfu.probe.closed", Map.of("active_peer_connections", 0, "outcome", "pass"));
            } catch (Exception | AssertionError failure) {
                diagnostics.failure("sfu.probe.failed", failure, Map.of("phase", phase));
                throw new AssertionError("SFU native probe failed during " + phase + ": " + failure.getClass().getSimpleName());
            } finally {
                try { api.request("DELETE", "/session", null); }
                catch (Exception cleanupFailure) {
                    diagnostics.failure("sfu.probe.failed", cleanupFailure, Map.of("phase", "close"));
                    throw new AssertionError("SFU probe control cleanup failed");
                }
            }
        }
    }

    /** Loopback-only, authenticated test control. Login tokens never enter this API. */
    private static final class ProbeApi implements AutoCloseable {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .proxy(new java.net.ProxySelector() {
                    @Override public List<java.net.Proxy> select(URI uri) { return List.of(java.net.Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, java.net.SocketAddress address, java.io.IOException failure) { }
                }).build();
        private final String secret = System.getenv("MMR_MEDIA_PROBE_SECRET");
        private final int port = Integer.parseInt(System.getenv().getOrDefault("MMR_MEDIA_PROBE_PORT", "8765"));

        JsonObject request(String method, String path, JsonObject body) throws Exception {
            if (port < 1 || port > 65535) throw new IllegalArgumentException("probe_port_invalid");
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(12)).header("X-MMR-Probe-Key", secret)
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(new Gson().toJson(body))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                byte[] bytes = stream.readNBytes(128 * 1024 + 1);
                if (response.statusCode() != 200 || bytes.length > 128 * 1024) throw new IllegalStateException("probe_control_failed");
                return JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            }
        }

        void negotiate(Peer peer, String path) throws Exception {
            peer.localDescription(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (peer.connection.getIceGatheringState() != RTCIceGatheringState.COMPLETE) {
                assertTrue(System.nanoTime() < deadline, "probe_ice_gather_timeout");
                Thread.sleep(10);
            }
            JsonObject body = new JsonObject();
            body.addProperty("sdp", peer.connection.getLocalDescription().sdp);
            JsonObject answer = request("POST", path, body);
            peer.setDescription(new RTCSessionDescription(RTCSdpType.ANSWER, answer.get("sdp").getAsString()), true);
        }

        @Override public void close() { client.close(); }
    }

    @Test
    @Timeout(60)
    void generatedOpusVp8MediaAndStatsSurviveTwoCleanLifecycles() throws Exception {
        try (Diagnostics diagnostics = Diagnostics.start("client-media-probe")) {
            long nativeLoadStarted = System.nanoTime();
            Logging.logToDebug(Logging.Severity.NONE);
            diagnostics.event("media.probe.native", Map.of("native_load_ms",
                    (System.nanoTime() - nativeLoadStarted) / 1_000_000.0, "status", "loaded"));
            for (int cycle = 1; cycle <= 2; cycle++) {
                long started = System.nanoTime();
                String phase = "create";
                diagnostics.event("media.probe.start", Map.of("cycle", cycle,
                        "kind", "generated", "transport", "direct_peer"));
                try (Call call = new Call()) {
                    phase = "connect";
                    call.connect();
                    double connectMs = (System.nanoTime() - started) / 1_000_000.0;
                    phase = "generated_media";
                    long inputStarted = System.nanoTime();
                    call.feedForThreeSeconds();
                    double inputDurationMs = (System.nanoTime() - inputStarted) / 1_000_000.0;
                    call.assertHealthy();
                    diagnostics.event("media.probe.received", Map.of("cycle", cycle,
                            "video_frames_cumulative", call.videoFrames.get(),
                            "audio_chunks_cumulative", call.audioChunks.get(),
                            "video_encoded_frames_cumulative", call.encodedVideoFrames.get(),
                            "audio_encoded_frames_cumulative", call.encodedAudioFrames.get(),
                            "input_duration_ms", inputDurationMs,
                            "status", "measured"));
                    phase = "receive_threshold";
                    call.assertReceived();
                    phase = "stats";
                    RTCStatsReport senderStats = call.publisher.stats();
                    RTCStatsReport receiverStats = call.subscriber.stats();
                    assertMediaStats(senderStats, RTCStatsType.OUTBOUND_RTP);
                    assertMediaStats(receiverStats, RTCStatsType.INBOUND_RTP);
                    diagnostics.event("media.probe.connected", Map.of("cycle", cycle,
                            "peer_setup_ms", connectMs, "status", "dtls_connected"));
                    logPacketCounters(diagnostics, cycle, senderStats, RTCStatsType.OUTBOUND_RTP);
                    logPacketCounters(diagnostics, cycle, receiverStats, RTCStatsType.INBOUND_RTP);
                    phase = "close";
                    long shutdownStarted = System.nanoTime();
                    call.close();
                    double shutdownMs = (System.nanoTime() - shutdownStarted) / 1_000_000.0;
                    call.close(); // The application owner is idempotent even if native dispose is not.
                    int videoAfterClose = call.videoFrames.get();
                    int audioAfterClose = call.audioChunks.get();
                    Thread.sleep(150);
                    assertEquals(videoAfterClose, call.videoFrames.get(), "Video continued after close");
                    assertEquals(audioAfterClose, call.audioChunks.get(), "Audio continued after close");
                    assertEquals(0, call.lateMediaCallbacks.get(), "Media callback after native close");
                    assertTrue(call.publisher.pendingIce.isEmpty());
                    assertTrue(call.subscriber.pendingIce.isEmpty());
                    diagnostics.event("media.probe.closed", Map.of("cycle", cycle,
                            "cycle_ms", (System.nanoTime() - started) / 1_000_000.0,
                            "shutdown_ms", shutdownMs,
                            "late_media_callbacks", call.lateMediaCallbacks.get(), "outcome", "pass"));
                } catch (Exception | AssertionError failure) {
                    diagnostics.failure("media.probe.failed", failure, Map.of("cycle", cycle, "phase", phase));
                    // Native exception messages may contain SDP or network addresses.
                    throw new AssertionError("Native client probe failed during " + phase + ": "
                            + failure.getClass().getSimpleName());
                }
            }
        }
    }

    private static void assertMediaStats(RTCStatsReport report, RTCStatsType direction) {
        assertNotNull(report);
        for (String codecName : List.of("audio/opus", "video/VP8")) {
            boolean found = false;
            for (RTCStats stream : report.getStats().values()) {
                if (stream.getType() != direction) continue;
                Object codecId = stream.getAttributes().get("codecId");
                RTCStats codec = report.getStats().get(codecId);
                if (codec != null && codecName.equalsIgnoreCase(
                        String.valueOf(codec.getAttributes().get("mimeType")))) {
                    String packetKey = direction == RTCStatsType.INBOUND_RTP
                            ? "packetsReceived" : "packetsSent";
                    assertTrue(number(stream.getAttributes().get(packetKey)) > 0,
                            "Negotiated media stream had no RTP packets");
                    found = true;
                }
            }
            assertTrue(found, "Required codec missing from negotiated RTP statistics");
        }
        assertTrue(report.getStats().values().stream().anyMatch(stat ->
                stat.getType() == RTCStatsType.TRANSPORT
                        && "connected".equals(stat.getAttributes().get("dtlsState"))),
                "Statistics must confirm connected DTLS transport");
    }

    private static void logPacketCounters(Diagnostics diagnostics, int cycle,
            RTCStatsReport report, RTCStatsType direction) {
        String packetKey = direction == RTCStatsType.INBOUND_RTP ? "packetsReceived" : "packetsSent";
        long packets = report.getStats().values().stream().filter(stat -> stat.getType() == direction)
                .mapToLong(stat -> number(stat.getAttributes().get(packetKey))).sum();
        diagnostics.event("media.probe.stats", Map.of("cycle", cycle,
                "phase", direction == RTCStatsType.INBOUND_RTP ? "receive" : "send",
                "rtp_packets_cumulative", packets));
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(CALLBACK_SECONDS, TimeUnit.SECONDS);
    }

    private static final class Peer implements PeerConnectionObserver, AutoCloseable {
        private final ArrayBlockingQueue<RTCIceCandidate> pendingIce = new ArrayBlockingQueue<>(128);
        private final AtomicReference<String> callbackFailure;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean connected = new AtomicBoolean();
        private final RTCPeerConnection connection;
        private volatile Peer remote;
        private boolean remoteDescriptionReady;

        Peer(PeerConnectionFactory factory, AtomicReference<String> callbackFailure) {
            this.callbackFailure = callbackFailure;
            connection = factory.createPeerConnection(new RTCConfiguration(), this);
            if (connection == null) throw new IllegalStateException("peer_creation_failed");
        }

        @Override public void onIceCandidate(RTCIceCandidate candidate) {
            Peer destination = remote;
            if (!closed.get() && destination != null && !destination.closed.get()
                    && !destination.pendingIce.offer(candidate)) {
                callbackFailure.compareAndSet(null, "ice_queue_full");
            }
        }

        @Override public void onConnectionChange(RTCPeerConnectionState state) {
            if (closed.get()) return;
            connected.set(state == RTCPeerConnectionState.CONNECTED);
            if (state == RTCPeerConnectionState.FAILED) callbackFailure.compareAndSet(null, "peer_failed");
        }

        // The pinned JNI transfers one native reference into each callback wrapper.
        @Override public void onTrack(RTCRtpTransceiver transceiver) { transceiver.dispose(); }
        @Override public void onAddTrack(RTCRtpReceiver receiver, MediaStream[] streams) { receiver.dispose(); }
        @Override public void onRemoveTrack(RTCRtpReceiver receiver) { receiver.dispose(); }

        RTCSessionDescription localDescription(boolean offer) throws Exception {
            CompletableFuture<RTCSessionDescription> result = new CompletableFuture<>();
            CreateSessionDescriptionObserver observer = new CreateSessionDescriptionObserver() {
                @Override public void onSuccess(RTCSessionDescription description) {
                    if (!closed.get()) result.complete(description);
                }
                @Override public void onFailure(String ignored) {
                    result.completeExceptionally(new IllegalStateException("sdp_create_failed"));
                }
            };
            if (offer) connection.createOffer(new RTCOfferOptions(), observer);
            else connection.createAnswer(new RTCAnswerOptions(), observer);
            RTCSessionDescription description = await(result);
            setDescription(description, false);
            return description;
        }

        void setDescription(RTCSessionDescription description, boolean remoteDescription) throws Exception {
            CompletableFuture<Void> result = new CompletableFuture<>();
            SetSessionDescriptionObserver observer = new SetSessionDescriptionObserver() {
                @Override public void onSuccess() { if (!closed.get()) result.complete(null); }
                @Override public void onFailure(String ignored) {
                    result.completeExceptionally(new IllegalStateException("sdp_set_failed"));
                }
            };
            if (remoteDescription) connection.setRemoteDescription(description, observer);
            else connection.setLocalDescription(description, observer);
            await(result);
            if (remoteDescription) remoteDescriptionReady = true;
            drainIce();
        }

        void drainIce() {
            if (!remoteDescriptionReady || closed.get()) return;
            RTCIceCandidate candidate;
            while ((candidate = pendingIce.poll()) != null) connection.addIceCandidate(candidate);
        }

        RTCStatsReport stats() throws Exception {
            CompletableFuture<RTCStatsReport> result = new CompletableFuture<>();
            connection.getStats(report -> { if (!closed.get()) result.complete(report); });
            return await(result);
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            remote = null;
            pendingIce.clear();
            connection.close();
            pendingIce.clear();
        }
    }

    private static final class Call implements AutoCloseable {
        private final AtomicReference<String> callbackFailure = new AtomicReference<>();
        private final AtomicBoolean closing = new AtomicBoolean();
        private final AtomicBoolean closeComplete = new AtomicBoolean();
        private final AtomicInteger videoFrames = new AtomicInteger();
        private final AtomicInteger audioChunks = new AtomicInteger();
        private final AtomicInteger encodedVideoFrames = new AtomicInteger();
        private final AtomicInteger encodedAudioFrames = new AtomicInteger();
        private final AtomicInteger lateMediaCallbacks = new AtomicInteger();
        private HeadlessAudioDeviceModule audioModule;
        private PeerConnectionFactory factory;
        private CustomVideoSource videoSource;
        private CustomAudioSource audioSource;
        private VideoTrack localVideo;
        private AudioTrack localAudio;
        private Peer publisher;
        private Peer subscriber;
        private RTCRtpSender videoSender;
        private RTCRtpSender audioSender;
        private RTCRtpReceiver[] receivers = new RTCRtpReceiver[0];
        private VideoTrack remoteVideo;
        private AudioTrack remoteAudio;
        private final VideoTrackSink videoSink = frame -> {
            try {
                if (closeComplete.get()) lateMediaCallbacks.incrementAndGet();
                else if (!closing.get()) videoFrames.incrementAndGet();
            } finally { frame.release(); }
        };
        private final AudioTrackSink audioSink = (data, bits, rate, channels, frames) -> {
            if (closeComplete.get()) lateMediaCallbacks.incrementAndGet();
            else if (!closing.get() && data.length > 0 && frames > 0) audioChunks.incrementAndGet();
        };

        Call() {
            try {
                // Headless ADM pulls the receive decoder at 10 ms without an OS speaker.
                // A plain kDummyAudio ADM has no pull thread and cannot verify decoded PCM.
                audioModule = new HeadlessAudioDeviceModule();
                factory = new PeerConnectionFactory(audioModule);
                audioModule.initPlayout();
                audioModule.startPlayout();
                videoSource = new CustomVideoSource();
                audioSource = new CustomAudioSource();
                localVideo = factory.createVideoTrack("probe-video", videoSource);
                localAudio = factory.createAudioTrack("probe-audio", audioSource);
                publisher = new Peer(factory, callbackFailure);
                subscriber = new Peer(factory, callbackFailure);
                publisher.remote = subscriber;
                subscriber.remote = publisher;
                videoSender = publisher.connection.addTrack(localVideo, List.of("probe-stream"));
                audioSender = publisher.connection.addTrack(localAudio, List.of("probe-stream"));
                List<RTCRtpCodecCapability> codecs = new ArrayList<>(
                        factory.getRtpSenderCapabilities(MediaType.VIDEO).getCodecs());
                codecs.sort(Comparator.comparing(codec -> !"VP8".equalsIgnoreCase(codec.getName())));
                for (RTCRtpTransceiver transceiver : publisher.connection.getTransceivers()) {
                    RTCRtpSender sender = transceiver.getSender();
                    try { if (sender.equals(videoSender)) transceiver.setCodecPreferences(codecs); }
                    finally { sender.dispose(); transceiver.dispose(); }
                }
                videoSender.setTransform(frame -> {
                    if (closeComplete.get()) lateMediaCallbacks.incrementAndGet();
                    else if (!closing.get()) encodedVideoFrames.incrementAndGet();
                });
                audioSender.setTransform(frame -> {
                    if (closeComplete.get()) lateMediaCallbacks.incrementAndGet();
                    else if (!closing.get()) encodedAudioFrames.incrementAndGet();
                });
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        void connect() throws Exception {
            subscriber.setDescription(publisher.localDescription(true), true);
            publisher.setDescription(subscriber.localDescription(false), true);
            attachReceivers();
            waitConnected();
        }

        void connectToSfu(ProbeApi api) throws Exception {
            publisher.remote = null;
            subscriber.remote = null;
            RTCRtpTransceiverInit receive = new RTCRtpTransceiverInit();
            receive.direction = RTCRtpTransceiverDirection.RECV_ONLY;
            subscriber.connection.addTransceiver(localVideo, receive).dispose();
            subscriber.connection.addTransceiver(localAudio, receive).dispose();
            api.negotiate(publisher, "/publish");
            api.negotiate(subscriber, "/subscribe");
            attachReceivers();
            waitConnected();
        }

        private void attachReceivers() {
            receivers = subscriber.connection.getReceivers();
            for (RTCRtpReceiver receiver : receivers) {
                var track = receiver.getTrack();
                if (track instanceof VideoTrack video) { remoteVideo = video; video.addSink(videoSink); }
                if (track instanceof AudioTrack audio) { remoteAudio = audio; audio.addSink(audioSink); }
            }
            assertNotNull(remoteVideo, "Remote video track missing");
            assertNotNull(remoteAudio, "Remote audio track missing");
        }

        private void waitConnected() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!publisher.connected.get() || !subscriber.connected.get()) {
                if (System.nanoTime() >= deadline) {
                    Diagnostics.current().event("media.probe.connection", Map.of("phase", "deadline",
                            "status", publisher.connection.getConnectionState().name(),
                            "reason", subscriber.connection.getConnectionState().name()));
                    throw new AssertionError("ICE/DTLS connection deadline exceeded");
                }
                assertHealthy();
                publisher.drainIce();
                subscriber.drainIce();
                Thread.sleep(5);
            }
        }

        void feedForThreeSeconds() throws Exception {
            long started = System.nanoTime();
            long nextAudio = started;
            long nextVideo = started;
            long sampleIndex = 0;
            int shade = 0;
            byte[] pcm = new byte[960];
            while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3)) {
                long now = System.nanoTime();
                publisher.drainIce();
                subscriber.drainIce();
                assertHealthy();
                if (now >= nextAudio) {
                    for (int i = 0; i < 480; i++) {
                        short value = (short) (Math.sin(2 * Math.PI * 440 * sampleIndex++ / 48000) * 8000);
                        pcm[2 * i] = (byte) value;
                        pcm[2 * i + 1] = (byte) (value >> 8);
                    }
                    audioSource.pushAudio(pcm, 16, 48000, 1, 480);
                    nextAudio = now + TimeUnit.MILLISECONDS.toNanos(10);
                }
                if (now >= nextVideo) {
                    NativeI420Buffer buffer = NativeI420Buffer.allocate(320, 240);
                    fill(buffer.getDataY(), shade++);
                    fill(buffer.getDataU(), 128);
                    fill(buffer.getDataV(), 128);
                    VideoFrame frame = new VideoFrame(buffer, 0);
                    try { videoSource.pushFrame(frame); } finally { frame.release(); }
                    nextVideo = now + TimeUnit.MILLISECONDS.toNanos(33);
                }
                Thread.sleep(2);
            }
        }

        void assertReceived() {
            assertTrue(videoFrames.get() >= 10, "Insufficient decoded synthetic video frames");
            assertTrue(audioChunks.get() >= 50, "Insufficient decoded synthetic audio chunks");
            assertTrue(encodedVideoFrames.get() >= 10, "Encoded video callback missing");
            assertTrue(encodedAudioFrames.get() >= 50, "Encoded audio callback missing");
        }

        private static void fill(ByteBuffer plane, int value) {
            for (int i = 0; i < plane.capacity(); i++) plane.put(i, (byte) value);
        }

        void assertHealthy() {
            assertEquals(null, callbackFailure.get(), "Native callback or bounded queue failed");
        }

        @Override public void close() {
            if (!closing.compareAndSet(false, true)) return;
            if (audioModule != null) audioModule.stopPlayout();
            if (remoteVideo != null) remoteVideo.removeSink(videoSink);
            if (remoteAudio != null) remoteAudio.removeSink(audioSink);
            if (videoSender != null) { videoSender.setTransform(null); videoSender.dispose(); }
            if (audioSender != null) { audioSender.setTransform(null); audioSender.dispose(); }
            for (RTCRtpReceiver receiver : receivers) receiver.dispose();
            if (publisher != null) publisher.close();
            if (subscriber != null) subscriber.close();
            // Remote tracks returned by getTrack() are borrowed from their receiver.
            // Disposing them would release a reference this wrapper does not own.
            if (localVideo != null) localVideo.dispose();
            if (localAudio != null) localAudio.dispose();
            if (videoSource != null) videoSource.dispose();
            if (audioSource != null) audioSource.dispose();
            if (factory != null) factory.dispose();
            if (audioModule != null) audioModule.dispose();
            closeComplete.set(true);
        }
    }
}
