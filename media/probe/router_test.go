package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/pion/rtp"
)

func testPacket(sequence uint16, size int) *rtp.Packet {
	return &rtp.Packet{Header: rtp.Header{Version: 2, Marker: true, PayloadType: 120,
		SequenceNumber: sequence, Timestamp: 0xfffffffe, SSRC: 12345}, Payload: bytes.Repeat([]byte{0x42}, size)}
}

func TestPacketForEgressKeepsPayloadAndSingleSourceSequenceAcrossWrapReorderDuplicate(t *testing.T) {
	for _, sequence := range []uint16{65535, 0, 2, 1, 1} {
		input := testPacket(sequence, 48)
		input.CSRC = []uint32{101, 102}
		if err := input.SetExtension(3, []byte{1, 2, 3}); err != nil {
			t.Fatal("fixture_extension_failed")
		}
		output := packetForEgress(input)
		if output.SequenceNumber != sequence || output.Timestamp != input.Timestamp || !output.Marker {
			t.Fatal("single_source_timing_changed")
		}
		if !bytes.Equal(input.Payload, output.Payload) {
			t.Fatal("codec_payload_changed")
		}
		if output.Extension || len(output.Extensions) != 0 || len(output.CSRC) != 0 || output.Padding {
			t.Fatal("ingress_transport_fields_leaked")
		}
		input.Payload[0] = 0xff
		if output.Payload[0] != 0x42 {
			t.Fatal("queue_payload_not_owned")
		}
	}
}

