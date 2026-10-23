package main

import (
	"net/http"
	"strconv"
	"time"
)

// The authenticated Java adapter has one bounded long poll for this instance.
// No login/session data crosses this boundary; clients only receive filtered snapshots.
func (e *engine) watchSources(w http.ResponseWriter, r *http.Request) {
	after, err := strconv.ParseUint(r.URL.Query().Get("after"), 10, 64)
	if err != nil {
		reply(w, 400, map[string]any{"code": "INVALID_REQUEST"})
		return
	}
	e.mu.Lock()
	if e.sourceChanged == nil {
		e.sourceChanged = make(chan struct{})
	}
	changed, revision := e.sourceChanged, e.sourceRevision
	e.mu.Unlock()
	if after == revision && !e.stopped.Load() {
		timer := time.NewTimer(8 * time.Second)
		defer timer.Stop()
		select {
		case <-changed:
		case <-timer.C:
		case <-r.Context().Done():
			return
		}
	}
	e.mu.Lock()
	rooms := map[string]any{}
	for _, peer := range e.peers {
		if !peer.publisher || peer.closed.Load() || !e.liveLocked(peer.owner, peer.room, peer.generation) {
			continue
		}
		room, ok := rooms[peer.room].(map[string]any)
		if !ok {
			room = map[string]any{"sources": sourceKinds(), "pausedSources": sourceKinds(), "generations": map[string]int64{}}
			rooms[peer.room] = room
		}
		field := "sources"
		if peer.paused.Load() {
			field = "pausedSources"
		} else if !peer.active.Load() {
			continue
		}
		room[field].(map[string]map[string]string)[peer.kind][peer.owner] = peer.id
		room["generations"].(map[string]int64)[peer.owner] = peer.generation
	}
	revision = e.sourceRevision
	e.mu.Unlock()
	reply(w, 200, map[string]any{"instance": e.instance, "revision": revision, "rooms": rooms})
}

func sourceKinds() map[string]map[string]string {
	return map[string]map[string]string{"VIDEO": {}, "AUDIO": {}, "SCREEN": {}, "SCREEN_AUDIO": {}}
}

// Caller holds mu. Closing a channel broadcasts readiness without keeping a
// queue of events or waiting for a slow consumer. Every reply is a full snapshot.
func (e *engine) sourcesChangedLocked() {
	e.sourceRevision++
	if e.sourceChanged != nil {
		close(e.sourceChanged)
	}
	e.sourceChanged = make(chan struct{})
}

func (e *engine) expirePaused() {
	e.mu.Lock()
	var expired []*mediaPeer
	for _, peer := range e.peers {
		if peer.publisher && peer.paused.Load() && !peer.pausedAt.IsZero() && time.Since(peer.pausedAt) >= 30*time.Second {
			expired = append(expired, peer)
		}
	}
	e.mu.Unlock()
	for _, peer := range expired {
		// Recheck under mu: a resume may have won after the scan.
		e.mu.Lock()
		if e.peers[peer.id] != peer || !peer.paused.Load() || time.Since(peer.pausedAt) < 30*time.Second {
			e.mu.Unlock()
			continue
		}
		removed := e.detachPublisherLocked(peer)
		e.mu.Unlock()
		for _, item := range removed {
			item.close()
		}
	}
}

func (e *engine) detachPublisherLocked(source *mediaPeer) []*mediaPeer {
	removed := []*mediaPeer{}
	for id, peer := range e.peers {
		if peer == source || peer.upstream == source {
			delete(e.peers, id)
			removed = append(removed, peer)
		}
	}
	if len(removed) > 0 {
		e.sourcesChangedLocked()
	}
	return removed
}

// Paused transports can be reclaimed to admit new work. Active meetings are
// never evicted, and the same global budget still applies to retained peers.
func (e *engine) reclaimPausedLocked(limit int) []*mediaPeer {
	var removed []*mediaPeer
	for len(e.peers)+len(e.pending) >= limit {
		var oldest *mediaPeer
		for _, peer := range e.peers {
			if peer.publisher && peer.paused.Load() && (oldest == nil || peer.pausedAt.Before(oldest.pausedAt)) {
				oldest = peer
			}
		}
		if oldest == nil {
			break
		}
		removed = append(removed, e.detachPublisherLocked(oldest)...)
	}
	return removed
}
