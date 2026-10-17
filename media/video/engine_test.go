package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"github.com/pion/rtp"
	"github.com/pion/webrtc/v4"
	"net/http/httptest"
	"testing"
	"time"
)

const testKey = "test-control-key-not-a-login-token-12345678901234567890"

func TestKeyframeRequestsStayBoundedWithoutTransportWriter(t *testing.T) {
	e := &engine{}
	source := &mediaPeer{keyframes: make(chan struct{}, 1)}
	source.ssrc.Store(123)
	// No PC or worker: feedback cannot drain. Requests must still return safely.
	for i := 0; i < 100; i++ {
		source.lastPLI.Store(0)
		e.keyframe(source)
	}
	if len(source.keyframes) != 1 {
		t.Fatal("feedback queue is not coalesced")
	}
	source.closed.Store(true)
	<-source.keyframes
	e.keyframe(source)
	if len(source.keyframes) != 0 {
		t.Fatal("closed source accepted feedback")
	}
}

func control(t *testing.T, e *engine, request controlRequest) (int, map[string]any) {
	t.Helper()
	request.Instance = e.instance
	body, _ := json.Marshal(request)
	r := httptest.NewRequest("POST", "/v1/control", bytes.NewReader(body))
	r.Header.Set("X-MMR-Media-Key", testKey)
	w := httptest.NewRecorder()
	e.ServeHTTP(w, r)
	result := map[string]any{}
	if json.Unmarshal(w.Body.Bytes(), &result) != nil {
		t.Fatal("invalid control response")
	}
	return w.Code, result
}
func lease(t *testing.T, e *engine, owner, room string) {
	t.Helper()
	status, _ := control(t, e, controlRequest{Operation: "renew", Room: room, Participant: owner, Generation: 1, TTLMillis: 15000})
	if status != 200 {
		t.Fatal("lease rejected")
	}
}
func TestControlAuthLimitsAndLeaseGeneration(t *testing.T) {
	e, err := newEngine(testKey)
	if err != nil {
		t.Fatal("engine failed")
	}
	defer e.close()
	w := httptest.NewRecorder()
	e.ServeHTTP(w, httptest.NewRequest("GET", "/health", nil))
	if w.Code != 401 {
		t.Fatal("missing control authentication accepted")
	}
	lease(t, e, "participant-a", "room-a")
	status, _ := control(t, e, controlRequest{Operation: "renew", Room: "room-a", Participant: "participant-a", Generation: 2, TTLMillis: 15000})
	if status != 409 {
		t.Fatal("generation was replaced")
	}
	status, _ = control(t, e, controlRequest{Operation: "renew", Room: "room-a", Participant: "participant-a", Generation: 1, TTLMillis: 15001})
	if status != 400 {
		t.Fatal("unbounded lease accepted")
	}
	status, _ = control(t, e, controlRequest{Operation: "publish", Room: "room-b", Participant: "participant-a", Generation: 1, Transport: "t-1", SDP: "v=0"})
	if status != 403 {
		t.Fatal("cross-room lease accepted")
	}
	e.mu.Lock()
	l := e.leases["participant-a"]
	l.expiry = time.Now().Add(-time.Second)
	e.leases["participant-a"] = l
	e.mu.Unlock()
	e.expire()
	e.mu.Lock()
	size := len(e.leases)
	e.mu.Unlock()
	if size != 0 {
		t.Fatal("lease did not expire")
	}
	for i := 0; i < 2; i++ {
		status, _ = control(t, e, controlRequest{Operation: "revoke", Room: "room-a", Participant: "participant-a", Generation: 1})
		if status != 200 {
			t.Fatal("revoke not idempotent")
		}
	}
}
func TestCodecPacketOwnershipExtensionsWrapAndDuplicates(t *testing.T) {
	for _, sequence := range []uint16{65535, 0, 1, 0, 65535} {
		original := &rtp.Packet{Header: rtp.Header{Version: 2, Padding: true, Extension: true, Marker: true, PayloadType: 96, SequenceNumber: sequence, Timestamp: ^uint32(0), SSRC: 42, CSRC: []uint32{1}}, Payload: []byte{0x10, 3, 4}}
		output := codecPacket(original)
		if output.SequenceNumber != sequence || output.Timestamp != original.Timestamp || !output.Marker || !bytes.Equal(output.Payload, original.Payload) {
			t.Fatal("codec/sequence/timestamp altered")
		}
		if output.Extension || output.Padding || len(output.CSRC) != 0 {
			t.Fatal("transport-specific header copied")
		}
		original.Payload[0] = 0
		if output.Payload[0] != 0x10 {
			t.Fatal("queue does not own payload")
		}
	}
}
func clientOffer(t *testing.T, pc *webrtc.PeerConnection) string {
	t.Helper()
	offer, err := pc.CreateOffer(nil)
	if err != nil {
		t.Fatal("offer failed")
	}
	gather := webrtc.GatheringCompletePromise(pc)
	if pc.SetLocalDescription(offer) != nil {
		t.Fatal("local offer failed")
	}
	select {
	case <-gather:
	case <-time.After(5 * time.Second):
		t.Fatal("gather timeout")
	}
	return pc.LocalDescription().SDP
}
func negotiateClient(t *testing.T, e *engine, pc *webrtc.PeerConnection, request controlRequest) {
	t.Helper()
	request.SDP = clientOffer(t, pc)
	status, result := control(t, e, request)
	if status != 200 {
		t.Fatalf("negotiate rejected status=%d", status)
	}
	answer, ok := result["sdp"].(string)
	if !ok || pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeAnswer, SDP: answer}) != nil {
		t.Fatal("remote answer failed")
	}
}
func TestRealPionFanoutPayloadIsolationAndRevoke(t *testing.T) {
	for _, kind := range []string{"VIDEO", "AUDIO", "SCREEN"} {
		t.Run(kind, func(t *testing.T) { realFanout(t, kind) })
	}
}
func realFanout(t *testing.T, kind string) {
	e, err := newEngine(testKey)
	if err != nil {
		t.Fatal("engine failed")
	}
	defer e.close()
	lease(t, e, "publisher", "room-a")
	lease(t, e, "receiver-a", "room-a")
	lease(t, e, "receiver-b", "room-a")
	lease(t, e, "foreign", "room-b")
	pc, err := e.api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal("client peer failed")
	}
	defer pc.Close()
	track, _ := webrtc.NewTrackLocalStaticRTP(kindCodec(kind), "media", "stream")
	sender, err := pc.AddTrack(track)
	if err != nil {
		t.Fatal("track failed")
	}
	go func() {
		for {
			if _, _, err := sender.ReadRTCP(); err != nil {
				return
			}
		}
	}()
	negotiateClient(t, e, pc, controlRequest{Kind: kind, Operation: "publish", Room: "room-a", Participant: "publisher", Generation: 1, Transport: "publication-a"})
	done := make(chan struct{})
	defer close(done)
	go func() {
		ticker := time.NewTicker(25 * time.Millisecond)
		defer ticker.Stop()
		sequence := uint16(65530)
		for {
			select {
			case <-done:
				return
			case <-ticker.C:
				sequence++
				_ = track.WriteRTP(&rtp.Packet{Header: rtp.Header{Version: 2, Marker: true, PayloadType: 96, SequenceNumber: sequence, Timestamp: uint32(sequence) * 3000, SSRC: 321}, Payload: []byte{0x10, 11, 22, 33, 44}})
			}
		}
	}()
	deadline := time.Now().Add(5 * time.Second)
	for e.ingress.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if e.ingress.Load() == 0 {
		t.Fatal("no publisher RTP reached SFU")
	}
	status, _ := control(t, e, controlRequest{Kind: kind, Operation: "subscribe", Room: "room-b", Participant: "foreign", Generation: 1, Transport: "cross-room", Source: "publisher", Publication: "publication-a", SDP: "v=0"})
	if status != 403 {
		t.Fatal("foreign room subscription accepted")
	}
	channels := []chan []byte{}
	for _, owner := range []string{"receiver-a", "receiver-b"} {
		receiver, err := e.api.NewPeerConnection(webrtc.Configuration{})
		if err != nil {
			t.Fatal("receiver failed")
		}
		defer receiver.Close()
		frames := make(chan []byte, 32)
		channels = append(channels, frames)
		receiver.OnTrack(func(remote *webrtc.TrackRemote, _ *webrtc.RTPReceiver) {
			for {
				packet, _, err := remote.ReadRTP()
				if err != nil {
					return
				}
				select {
				case frames <- append([]byte(nil), packet.Payload...):
				default:
				}
			}
		})
		_, err = receiver.AddTransceiverFromKind(func() webrtc.RTPCodecType {
			if kind == "AUDIO" {
				return webrtc.RTPCodecTypeAudio
			}
			return webrtc.RTPCodecTypeVideo
		}(), webrtc.RTPTransceiverInit{Direction: webrtc.RTPTransceiverDirectionRecvonly})
		if err != nil {
			t.Fatal("receiver transceiver failed")
		}
		negotiateClient(t, e, receiver, controlRequest{Kind: kind, Operation: "subscribe", Room: "room-a", Participant: owner, Generation: 1, Transport: owner + "-transport", Source: "publisher", Publication: "publication-a"})
	}
	for _, frames := range channels {
		select {
		case payload := <-frames:
			if !bytes.Equal(payload, []byte{0x10, 11, 22, 33, 44}) {
				t.Fatal("SFU transcoded or changed payload")
			}
		case <-time.After(5 * time.Second):
			t.Fatal("receiver did not receive via SFU")
		}
	}
	if e.forwarded.Load() == 0 || e.forwardedBytes.Load() == 0 {
		t.Fatal("forward diagnostics absent")
	}
	control(t, e, controlRequest{Operation: "revoke", Room: "room-a", Participant: "receiver-a", Generation: 1})
	e.mu.Lock()
	count := len(e.peers)
	e.mu.Unlock()
	if count != 2 {
		t.Fatal("revoke removed unrelated publisher/receiver")
	}
	control(t, e, controlRequest{Kind: kind, Operation: "stop", Room: "room-a", Participant: "publisher", Generation: 1})
	e.mu.Lock()
	count = len(e.peers)
	e.mu.Unlock()
	if count != 0 {
		t.Fatal("camera stop left a track/subscription")
	}
}

