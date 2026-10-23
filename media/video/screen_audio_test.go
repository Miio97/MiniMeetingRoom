package main

import (
	"github.com/pion/webrtc/v4"
	"testing"
)

func publishTestKind(t *testing.T, e *engine, owner, room, kind, id, parent string) *mediaPeer {
	t.Helper()
	pc, err := e.api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = pc.Close() })
	track, err := webrtc.NewTrackLocalStaticRTP(kindCodec(kind), id, "stream")
	if err != nil {
		t.Fatal(err)
	}
	sender, err := pc.AddTrack(track)
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			if _, _, err := sender.ReadRTCP(); err != nil {
				return
			}
		}
	}()
	negotiateClient(t, e, pc, controlRequest{Operation: "publish", Kind: kind, Room: room, Participant: owner, Generation: 1, Transport: id, Publication: parent})
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.peers[id]
}

func TestScreenAudioRequiresOwnedScreenAndNeverStopsMicrophone(t *testing.T) {
	e, err := newEngine(testKey)
	if err != nil {
		t.Fatal(err)
	}
	defer e.close()
	lease(t, e, "a", "room")
	lease(t, e, "b", "room")
	mic := publishTestKind(t, e, "a", "room", "AUDIO", "mic", "")
	denied := func(owner, parent string) {
		t.Helper()
		status, _ := control(t, e, controlRequest{Operation: "publish", Kind: "SCREEN_AUDIO", Room: "room", Participant: owner, Generation: 1, Transport: "denied", Publication: parent, SDP: "v=0"})
		if status != 403 {
			t.Fatal("system audio admitted without its owner's screen", status)
		}
	}
	denied("a", "missing-screen")
	screen := publishTestKind(t, e, "a", "room", "SCREEN", "screen", "")
	denied("b", screen.id)
	denied("a", "stale-screen")
	sound := publishTestKind(t, e, "a", "room", "SCREEN_AUDIO", "sound", screen.id)
	if !audioKind(sound.kind) || sound.screen != screen || kindCodec(sound.kind).MimeType != webrtc.MimeTypeOpus {
		t.Fatal("system sound codec or parent incorrect")
	}
	control(t, e, controlRequest{Operation: "stop", Kind: "SCREEN_AUDIO", Room: "room", Participant: "a", Generation: 1})
	if !sound.closed.Load() || screen.closed.Load() || mic.closed.Load() {
		t.Fatal("system sound off affected screen or mic")
	}
	sound = publishTestKind(t, e, "a", "room", "SCREEN_AUDIO", "sound-2", screen.id)
	next := publishTestKind(t, e, "a", "room", "SCREEN", "screen-2", "")
	if !sound.closed.Load() || !screen.closed.Load() || next.closed.Load() || mic.closed.Load() {
		t.Fatal("replacing screen leaked its system audio or stopped mic")
	}
	sound = publishTestKind(t, e, "a", "room", "SCREEN_AUDIO", "sound-3", next.id)
	control(t, e, controlRequest{Operation: "stop", Kind: "SCREEN", Room: "room", Participant: "a", Generation: 1})
	if !next.closed.Load() || !sound.closed.Load() || mic.closed.Load() {
		t.Fatal("host screen stop did not retire only the screen and its sound")
	}
	e.mu.Lock()
	remaining := len(e.peers)
	e.mu.Unlock()
	if remaining != 1 {
		t.Fatal("screen/audio cleanup leak", remaining)
	}
}
