package main

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"hash"
	"sync"
	"time"

	"github.com/pion/rtp"
	"github.com/pion/webrtc/v4"
)

type trackStats struct {
	IngressPackets         uint64 `json:"ingress_packets"`
	IngressPayloadBytes    uint64 `json:"ingress_payload_bytes"`
	ForwardedPackets       uint64 `json:"forwarded_packets"`
	ForwardedPayloadBytes  uint64 `json:"forwarded_payload_bytes"`
	QueuePackets           int    `json:"queue_packets"`
	QueueBytes             int    `json:"queue_bytes"`
	QueueDrops             uint64 `json:"queue_drops"`
	ExpiredDrops           uint64 `json:"expired_drops"`
	WriteErrors            uint64 `json:"write_errors"`
	ReadErrors             uint64 `json:"read_errors"`
	PLIRequests            uint64 `json:"pli_requests"`
	IngressPayloadSHA256   string `json:"ingress_payload_sha256"`
	ForwardedPayloadSHA256 string `json:"forwarded_payload_sha256"`
}

type route struct {
	mu            sync.Mutex
	stats         trackStats
	ingressHash   hash.Hash
	forwardedHash hash.Hash
	queue         *packetQueue
	track         *webrtc.TrackLocalStaticRTP
	video         bool
}

func newRoute(video bool) (*route, error) {
	codec := webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeOpus, ClockRate: 48000, Channels: 2,
		SDPFmtpLine: "minptime=10;useinbandfec=1"}
	id := "probe-audio"
	if video {
		codec = webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000}
		id = "probe-video"
	}
	track, err := webrtc.NewTrackLocalStaticRTP(codec, id, "probe-stream")
	if err != nil {
		return nil, err
	}
	return &route{queue: newPacketQueue(), track: track, video: video,
		ingressHash: sha256.New(), forwardedHash: sha256.New()}, nil
}

func hashPayload(destination hash.Hash, payload []byte) {
	var length [8]byte
	binary.BigEndian.PutUint64(length[:], uint64(len(payload)))
	_, _ = destination.Write(length[:])
	_, _ = destination.Write(payload)
}

// Preserve the codec payload, sequence, timestamp and marker for this one-source
// proof. Never copy ingress MID/RID/TWCC/absolute-capture extensions to an egress
// transport. Pion's binding rewrites SSRC/PT and protects the packet with that
// egress connection's own DTLS-SRTP context.
func packetForEgress(packet *rtp.Packet) *rtp.Packet {
	return &rtp.Packet{Header: rtp.Header{Version: 2, Marker: packet.Marker,
		PayloadType: packet.PayloadType, SequenceNumber: packet.SequenceNumber,
		Timestamp: packet.Timestamp, SSRC: packet.SSRC},
		Payload: append([]byte(nil), packet.Payload...)}
}

func (r *route) accept(packet *rtp.Packet, now time.Time) bool {
	r.mu.Lock()
	r.stats.IngressPackets++
	r.stats.IngressPayloadBytes += uint64(len(packet.Payload))
	hashPayload(r.ingressHash, packet.Payload)
	r.mu.Unlock()
	if !r.queue.offer(packetForEgress(packet), now) {
		r.mu.Lock()
		r.stats.QueueDrops++
		r.mu.Unlock()
		return false
	}
	return true
}

func (r *route) forwarded(packet *rtp.Packet) {
	r.mu.Lock()
	r.stats.ForwardedPackets++
	r.stats.ForwardedPayloadBytes += uint64(len(packet.Payload))
	hashPayload(r.forwardedHash, packet.Payload)
	r.mu.Unlock()
}

func (r *route) snapshot() trackStats {
	r.mu.Lock()
	result := r.stats
	result.IngressPayloadSHA256 = hex.EncodeToString(r.ingressHash.Sum(nil))
	result.ForwardedPayloadSHA256 = hex.EncodeToString(r.forwardedHash.Sum(nil))
	r.mu.Unlock()
	result.QueuePackets, result.QueueBytes = r.queue.depth()
	return result
}

func (r *route) clearQueue() {
	count := r.queue.clear()
	r.mu.Lock()
	r.stats.QueueDrops += uint64(count)
	r.mu.Unlock()
}