func TestEngineConfigurableCapacity(t *testing.T) {
	t.Setenv("MINIMEETING_MAX_PEERS", "50")
	t.Setenv("MMR_MEDIA_MAX_LEASES", "256")
	t.Setenv("MMR_MEDIA_SLOTS", "128")
	t.Setenv("MMR_MEDIA_PORT_MIN", "50000")
	t.Setenv("MMR_MEDIA_PORT_MAX", "50100")

	e, err := newEngine(testKey)
	if err != nil {
		t.Fatalf("failed to init engine with config: %v", err)
	}
	defer e.close()

	if e.maxPeers != 5000 {
		t.Fatalf("expected maxPeers=5000, got %d", e.maxPeers)
	}
	if e.maxLeases != 256 {
		t.Fatalf("expected maxLeases=256, got %d", e.maxLeases)
	}
	if cap(e.slots) != 128 {
		t.Fatalf("expected slots cap=128, got %d", cap(e.slots))
	}

	// Verify adding multiple leases up to 50 works without panic or leak
	for i := 1; i <= 50; i++ {
		pId := fmt.Sprintf("p-%d", i)
		control(t, e, controlRequest{Operation: "renew", Room: "room-50", Participant: pId, Generation: 1, TTLMillis: 5000})
	}

	e.mu.Lock()
	if len(e.leases) != 50 {
		t.Fatalf("expected 50 leases, got %d", len(e.leases))
	}
	e.mu.Unlock()

	// Revoke some and verify no leak
	for i := 1; i <= 25; i++ {
		pId := fmt.Sprintf("p-%d", i)
		control(t, e, controlRequest{Operation: "revoke", Room: "room-50", Participant: pId, Generation: 1})
	}

	e.mu.Lock()
	if len(e.leases) != 25 {
		t.Fatalf("expected 25 leases remaining, got %d", len(e.leases))
	}
	e.mu.Unlock()
}

