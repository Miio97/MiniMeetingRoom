package client.media;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.audio.*;
import dev.onvoid.webrtc.media.video.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import support.FxTestRuntime;
import static org.junit.jupiter.api.Assertions.*;

/** SDK capability probe: real native RTP, synthetic sources, no hardware or database. */
@Timeout(30)
class NativeMediaReuseTest {
    @BeforeAll static void runtime() throws Exception { FxTestRuntime.start(); }

    @Test void cameraSourceCanBeDisposedAndReplacedWithoutNewOffer() throws Exception {
        try (var owner = new NativeVideoSession(); var preview = new VideoFrameMailbox(); var receiver = new Answerer(false)) {
            SyntheticInput first = new SyntheticInput();
            var sender = owner.publisher(first, preview);
            try {
                sender.answer(receiver.answer(sender.offer())); first.start();
                await(() -> sender.connected()); await(() -> receiver.received.get() > 3);
                sender.pauseInput(); first.close();
                Thread.sleep(200); long stopped = receiver.received.get(); Thread.sleep(200);
                assertEquals(stopped, receiver.received.get(), "Disposed source must not continue producing frames");
                try (var next = new SyntheticInput()) {
                    sender.resumeInput(next); next.start();
                    sender.quality(AdaptiveVideoPolicy.Profile.LOW);
                    await(() -> receiver.received.get() > stopped + 3);
                    assertTrue(sender.connected());
                }
            } finally { sender.close(); first.close(); }
        }
    }

    @Test void audioTrackCanDetachAndResumeWithoutNewOffer() throws Exception {
        try (var owner = new NativeAudioSession(true, true, null, null); var receiver = new Answerer(true)) {
            var sender = owner.publisher();
            var producer = Executors.newSingleThreadScheduledExecutor();
            try {
                sender.answer(receiver.answer(sender.offer()));
                AtomicLong sample = new AtomicLong();
                Runnable push = () -> { byte[] pcm = new byte[960]; for (int i = 0; i < 480; i++) {
                    short value = (short)(Math.sin(2*Math.PI*440*sample.getAndIncrement()/48000)*5000);
                    pcm[2*i] = (byte)value; pcm[2*i+1] = (byte)(value >> 8);
                } owner.push(pcm); };
                var input = producer.scheduleAtFixedRate(push, 0, 10, TimeUnit.MILLISECONDS);
                await(() -> sender.connected()); await(() -> receiver.meaningful.get() > 4800);
                input.cancel(false); sender.pauseInput();
                // Decoders may emit concealment/silence while paused; the sender is detached, not closed.
                Thread.sleep(200); long before = receiver.meaningful.get(), packetsBefore = packets(sender);
                sender.resumeInput();
                producer.scheduleAtFixedRate(push, 0, 10, TimeUnit.MILLISECONDS);
                await(() -> receiver.meaningful.get() > before + 4800 && packets(sender) > packetsBefore + 3);
                assertTrue(sender.connected());
            } finally { producer.shutdownNow(); sender.close(); }
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "Native RTP did not resume");
    }
    private static long packets(NativeAudioSession.Peer sender) {
        var report=sender.stats(500); if(report==null) return 0;
        return report.getStats().values().stream().filter(stat -> stat.getType()==RTCStatsType.OUTBOUND_RTP)
                .map(stat -> stat.getAttributes().get("packetsSent")).filter(Number.class::isInstance)
                .mapToLong(value -> ((Number)value).longValue()).sum();
    }

    static final class SyntheticInput implements VideoInput {
        final CustomVideoSource source = new CustomVideoSource();
        final ScheduledExecutorService producer = Executors.newSingleThreadScheduledExecutor();
        boolean closed;
        public VideoTrackSource source() { return source; }
        public int width() { return 320; } public int height() { return 240; } public int fps() { return 15; }
        public void start() { producer.scheduleAtFixedRate(this::frame, 0, 67, TimeUnit.MILLISECONDS); }
        synchronized void frame() {
            if (closed) return;
            var buffer = NativeI420Buffer.allocate(320,240);
            try { for (int i = 0; i < buffer.getDataY().capacity(); i++) buffer.getDataY().put(i, (byte)120);
                for (int i = 0; i < buffer.getDataU().capacity(); i++) buffer.getDataU().put(i, (byte)100);
                for (int i = 0; i < buffer.getDataV().capacity(); i++) buffer.getDataV().put(i, (byte)160);
                source.pushFrame(new VideoFrame(buffer,System.nanoTime()));
            } finally { buffer.release(); }
        }
        public synchronized void close() { if (!closed) { closed = true; producer.shutdownNow(); source.dispose(); } }
    }

    static final class Answerer implements AutoCloseable {
        final AtomicLong received = new AtomicLong();
        final AtomicLong meaningful = new AtomicLong();
        final HeadlessAudioDeviceModule audio = new HeadlessAudioDeviceModule();
        final PeerConnectionFactory factory = new PeerConnectionFactory(audio);
        final CompletableFuture<Void> gathered = new CompletableFuture<>();
        final java.util.List<RTCRtpReceiver> receivers = new java.util.ArrayList<>();
        final RTCPeerConnection peer;
        Answerer(boolean voice) {
            peer = factory.createPeerConnection(new RTCConfiguration(), new PeerConnectionObserver() {
                public void onIceCandidate(RTCIceCandidate candidate) { }
                public void onIceGatheringChange(RTCIceGatheringState state) { if (state == RTCIceGatheringState.COMPLETE) gathered.complete(null); }
                public void onTrack(RTCRtpTransceiver transceiver) {
                    try { var receiver = transceiver.getReceiver(); receivers.add(receiver);
                        if (receiver.getTrack() instanceof VideoTrack track) track.addSink(frame -> { received.incrementAndGet(); frame.release(); });
                        if (receiver.getTrack() instanceof AudioTrack track) track.addSink((data,bits,rate,channels,frames) -> {
                            received.addAndGet(frames);
                            for (byte value : data) if (value != 0) { meaningful.addAndGet(frames); break; }
                        });
                    } finally { transceiver.dispose(); }
                }
            });
        }
        String answer(String offer) throws Exception {
            set(new RTCSessionDescription(RTCSdpType.OFFER,offer),true);
            var answer = new CompletableFuture<RTCSessionDescription>();
            peer.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                public void onSuccess(RTCSessionDescription value) { answer.complete(value); }
                public void onFailure(String error) { answer.completeExceptionally(new IllegalStateException("ANSWER_FAILED")); }
            });
            set(answer.get(5,TimeUnit.SECONDS),false); gathered.get(5,TimeUnit.SECONDS); return peer.getLocalDescription().sdp;
        }
        void set(RTCSessionDescription value, boolean remote) throws Exception {
            var done = new CompletableFuture<Void>();
            var observer = new SetSessionDescriptionObserver() {
                public void onSuccess() { done.complete(null); }
                public void onFailure(String error) { done.completeExceptionally(new IllegalStateException("DESCRIPTION_FAILED")); }
            };
            if (remote) peer.setRemoteDescription(value,observer); else peer.setLocalDescription(value,observer);
            done.get(5,TimeUnit.SECONDS);
        }
        public void close() { peer.close(); receivers.forEach(RTCRtpReceiver::dispose); factory.dispose(); audio.dispose(); }
    }
}