func TestPacketQueueBoundsAndIdempotentCloseDrainsInOrder(t *testing.T) {
	queue := newPacketQueue()
	for i := 0; i < queuePacketLimit; i++ {
		if !queue.offer(testPacket(uint16(i), 10), time.Now()) {
			t.Fatal("early_queue_rejection")
		}
	}
	if queue.offer(testPacket(999, 10), time.Now()) {
		t.Fatal("unbounded_packet_queue")
	}
	queue.close()
	queue.close()
	if queue.offer(testPacket(999, 10), time.Now()) {
		t.Fatal("admitted_after_close")
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	for i := 0; i < queuePacketLimit; i++ {
		item, ok := queue.pop(ctx)
		if !ok || item.packet.SequenceNumber != uint16(i) {
			t.Fatal("queue_order_changed")
		}
	}
	if _, ok := queue.pop(ctx); ok {
		t.Fatal("closed_queue_not_drained")
	}
	packets, size := queue.depth()
	if packets != 0 || size != 0 {
		t.Fatal("queue_buffers_retained")
	}
}

func TestPacketQueueByteLimitAndCancellation(t *testing.T) {
	queue := newPacketQueue()
	if queue.offer(testPacket(1, queueByteLimit), time.Now()) {
		t.Fatal("unbounded_byte_queue")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, ok := queue.pop(ctx); ok {
		t.Fatal("cancelled_empty_queue_blocked")
	}
	if !queue.offer(testPacket(2, 16), time.Now()) {
		t.Fatal("valid_packet_rejected")
	}
	if queue.clear() != 1 {
		t.Fatal("clear_count_wrong")
	}
	packets, size := queue.depth()
	if packets != 0 || size != 0 {
		t.Fatal("clear_retained_buffers")
	}
}

func TestPayloadCountersAndHashesIdentifyExactForwardedPayload(t *testing.T) {
	route, err := newRoute(false)
	if err != nil {
		t.Fatal("route_creation_failed")
	}
	packet := testPacket(7, 47)
	if !route.accept(packet, time.Now()) {
		t.Fatal("admission_failed")
	}
	item, ok := route.queue.pop(context.Background())
	if !ok {
		t.Fatal("queued_packet_missing")
	}
	route.forwarded(item.packet)
	stats := route.snapshot()
	if stats.IngressPackets != 1 || stats.ForwardedPackets != 1 || stats.IngressPayloadBytes != 47 ||
		stats.ForwardedPayloadBytes != 47 || stats.IngressPayloadSHA256 != stats.ForwardedPayloadSHA256 {
		t.Fatal("payload_forwarding_evidence_wrong")
	}
	item.packet.Payload[0] ^= 1
	route.forwarded(item.packet)
	if route.snapshot().IngressPayloadSHA256 == route.snapshot().ForwardedPayloadSHA256 {
		t.Fatal("hash_did_not_detect_change")
	}
}

func TestHTTPAuthenticationJSONBoundariesAndEmptySessionCleanup(t *testing.T) {
	secret := strings.Repeat("s", 64)
	service, err := newService(secret)
	if err != nil {
		t.Fatal("service_creation_failed")
	}
	defer service.close()
	cases := []struct {
		name, method, path, body, key string
		status                        int
	}{
		{"no_secret", http.MethodGet, "/health", "", "", http.StatusUnauthorized},
		{"wrong_secret", http.MethodGet, "/stats", "", "wrong", http.StatusUnauthorized},
		{"ready", http.MethodGet, "/health", "", secret, http.StatusOK},
		{"query_rejected", http.MethodGet, "/stats?token=ignored", "", secret, http.StatusBadRequest},
		{"missing_publisher", http.MethodPost, "/subscribe", `{"sdp":"offer"}`, secret, http.StatusConflict},
		{"unknown_field", http.MethodPost, "/publish", `{"sdp":"offer","token":"ignored"}`, secret, http.StatusBadRequest},
		{"trailing_json", http.MethodPost, "/publish", `{"sdp":"offer"}{}`, secret, http.StatusBadRequest},
		{"oversized_json", http.MethodPost, "/publish", `{"sdp":"` + strings.Repeat("x", maxJSONBytes) + `"}`, secret, http.StatusBadRequest},
		{"bad_offer", http.MethodPost, "/publish", `{"sdp":"not_valid_sdp"}`, secret, http.StatusBadRequest},
		{"first_close", http.MethodDelete, "/session", "", secret, http.StatusOK},
		{"second_close", http.MethodDelete, "/session", "", secret, http.StatusOK},
	}
	for _, fixture := range cases {
		t.Run(fixture.name, func(t *testing.T) {
			request := httptest.NewRequest(fixture.method, fixture.path, strings.NewReader(fixture.body))
			request.Header.Set("X-MMR-Probe-Key", fixture.key)
			recorder := httptest.NewRecorder()
			service.ServeHTTP(recorder, request)
			if recorder.Code != fixture.status {
				t.Fatalf("unexpected_status_%d", recorder.Code)
			}
			if strings.Contains(recorder.Body.String(), secret) || strings.Contains(recorder.Body.String(), "not_valid_sdp") {
				t.Fatal("private_request_data_in_response")
			}
			if fixture.method == http.MethodDelete {
				var stats sessionStats
				if err := json.Unmarshal(recorder.Body.Bytes(), &stats); err != nil || !stats.Closed ||
					stats.ActivePeerConnections != 0 || stats.CleanupTimedOut {
					t.Fatal("cleanup_not_complete")
				}
			}
		})
	}
}

func TestConcurrentSessionCloseWaitsForSameCompletedCleanup(t *testing.T) {
	api, err := newAPI()
	if err != nil {
		t.Fatal("api_creation_failed")
	}
	session, err := newSession(api)
	if err != nil {
		t.Fatal("session_creation_failed")
	}
	results := make(chan sessionStats, 8)
	var callers sync.WaitGroup
	for i := 0; i < 8; i++ {
		callers.Add(1)
		go func() { defer callers.Done(); results <- session.close() }()
	}
	if !waitGroup(&callers, 2*time.Second) {
		t.Fatal("concurrent_close_deadline")
	}
	close(results)
	for result := range results {
		if !result.Closed || result.ActivePeerConnections != 0 || result.CleanupTimedOut || result.PeerCloseErrors != 0 {
			t.Fatal("close_returned_before_cleanup_completed")
		}
		for _, route := range result.Tracks {
			if route.QueuePackets != 0 || route.QueueBytes != 0 {
				t.Fatal("close_retained_queued_buffers")
			}
		}
	}
}
