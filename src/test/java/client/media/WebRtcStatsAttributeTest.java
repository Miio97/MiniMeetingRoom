package client.media;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebRtcStatsAttributeTest {
    @Test
    void testVerifyRealSdkStatsAttributes() throws Exception {
        AudioDeviceModule audio = new AudioDeviceModule(AudioLayer.kDummyAudio);
        PeerConnectionFactory factory = new PeerConnectionFactory(audio);
        CustomVideoSource source = new CustomVideoSource();
        VideoTrack track = factory.createVideoTrack("test-video", source);

        AtomicReference<RTCPeerConnection> peer1Ref = new AtomicReference<>();
        AtomicReference<RTCPeerConnection> peer2Ref = new AtomicReference<>();

        PeerConnectionObserver observer1 = new PeerConnectionObserver() {
            @Override public void onIceCandidate(RTCIceCandidate candidate) {
                if (peer2Ref.get() != null) peer2Ref.get().addIceCandidate(candidate);
            }
            @Override public void onConnectionChange(RTCPeerConnectionState state) { }
        };
        PeerConnectionObserver observer2 = new PeerConnectionObserver() {
            @Override public void onIceCandidate(RTCIceCandidate candidate) {
                if (peer1Ref.get() != null) peer1Ref.get().addIceCandidate(candidate);
            }
            @Override public void onConnectionChange(RTCPeerConnectionState state) { }
        };

        RTCPeerConnection peer1 = factory.createPeerConnection(new RTCConfiguration(), observer1);
        RTCPeerConnection peer2 = factory.createPeerConnection(new RTCConfiguration(), observer2);
        peer1Ref.set(peer1);
        peer2Ref.set(peer2);

        try {
            RTCRtpTransceiverInit init = new RTCRtpTransceiverInit();
            init.direction = RTCRtpTransceiverDirection.SEND_RECV;
            peer1.addTransceiver(track, init).dispose();
            peer2.addTransceiver(track, init).dispose();

            // Exchange offer / answer
            CompletableFuture<RTCSessionDescription> offerFuture = new CompletableFuture<>();
            peer1.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
                @Override public void onSuccess(RTCSessionDescription sdp) { offerFuture.complete(sdp); }
                @Override public void onFailure(String error) { offerFuture.completeExceptionally(new RuntimeException(error)); }
            });
            RTCSessionDescription offer = offerFuture.get(5, TimeUnit.SECONDS);

            CompletableFuture<Void> setLocalOffer = new CompletableFuture<>();
            peer1.setLocalDescription(offer, new SetSessionDescriptionObserver() {
                @Override public void onSuccess() { setLocalOffer.complete(null); }
                @Override public void onFailure(String error) { setLocalOffer.completeExceptionally(new RuntimeException(error)); }
            });
            setLocalOffer.get(5, TimeUnit.SECONDS);

            CompletableFuture<Void> setRemoteOffer = new CompletableFuture<>();
            peer2.setRemoteDescription(offer, new SetSessionDescriptionObserver() {
                @Override public void onSuccess() { setRemoteOffer.complete(null); }
                @Override public void onFailure(String error) { setRemoteOffer.completeExceptionally(new RuntimeException(error)); }
            });
            setRemoteOffer.get(5, TimeUnit.SECONDS);

            CompletableFuture<RTCSessionDescription> answerFuture = new CompletableFuture<>();
            peer2.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                @Override public void onSuccess(RTCSessionDescription sdp) { answerFuture.complete(sdp); }
                @Override public void onFailure(String error) { answerFuture.completeExceptionally(new RuntimeException(error)); }
            });
            RTCSessionDescription answer = answerFuture.get(5, TimeUnit.SECONDS);

            CompletableFuture<Void> setLocalAnswer = new CompletableFuture<>();
            peer2.setLocalDescription(answer, new SetSessionDescriptionObserver() {
                @Override public void onSuccess() { setLocalAnswer.complete(null); }
                @Override public void onFailure(String error) { setLocalAnswer.completeExceptionally(new RuntimeException(error)); }
            });
            setLocalAnswer.get(5, TimeUnit.SECONDS);

            CompletableFuture<Void> setRemoteAnswer = new CompletableFuture<>();
            peer1.setRemoteDescription(answer, new SetSessionDescriptionObserver() {
                @Override public void onSuccess() { setRemoteAnswer.complete(null); }
                @Override public void onFailure(String error) { setRemoteAnswer.completeExceptionally(new RuntimeException(error)); }
            });
            setRemoteAnswer.get(5, TimeUnit.SECONDS);

            // Push frames to generate traffic
            for (int i = 0; i < 20; i++) {
                NativeI420Buffer buf = NativeI420Buffer.allocate(640, 480);
                VideoFrame frame = new VideoFrame(buf, 0);
                source.pushFrame(frame);
                frame.release();
                Thread.sleep(30);
            }

            // Query stats from peer1 and peer2
            CompletableFuture<RTCStatsReport> statsFuture = new CompletableFuture<>();
            peer1.getStats(statsFuture::complete);
            RTCStatsReport report = statsFuture.get(5, TimeUnit.SECONDS);

            CompletableFuture<RTCStatsReport> stats2Future = new CompletableFuture<>();
            peer2.getStats(stats2Future::complete);
            RTCStatsReport report2 = stats2Future.get(5, TimeUnit.SECONDS);

            assertNotNull(report);
            System.out.println("=== REAL WEBRTC STATS ATTRIBUTES REPORT PEER 1 ===");
            for (RTCStats stat : report.getStats().values()) {
                Set<String> keys = stat.getAttributes().keySet();
                System.out.println("STAT TYPE: " + stat.getType() + " -> keys: " + keys);
            }
            System.out.println("=== REAL WEBRTC STATS ATTRIBUTES REPORT PEER 2 (RECEIVER) ===");
            for (RTCStats stat : report2.getStats().values()) {
                Set<String> keys = stat.getAttributes().keySet();
                System.out.println("STAT TYPE: " + stat.getType() + " -> keys: " + keys);
            }
            System.out.println("===========================================");

        } finally {
            peer1.close();
            peer2.close();
            track.dispose();
            source.dispose();
            factory.dispose();
            audio.dispose();
        }
    }
}
