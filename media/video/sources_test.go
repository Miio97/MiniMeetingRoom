package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/pion/webrtc/v4"
)

func sourceSnapshot(t *testing.T, e *engine, after uint64) map[string]any {
	t.Helper()
	r := httptest.NewRequest("GET", fmt.Sprintf("/v1/sources?after=%d", after), nil)
	r.Header.Set("X-MMR-Media-Key", testKey)
	w := httptest.NewRecorder()
	e.ServeHTTP(w, r)
	var result map[string]any
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &result) != nil {
		t.Fatal("source snapshot failed", w.Code)
	}
	return result
}

func TestPublicationPauseResumeSnapshotAndBoundedRetention(t *testing.T) {
	for _, kind := range []string{"AUDIO", "VIDEO"} {
		t.Run(kind, func(t *testing.T) {
			e, err := newEngine(testKey)
			if err != nil {
				t.Fatal(err)
			}
			defer e.close()
			lease(t, e, "owner", "room-a")
			lease(t, e, "foreign", "room-b")
			pc, err := e.api.NewPeerConnection(webrtc.Configuration{})
			if err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithCancel(context.Background())
			peer := &mediaPeer{id: "retained", room: "room-a", owner: "owner", kind: kind, generation: 1, publisher: true, pc: pc, ctx: ctx, cancel: cancel}
			peer.active.Store(true)
			e.mu.Lock()
			e.peers[peer.id] = peer
			e.sourcesChangedLocked()
			e.mu.Unlock()
			initial := sourceSnapshot(t, e, 0)
			room := initial["rooms"].(map[string]any)["room-a"].(map[string]any)
			if room["sources"].(map[string]any)[kind].(map[string]any)["owner"] != peer.id {
				t.Fatal("active source absent")
			}
			request := controlRequest{Operation: "pause", Kind: kind, Room: peer.room, Participant: peer.owner, Generation: 1, Transport: peer.id}
			foreign := request
			foreign.Participant = "foreign"
			foreign.Room = "room-b"
			if status, _ := control(t, e, foreign); status != 409 {
				t.Fatal("cross-room pause accepted")
			}
			stale := request
			stale.Generation = 2
			if status, _ := control(t, e, stale); status != 403 {
				t.Fatal("stale generation accepted")
			}
			if status, _ := control(t, e, request); status != 200 {
				t.Fatal("pause rejected")
			}
			paused := sourceSnapshot(t, e, 0)
			room = paused["rooms"].(map[string]any)["room-a"].(map[string]any)
			if len(room["sources"].(map[string]any)[kind].(map[string]any)) != 0 || room["pausedSources"].(map[string]any)[kind].(map[string]any)["owner"] != peer.id || peer.closed.Load() {
				t.Fatal("pause did not preserve transport and hide active source")
			}
			if paused["revision"].(float64) <= initial["revision"].(float64) {
				t.Fatal("revision did not advance")
			}
			request.Operation = "resume"
			if status, _ := control(t, e, request); status != 200 {
				t.Fatal("resume rejected")
			}
			if e.peers[peer.id] != peer || peer.paused.Load() {
				t.Fatal("resume recreated peer")
			}
			// An expired scan must not destroy a transport which resumed before commit.
			e.expirePaused()
			if peer.closed.Load() {
				t.Fatal("active transport expired")
			}
			request.Operation = "pause"
			control(t, e, request)
			e.mu.Lock()
			peer.pausedAt = time.Now().Add(-31 * time.Second)
			e.mu.Unlock()
			e.expirePaused()
			if !peer.closed.Load() || len(e.peers) != 0 {
				t.Fatal("idle retention was unbounded")
			}
			request.Operation = "resume"
			if status, body := control(t, e, request); status != 409 || body["code"] != "MEDIA_REUSE_EXPIRED" {
				t.Fatal("expired resume did not fail clearly")
			}
		})
	}
}

func TestSourceLongPollWakesWithoutHoldingEngineLock(t *testing.T) {
	e, err := newEngine(testKey)
	if err != nil {
		t.Fatal(err)
	}
	defer e.close()
	initial := sourceSnapshot(t, e, 0)
	finished := make(chan map[string]any, 1)
	go func() { finished <- sourceSnapshot(t, e, uint64(initial["revision"].(float64))) }()
	// The watch may start before or after the change; both paths must return the new snapshot.
	e.mu.Lock()
	e.sourcesChangedLocked()
	e.mu.Unlock()
	select {
	case snapshot := <-finished:
		if snapshot["revision"].(float64) <= initial["revision"].(float64) {
			t.Fatal("stale long poll")
		}
	case <-time.After(time.Second):
		t.Fatal("watch held lock or missed source revision")
	}
}
