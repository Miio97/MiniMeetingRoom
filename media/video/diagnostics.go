package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"time"
)

// One process owner; only fixed numeric fields, never SDP/ICE/identities or per-packet logs.
type diagnostics struct {
	mu        sync.Mutex
	file      *os.File
	start     time.Time
	run       string
	sequence  int
	bytes     int
	truncated bool
}

func startDiagnostics() *diagnostics {
	d := &diagnostics{start: time.Now(), run: os.Getenv("MMR_DIAGNOSTICS_RUN_ID")}
	if os.Getenv("MMR_DIAGNOSTICS_ENABLED") == "false" {
		return d
	}
	if d.run == "" {
		d.run = fmt.Sprintf("video-%d-%d", os.Getpid(), time.Now().UnixNano())
	}
	if len(d.run) > 96 || !opaque.MatchString(d.run) {
		d.run = "video-local"
	}
	directory := os.Getenv("MMR_DIAGNOSTICS_DIR")
	if directory == "" {
		directory = "logs/diagnostics"
	}
	if os.MkdirAll(directory, 0700) != nil {
		return d
	}
	name := fmt.Sprintf("sfu-video-%s-%d.txt", time.Now().UTC().Format("20060102T150405.000Z"), os.Getpid())
	file, err := os.OpenFile(filepath.Join(directory, name), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return d
	}
	d.file = file
	d.event("run.start", map[string]any{"cpu_count": runtime.NumCPU(), "format": "utf8_json_lines"})
	return d
}
func (d *diagnostics) event(event string, fields map[string]any) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.file == nil {
		return
	}
	if d.bytes > 10*1024*1024 && event != "run.end" {
		d.truncated = true
		return
	}
	d.sequence++
	record := map[string]any{"schema": "mmr.diagnostics.v1", "time_utc": time.Now().UTC().Format(time.RFC3339Nano), "run_id": d.run, "pid": os.Getpid(), "role": "sfu", "elapsed_ms": float64(time.Since(d.start).Microseconds()) / 1000, "event": event, "fields": fields, "seq": d.sequence, "dropped_events": 0}
	encoded, err := json.Marshal(record)
	if err != nil {
		return
	}
	encoded = append(encoded, '\n')
	written, _ := d.file.Write(encoded)
	d.bytes += written
}
func (d *diagnostics) sample(e *engine) {
	e.mu.Lock()
	peers := len(e.peers)
	leases := len(e.leases)
	queued := 0
	queuedBytes := 0
	for _, peer := range e.peers {
		if peer.queue != nil {
			depth, size := peer.queue.depth()
			queued += depth
			queuedBytes += size
		}
	}
	e.mu.Unlock()
	for _, kind := range []string{"AUDIO", "VIDEO", "SCREEN"} {
		e.mu.Lock()
		packets, size, count := 0, 0, 0
		for _, peer := range e.peers {
			if peer.kind == kind {
				count++
				if peer.queue != nil {
					a, b := peer.queue.depth()
					packets += a
					size += b
				}
			}
		}
		e.mu.Unlock()
		c := e.byKind[kind]
		d.event("video.engine.sample", map[string]any{"kind": kind, "active_peer_connections": count, "queue_packets": packets, "queue_bytes": size, "queue_drops": c.drops.Load(), "ingress_packets": c.ingress.Load(), "forwarded_packets": c.forwarded.Load(), "ingress_codec_bytes": c.ingressBytes.Load(), "forwarded_codec_bytes": c.forwardedBytes.Load(), "write_errors": c.writeErrors.Load(), "pli_requests": c.pli.Load()})
	}
	var memory runtime.MemStats
	runtime.ReadMemStats(&memory)
	d.event("video.engine.sample", map[string]any{"active_peer_connections": peers, "participant_count": leases, "queue_packets": queued, "queue_bytes": queuedBytes, "queue_drops": e.drops.Load(), "ingress_packets": e.ingress.Load(), "forwarded_packets": e.forwarded.Load(), "ingress_codec_bytes": e.ingressBytes.Load(), "forwarded_codec_bytes": e.forwardedBytes.Load(), "write_errors": e.writeErrors.Load(), "pli_requests": e.pli.Load(), "go_heap_bytes": memory.Alloc, "goroutine_count": runtime.NumGoroutine()})
}
func (d *diagnostics) close() {
	d.event("run.end", map[string]any{"clean_shutdown": true, "file_truncated": d.truncated, "dropped_events": 0})
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.file != nil {
		_ = d.file.Close()
		d.file = nil
	}
}
