package main

import (
	"context"
	"sync"
	"time"

	"github.com/pion/rtp"
)

const (
	queuePacketLimit = 256
	queueByteLimit   = 2 * 1024 * 1024
	queueMaxAge      = 750 * time.Millisecond
)

type queuedPacket struct {
	packet   *rtp.Packet
	queuedAt time.Time
	bytes    int
}

// Each route owns a finite queue and one writer. Admission never waits for a
// subscriber. Packets and payloads are owned by the queue until pop returns them.
type packetQueue struct {
	mu     sync.Mutex
	items  []queuedPacket
	bytes  int
	closed bool
	wake   chan struct{}
}

func newPacketQueue() *packetQueue {
	return &packetQueue{wake: make(chan struct{}, 1)}
}

func (q *packetQueue) offer(packet *rtp.Packet, now time.Time) bool {
	q.mu.Lock()
	defer q.mu.Unlock()
	cost := packet.MarshalSize()
	if q.closed || len(q.items) >= queuePacketLimit || cost > queueByteLimit-q.bytes {
		return false
	}
	q.items = append(q.items, queuedPacket{packet, now, cost})
	q.bytes += cost
	q.notify()
	return true
}

func (q *packetQueue) pop(ctx context.Context) (queuedPacket, bool) {
	for {
		select {
		case <-ctx.Done():
			return queuedPacket{}, false
		default:
		}
		q.mu.Lock()
		if len(q.items) > 0 {
			item := q.items[0]
			q.items[0] = queuedPacket{}
			q.items = q.items[1:]
			q.bytes -= item.bytes
			q.mu.Unlock()
			return item, true
		}
		closed := q.closed
		q.mu.Unlock()
		if closed {
			return queuedPacket{}, false
		}
		select {
		case <-ctx.Done():
			return queuedPacket{}, false
		case <-q.wake:
		}
	}
}

func (q *packetQueue) notify() {
	select {
	case q.wake <- struct{}{}:
	default:
	}
}

func (q *packetQueue) close() {
	q.mu.Lock()
	q.closed = true
	q.notify()
	q.mu.Unlock()
}

func (q *packetQueue) clear() int {
	q.mu.Lock()
	defer q.mu.Unlock()
	count := len(q.items)
	q.items = nil
	q.bytes = 0
	return count
}

func (q *packetQueue) depth() (int, int) {
	q.mu.Lock()
	defer q.mu.Unlock()
	return len(q.items), q.bytes
}
