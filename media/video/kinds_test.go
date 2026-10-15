package main

import (
	"context"
	"github.com/pion/rtp"
	"github.com/pion/webrtc/v4"
	"testing"
	"time"
)

func TestKindsAreIndependentAndScreenIsExclusive(t *testing.T) {
	e, err := newEngine(testKey)
	if err != nil {
		t.Fatal("engine failed")
	}
	defer e.close()
	lease(t, e, "a", "room")
	lease(t, e, "b", "room")
	lease(t, e, "foreign", "other")
	publish := func(owner, kind, id string) {
		pc, err := e.api.NewPeerConnection(webrtc.Configuration{})
		if err != nil {
			t.Fatal("peer failed")
		}
		t.Cleanup(func() { _ = pc.Close() })
		track, err := webrtc.NewTrackLocalStaticRTP(kindCodec(kind), id, "stream")
		if err != nil {
			t.Fatal("track failed")
		}
		sender, err := pc.AddTrack(track)
		if err != nil {
			t.Fatal("sender failed")
		}
		go func() {
			for {
				if _, _, err := sender.ReadRTCP(); err != nil {
					return
				}
			}
		}()
		negotiateClient(t, e, pc, controlRequest{Operation: "publish", Kind: kind, Room: "room", Participant: owner, Generation: 1, Transport: id})
	}
	for _, kind := range []string{"AUDIO", "VIDEO", "SCREEN"} {
		publish("a", kind, "a-"+kind)
	}
	publish("b", "AUDIO", "b-audio")
	status, result := control(t, e, controlRequest{Operation: "publish", Kind: "AUDIO", Room: "room", Participant: "a", Generation: 1, Transport: "duplicate", SDP: "v=0"})
	if status != 409 || result["code"] != "ALREADY_PUBLISHING" {
		t.Fatal("duplicate kind accepted")
	}
	status, result = control(t, e, controlRequest{Operation: "publish", Kind: "SCREEN", Room: "room", Participant: "b", Generation: 1, Transport: "second-screen", SDP: "v=0"})
	if status != 409 || result["code"] != "SHARE_IN_USE" {
		t.Fatal("second screen accepted")
	}
	status, _ = control(t, e, controlRequest{Operation: "state", Kind: "AUDIO", Room: "room", Participant: "foreign", Generation: 1})
	if status != 403 {
		t.Fatal("cross room state accepted")
	}
	status, _ = control(t, e, controlRequest{Operation: "stop", Kind: "AUDIO", Room: "other", Participant: "a", Generation: 1})
	if status != 403 {
		t.Fatal("cross room stop accepted")
	}
	control(t, e, controlRequest{Operation: "stop", Kind: "AUDIO", Room: "room", Participant: "a", Generation: 1})
	e.mu.Lock()
	count := len(e.peers)
	camera := e.publisherLocked("a", "VIDEO")
	screen := e.publisherLocked("a", "SCREEN")
	other := e.publisherLocked("b", "AUDIO")
	e.mu.Unlock()
	if count != 3 || camera == nil || screen == nil || other == nil {
		t.Fatal("audio stop affected other kinds")
	}
	control(t, e, controlRequest{Operation: "stop", Kind: "SCREEN", Room: "room", Participant: "a", Generation: 1})
	publish("b", "SCREEN", "b-screen")
	control(t, e, controlRequest{Operation: "revoke", Room: "room", Participant: "a", Generation: 1})
	e.mu.Lock()
	count = len(e.peers)
	e.mu.Unlock()
	if count != 2 {
		t.Fatal("revoke affected another participant")
	}
	e.mu.Lock()
	l := e.leases["b"]
	l.expiry = time.Now().Add(-time.Second)
	e.leases["b"] = l
	e.mu.Unlock()
	e.expire()
	e.mu.Lock()
	remaining := len(e.peers)
	e.mu.Unlock()
	if remaining != 0 {
		t.Fatal("expiry left audio/screen transports")
	}
	audio := &mediaPeer{kind: "AUDIO", keyframes: make(chan struct{}, 1)}
	audio.ssrc.Store(1)
	e.keyframe(audio)
	if len(audio.keyframes) != 0 {
		t.Fatal("audio requested PLI")
	}
}

func TestQueuesBoundPacketBytesAndCancellation(t *testing.T) {
	q := newPacketQueue()
	packet := &rtp.Packet{Header: rtp.Header{Version: 2}, Payload: make([]byte, 100)}
	for i := 0; i < queuePacketLimit; i++ {
		if !q.offer(packet, time.Now()) {
			t.Fatal("early packet drop")
		}
	}
	if q.offer(packet, time.Now()) {
		t.Fatal("packet bound exceeded")
	}
	q.clear()
	if q.offer(&rtp.Packet{Payload: make([]byte, queueByteLimit+1)}, time.Now()) {
		t.Fatal("byte bound exceeded")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, ok := q.pop(ctx); ok {
		t.Fatal("cancelled queue delivered")
	}
	q.close()
	if q.offer(packet, time.Now()) {
		t.Fatal("closed queue accepted")
	}
}

func TestExpiredAudioPacketDropsWithoutVideoFeedback(t *testing.T) {
	e, err := newEngine(testKey)
	if err != nil {
		t.Fatal("engine failed")
	}
	defer e.close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	peer := &mediaPeer{kind: "AUDIO", ctx: ctx, queue: newPacketQueue(), upstream: &mediaPeer{kind: "AUDIO"}}
	if !peer.queue.offer(&rtp.Packet{Header: rtp.Header{Version: 2}, Payload: []byte{1}}, time.Now().Add(-queueMaxAge-time.Second)) {
		t.Fatal("queue rejected")
	}
	done := make(chan struct{})
	go func() { e.forward(peer); close(done) }()
	deadline := time.Now().Add(time.Second)
	for e.drops.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("writer did not cancel")
	}
	if e.drops.Load() != 1 || e.byKind["AUDIO"].drops.Load() != 1 || e.pli.Load() != 0 {
		t.Fatal("expired audio policy or counters incorrect")
	}
}
