package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"github.com/pion/interceptor"
	"github.com/pion/logging"
	"github.com/pion/rtcp"
	"github.com/pion/rtp"
	"github.com/pion/webrtc/v4"
	"io"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type quietLogger struct{}

func (quietLogger) Trace(string)                  {}
func (quietLogger) Tracef(string, ...interface{}) {}
func (quietLogger) Debug(string)                  {}
func (quietLogger) Debugf(string, ...interface{}) {}
func (quietLogger) Info(string)                   {}
func (quietLogger) Infof(string, ...interface{})  {}
func (quietLogger) Warn(string)                   {}
func (quietLogger) Warnf(string, ...interface{})  {}
func (quietLogger) Error(string)                  {}
func (quietLogger) Errorf(string, ...interface{}) {}

type quietFactory struct{}

func (quietFactory) NewLogger(string) logging.LeveledLogger { return quietLogger{} }

type controlRequest struct {
	Operation   string   `json:"operation"`
	Kind        string   `json:"kind,omitempty"`
	Instance    string   `json:"instance"`
	Room        string   `json:"room"`
	Participant string   `json:"participant"`
	Generation  int64    `json:"generation"`
	TTLMillis   int64    `json:"ttl_ms"`
	Transport   string   `json:"transport"`
	Source      string   `json:"source"`
	Publication string   `json:"publication"`
	Transports  []string `json:"transports,omitempty"`
	SDP         string   `json:"sdp"`
}
type mediaPeer struct {
	id, room, owner, source string
	kind                    string
	generation              int64
	pc                      *webrtc.PeerConnection
	answer                  string
	fingerprint             [32]byte
	publisher               bool
	active                  atomic.Bool
	ssrc                    atomic.Uint32
	lastPLI                 atomic.Int64
	keyframes               chan struct{}
	upstream                *mediaPeer
	queue                   *packetQueue
	local                   *webrtc.TrackLocalStaticRTP
	ctx                     context.Context
	cancel                  context.CancelFunc
	closed                  atomic.Bool
	closeOnce               sync.Once
}
type mediaLease struct {
	room       string
	generation int64
	expiry     time.Time
	version    uint64
}
type kindCounters struct{ ingress, forwarded, ingressBytes, forwardedBytes, drops, writeErrors, pli atomic.Uint64 }
type engine struct {
	byKind         map[string]*kindCounters
	mu             sync.Mutex
	api            *webrtc.API
	secret         [32]byte
	instance       string
	peers          map[string]*mediaPeer
	leases         map[string]mediaLease
	pending        map[string]bool
	stopVersions   map[string]uint64
	slots          chan struct{}
	maxPeers       int
	maxLeases      int
	stopped        atomic.Bool
	ingress        atomic.Uint64
	forwarded      atomic.Uint64
	ingressBytes   atomic.Uint64
	forwardedBytes atomic.Uint64
	drops          atomic.Uint64
	writeErrors    atomic.Uint64
	pli            atomic.Uint64
	leaseSequence  atomic.Uint64
	shutdown       func()
}

var opaque = regexp.MustCompile(`^[A-Za-z0-9_-]{1,96}$`)

func newEngine(secret string) (*engine, error) {
	media := &webrtc.MediaEngine{}
	codec := webrtc.RTPCodecParameters{RTPCodecCapability: webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000,
		RTCPFeedback: []webrtc.RTCPFeedback{{Type: "nack"}, {Type: "nack", Parameter: "pli"}, {Type: "ccm", Parameter: "fir"}}}, PayloadType: 96}
	if err := media.RegisterCodec(codec, webrtc.RTPCodecTypeVideo); err != nil {
		return nil, err
	}
	if err := media.RegisterCodec(webrtc.RTPCodecParameters{RTPCodecCapability: webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeOpus, ClockRate: 48000, Channels: 2, SDPFmtpLine: "minptime=10;useinbandfec=1"}, PayloadType: 111}, webrtc.RTPCodecTypeAudio); err != nil {
		return nil, err
	}
	registry := &interceptor.Registry{}
	if err := webrtc.RegisterDefaultInterceptorsWithOptions(media, registry, webrtc.WithInterceptorLoggerFactory(quietFactory{})); err != nil {
		return nil, err
	}
	settings := webrtc.SettingEngine{LoggerFactory: quietFactory{}}
	settings.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	settings.SetIncludeLoopbackCandidate(true)
	settings.SetICETimeouts(5*time.Second, 8*time.Second, time.Second)

	portMin, _ := strconv.Atoi(os.Getenv("MMR_MEDIA_PORT_MIN"))
	portMax, _ := strconv.Atoi(os.Getenv("MMR_MEDIA_PORT_MAX"))
	if portMin > 0 && portMax > portMin && portMax <= 65535 {
		_ = settings.SetEphemeralUDPPortRange(uint16(portMin), uint16(portMax))
	}

	maxParticipants := 20
	if val, err := strconv.Atoi(os.Getenv("MINIMEETING_MAX_PEERS")); err == nil && val > 0 {
		maxParticipants = val
	} else if val, err := strconv.Atoi(os.Getenv("MMR_MAX_PEERS")); err == nil && val > 0 {
		maxParticipants = val
	} else if val, err := strconv.Atoi(os.Getenv("MMR_MAX_PARTICIPANTS")); err == nil && val > 0 {
		maxParticipants = val
	}

	if maxParticipants > 50 {
		return nil, errors.New("MAX_PARTICIPANTS_LIMIT")
	}
	maxPeers := 512
	if maxParticipants <= 100 {
		computed := maxParticipants*maxParticipants*2 + maxParticipants
		if computed > maxPeers {
			maxPeers = computed
		}
	} else {
		maxPeers = maxParticipants
	}
	if envPeers, err := strconv.Atoi(os.Getenv("MMR_MEDIA_MAX_PEER_CONNECTIONS")); err == nil && envPeers > 0 {
		maxPeers = envPeers
	}

	maxLeases := 128
	if maxParticipants*4 > maxLeases {
		maxLeases = maxParticipants * 4
	}
	if envLeases, err := strconv.Atoi(os.Getenv("MMR_MEDIA_MAX_LEASES")); err == nil && envLeases > 0 {
		maxLeases = envLeases
	}

	slotsCount := 64
	if maxParticipants*2 > slotsCount {
		slotsCount = maxParticipants * 2
	}
	if envSlots, err := strconv.Atoi(os.Getenv("MMR_MEDIA_SLOTS")); err == nil && envSlots > 0 {
		slotsCount = envSlots
	}

	instanceBytes := make([]byte, 16)
	if _, err := rand.Read(instanceBytes); err != nil {
		return nil, err
	}
	return &engine{
		byKind:       map[string]*kindCounters{"AUDIO": {}, "VIDEO": {}, "SCREEN": {}},
		api:          webrtc.NewAPI(webrtc.WithMediaEngine(media), webrtc.WithInterceptorRegistry(registry), webrtc.WithSettingEngine(settings)),
		secret:       sha256.Sum256([]byte(secret)),
		instance:     hex.EncodeToString(instanceBytes),
		peers:        map[string]*mediaPeer{},
		leases:       map[string]mediaLease{},
		pending:      map[string]bool{},
		stopVersions: map[string]uint64{},
		slots:        make(chan struct{}, slotsCount),
		maxPeers:     maxPeers,
		maxLeases:    maxLeases,
	}, nil
}
func (e *engine) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	candidate := sha256.Sum256([]byte(r.Header.Get("X-MMR-Media-Key")))
	if subtle.ConstantTimeCompare(candidate[:], e.secret[:]) != 1 {
		reply(w, 401, map[string]any{"code": "CONTROL_AUTH_REQUIRED"})
		return
	}
	select {
	case e.slots <- struct{}{}:
		defer func() { <-e.slots }()
	default:
		reply(w, 429, map[string]any{"code": "MEDIA_BUSY"})
		return
	}
	if r.Method == "GET" && r.URL.Path == "/health" {
		reply(w, 200, map[string]any{"instance": e.instance, "pid": os.Getpid(), "ready": !e.stopped.Load()})
		return
	}
	if r.Method == "POST" && r.URL.Path == "/shutdown" {
		reply(w, 200, map[string]any{"ok": true})
		if e.shutdown != nil {
			go e.shutdown()
		}
		return
	}
	if r.Method != "POST" || r.URL.Path != "/v1/control" {
		reply(w, 404, map[string]any{"code": "UNKNOWN_CONTROL"})
		return
	}
	var request controlRequest
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 128*1024))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&request) != nil {
		reply(w, 400, map[string]any{"code": "CONTROL_JSON"})
		return
	}
	var extra any
	if decoder.Decode(&extra) != io.EOF {
		reply(w, 400, map[string]any{"code": "CONTROL_JSON"})
		return
	}
	if request.Instance != e.instance || e.stopped.Load() {
		reply(w, 409, map[string]any{"code": "ENGINE_CHANGED"})
		return
	}
	if !opaque.MatchString(request.Room) || !opaque.MatchString(request.Participant) || request.Generation < 1 {
		reply(w, 400, map[string]any{"code": "CONTROL_IDS"})
		return
	}
	if request.Kind == "" {
		request.Kind = "VIDEO"
	}
	if request.Kind != "VIDEO" && request.Kind != "AUDIO" && request.Kind != "SCREEN" {
		reply(w, 400, map[string]any{"code": "INVALID_MEDIA_KIND"})
		return
	}
	switch request.Operation {
	case "state":
		e.mu.Lock()
		if !e.liveLocked(request.Participant, request.Room, request.Generation) {
			e.mu.Unlock()
			reply(w, 403, map[string]any{"code": "LEASE_EXPIRED"})
			return
		}
		sources := map[string]string{}
		for _, peer := range e.peers {
			if peer.kind == request.Kind && peer.publisher && peer.active.Load() && !peer.closed.Load() && peer.room == request.Room && e.liveLocked(peer.owner, peer.room, peer.generation) {
				sources[peer.owner] = peer.id
			}
		}
		peers := len(e.peers)
		e.mu.Unlock()
		reply(w, 200, map[string]any{"sources": sources, "active_peers": peers, "ingress_packets": e.ingress.Load(), "forwarded_packets": e.forwarded.Load(), "ingress_bytes": e.ingressBytes.Load(), "forwarded_bytes": e.forwardedBytes.Load(), "queue_drops": e.drops.Load(), "write_errors": e.writeErrors.Load(), "pli_requests": e.pli.Load()})
	case "renew":
		if request.TTLMillis < 1 || request.TTLMillis > 15000 {
			reply(w, 400, map[string]any{"code": "LEASE_LIMIT"})
			return
		}
		e.mu.Lock()
		lease, exists := e.leases[request.Participant]
		if exists && (lease.room != request.Room || lease.generation != request.Generation) {
			e.mu.Unlock()
			reply(w, 409, map[string]any{"code": "STALE_MEDIA"})
			return
		}
		if !exists && len(e.leases) >= e.maxLeases {
			e.mu.Unlock()
			reply(w, 429, map[string]any{"code": "MEDIA_BUSY"})
			return
		}
		version := lease.version
		if !exists {
			version = e.leaseSequence.Add(1)
		}
		e.leases[request.Participant] = mediaLease{request.Room, request.Generation, time.Now().Add(time.Duration(request.TTLMillis) * time.Millisecond), version}
		e.mu.Unlock()
		reply(w, 200, map[string]any{"ok": true})
	case "unsubscribe":
		if request.Kind != "VIDEO" || len(request.Transports) > 6 {
			reply(w, 400, map[string]any{"code": "INVALID_REQUEST"})
			return
		}
		e.mu.Lock()
		if !e.liveLocked(request.Participant, request.Room, request.Generation) {
			e.mu.Unlock()
			reply(w, 403, map[string]any{"code": "LEASE_EXPIRED"})
			return
		}
		removed := []*mediaPeer{}
		for _, id := range request.Transports {
			peer := e.peers[id]
			if peer != nil && !peer.publisher && peer.kind == "VIDEO" && peer.owner == request.Participant && peer.room == request.Room && peer.generation == request.Generation {
				delete(e.peers, id)
				removed = append(removed, peer)
			}
		}
		e.mu.Unlock()
		for _, peer := range removed {
			peer.close()
		}
		reply(w, 200, map[string]any{"ok": true})
	case "revoke", "stop":
		e.mu.Lock()
		live := e.liveLocked(request.Participant, request.Room, request.Generation)
		lease, exists := e.leases[request.Participant]
		wrongRoom := exists && lease.room != request.Room
		e.mu.Unlock()
		if wrongRoom || (request.Operation == "stop" && !live) {
			reply(w, 403, map[string]any{"code": "LEASE_EXPIRED"})
			return
		}
		if request.Operation == "revoke" {
			e.remove(request.Participant, request.Generation, true)
		} else {
			e.stopKind(request.Participant, request.Generation, request.Kind)
		}
		reply(w, 200, map[string]any{"ok": true})
	case "publish", "subscribe":
		e.negotiate(w, request)
	default:
		reply(w, 400, map[string]any{"code": "UNKNOWN_CONTROL"})
	}
}
func reply(w http.ResponseWriter, status int, result any) {
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(result)
}
func (e *engine) liveLocked(owner, room string, generation int64) bool {
	lease, ok := e.leases[owner]
	return ok && lease.room == room && lease.generation == generation && time.Now().Before(lease.expiry)
}
func (e *engine) publisherLocked(owner string, kinds ...string) *mediaPeer {
	for _, peer := range e.peers {
		if peer.owner == owner && (len(kinds) == 0 || peer.kind == kinds[0]) && peer.publisher && !peer.closed.Load() {
			return peer
		}
	}
	return nil
}

func (e *engine) negotiate(w http.ResponseWriter, request controlRequest) {
	if !opaque.MatchString(request.Transport) || len(request.SDP) > 110000 || !strings.HasPrefix(request.SDP, "v=0") {
		reply(w, 400, map[string]any{"code": "INVALID_OFFER"})
		return
	}
	publisher := request.Operation == "publish"
	fingerprint := sha256.Sum256([]byte(request.SDP))
	e.mu.Lock()
	if !e.liveLocked(request.Participant, request.Room, request.Generation) {
		e.mu.Unlock()
		reply(w, 403, map[string]any{"code": "LEASE_EXPIRED"})
		return
	}
	if existing := e.peers[request.Transport]; existing != nil {
		valid := existing.owner == request.Participant && existing.generation == request.Generation && existing.room == request.Room && existing.source == request.Source && existing.publisher == publisher && existing.kind == request.Kind && existing.fingerprint == fingerprint
		answer := existing.answer
		e.mu.Unlock()
		if !valid {
			reply(w, 409, map[string]any{"code": "REQUEST_CONFLICT"})
			return
		}
		reply(w, 200, map[string]any{"sdp": answer, "transport": request.Transport})
		return
	}
	source := e.publisherLocked(request.Source, request.Kind)
	limit := e.maxPeers
	// Keep a global reserve for voice; existing transports are never evicted to admit new ones.
	if request.Kind != "AUDIO" && limit > 8 {
		reserve := limit / 8
		if reserve > 128 {
			reserve = 128
		}
		limit -= reserve
	}
	if len(e.peers)+len(e.pending) >= limit || e.pending[request.Transport] {
		e.mu.Unlock()
		reply(w, 429, map[string]any{"code": "MEDIA_BUSY"})
		return
	}
	if publisher && e.publisherLocked(request.Participant, request.Kind) != nil {
		e.mu.Unlock()
		reply(w, 409, map[string]any{"code": "ALREADY_PUBLISHING"})
		return
	}
	if publisher && request.Kind == "SCREEN" && e.screenLocked(request.Room) != nil {
		e.mu.Unlock()
		reply(w, 409, map[string]any{"code": "SHARE_IN_USE"})
		return
	}
	if !publisher && (source == nil || source.id != request.Publication || source.room != request.Room || source.owner == request.Participant || !source.active.Load() || !e.liveLocked(source.owner, source.room, source.generation)) {
		e.mu.Unlock()
		reply(w, 403, map[string]any{"code": "SOURCE_UNAVAILABLE"})
		return
	}
	if !publisher {
		visible := 0
		for _, peer := range e.peers {
			if !peer.publisher && peer.owner == request.Participant && peer.kind == "VIDEO" {
				visible++
			}
			if !peer.publisher && peer.owner == request.Participant && peer.source == request.Source && peer.kind == request.Kind {
				e.mu.Unlock()
				reply(w, 409, map[string]any{"code": "ALREADY_SUBSCRIBED"})
				return
			}
		}
		if request.Kind == "VIDEO" && visible >= 6 {
			e.mu.Unlock()
			reply(w, 429, map[string]any{"code": "VIDEO_VIEWPORT_LIMIT"})
			return
		}
	}
	leaseVersion := e.leases[request.Participant].version
	stopVersion := e.stopVersions[request.Participant+":"+request.Kind]
	e.pending[request.Transport] = true
	e.mu.Unlock()
	defer func() { e.mu.Lock(); delete(e.pending, request.Transport); e.mu.Unlock() }()
	pc, err := e.api.NewPeerConnection(webrtc.Configuration{BundlePolicy: webrtc.BundlePolicyMaxBundle})
	if err != nil {
		reply(w, 503, map[string]any{"code": "PEER_FAILED"})
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	peer := &mediaPeer{kind: request.Kind, id: request.Transport, room: request.Room, owner: request.Participant, source: request.Source, generation: request.Generation, pc: pc, publisher: publisher, fingerprint: fingerprint, ctx: ctx, cancel: cancel}
	peer.upstream = source
	if publisher && request.Kind != "AUDIO" {
		peer.keyframes = make(chan struct{}, 1)
		go e.sendKeyframes(peer)
	}
	installed := false
	defer func() {
		if !installed {
			peer.close()
		}
	}()
	pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		if state == webrtc.PeerConnectionStateConnected && !publisher {
			e.keyframe(source)
		}
		if state == webrtc.PeerConnectionStateFailed {
			e.removePeer(peer)
		}
	})
	if publisher {
		pc.OnTrack(func(track *webrtc.TrackRemote, receiver *webrtc.RTPReceiver) {
			if !matchesKind(request.Kind, track) || !peer.active.CompareAndSwap(false, true) {
				return
			}
			peer.ssrc.Store(uint32(track.SSRC()))
			go e.readTrack(peer, track)
			go func() {
				for {
					if _, _, err := receiver.ReadRTCP(); err != nil {
						return
					}
				}
			}()
		})
	} else {
		peer.queue = newPacketQueue()
		peer.local, err = webrtc.NewTrackLocalStaticRTP(kindCodec(request.Kind), source.owner, strings.ToLower(request.Kind))
		if err != nil {
			reply(w, 503, map[string]any{"code": "TRACK_FAILED"})
			return
		}
		sender, addErr := pc.AddTrack(peer.local)
		if addErr != nil {
			reply(w, 503, map[string]any{"code": "TRACK_FAILED"})
			return
		}
		go e.forward(peer)
		go func() {
			for {
				packets, _, readErr := sender.ReadRTCP()
				if readErr != nil {
					return
				}
				for _, packet := range packets {
					switch packet.(type) {
					case *rtcp.PictureLossIndication, *rtcp.FullIntraRequest:
						e.keyframe(source)
					}
				}
			}
		}()
	}
	if err = pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: request.SDP}); err != nil {
		reply(w, 400, map[string]any{"code": "OFFER_REJECTED"})
		return
	}
	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		reply(w, 503, map[string]any{"code": "ANSWER_FAILED"})
		return
	}
	gather := webrtc.GatheringCompletePromise(pc)
	if pc.SetLocalDescription(answer) != nil {
		reply(w, 503, map[string]any{"code": "ANSWER_FAILED"})
		return
	}
	select {
	case <-gather:
	case <-time.After(10 * time.Second):
		reply(w, 504, map[string]any{"code": "ICE_TIMEOUT"})
		return
	case <-ctx.Done():
		reply(w, 409, map[string]any{"code": "MEDIA_CLOSED"})
		return
	}
	peer.answer = pc.LocalDescription().SDP
	e.mu.Lock()
	valid := !e.stopped.Load() && e.liveLocked(peer.owner, peer.room, peer.generation) && e.leases[peer.owner].version == leaseVersion && e.stopVersions[peer.owner+":"+peer.kind] == stopVersion
	if publisher && e.publisherLocked(peer.owner, peer.kind) != nil {
		valid = false
	}
	screenConflict := publisher && peer.kind == "SCREEN" && e.screenLocked(peer.room) != nil
	if screenConflict {
		valid = false
	}
	if !publisher && (source.closed.Load() || e.publisherLocked(source.owner, source.kind) != source) {
		valid = false
	}
	if !publisher {
		for _, existing := range e.peers {
			if !existing.publisher && existing.owner == peer.owner && existing.source == peer.source && existing.kind == peer.kind {
				valid = false
			}
		}
	}
	if valid {
		e.peers[peer.id] = peer
		installed = true
	}
	e.mu.Unlock()
	if !valid {
		if screenConflict {
			reply(w, 409, map[string]any{"code": "SHARE_IN_USE"})
			return
		}
		reply(w, 409, map[string]any{"code": "MEDIA_CLOSED"})
		return
	}
	reply(w, 200, map[string]any{"sdp": peer.answer, "transport": peer.id})
}
func (e *engine) readTrack(source *mediaPeer, track *webrtc.TrackRemote) {
	defer func() { source.active.Store(false); e.removePeer(source) }()
	for {
		packet, _, err := track.ReadRTP()
		if err != nil {
			return
		}
		if source.closed.Load() {
			return
		}
		e.mu.Lock()
		valid := e.liveLocked(source.owner, source.room, source.generation)
		receivers := []*mediaPeer{}
		if valid {
			for _, peer := range e.peers {
				if !peer.publisher && peer.upstream == source && peer.room == source.room && !peer.closed.Load() && e.liveLocked(peer.owner, peer.room, peer.generation) {
					receivers = append(receivers, peer)
				}
			}
		}
		e.mu.Unlock()
		if !valid {
			return
		}
		e.ingress.Add(1)
		e.byKind[source.kind].ingress.Add(1)
		e.ingressBytes.Add(uint64(len(packet.Payload)))
		e.byKind[source.kind].ingressBytes.Add(uint64(len(packet.Payload)))
		for _, peer := range receivers {
			copy := codecPacket(packet)
			if !peer.queue.offer(copy, time.Now()) {
				e.drops.Add(1)
				e.byKind[source.kind].drops.Add(1)
				e.keyframe(source)
			}
		}
	}
}
func codecPacket(packet *rtp.Packet) *rtp.Packet {
	return &rtp.Packet{Header: rtp.Header{Version: 2, Marker: packet.Marker, PayloadType: packet.PayloadType, SequenceNumber: packet.SequenceNumber, Timestamp: packet.Timestamp, SSRC: packet.SSRC}, Payload: append([]byte(nil), packet.Payload...)}
}
func (e *engine) forward(peer *mediaPeer) {
	for {
		item, ok := peer.queue.pop(peer.ctx)
		if !ok {
			return
		}
		if time.Since(item.queuedAt) > queueMaxAge {
			e.drops.Add(1)
			e.byKind[peer.kind].drops.Add(1)
			e.keyframe(peer.upstream)
			continue
		}
		e.mu.Lock()
		valid := e.liveLocked(peer.owner, peer.room, peer.generation)
		e.mu.Unlock()
		if !valid || peer.closed.Load() {
			return
		}
		if peer.pc.ConnectionState() != webrtc.PeerConnectionStateConnected {
			e.drops.Add(1)
			e.byKind[peer.kind].drops.Add(1)
			continue
		}
		if peer.local.WriteRTP(item.packet) != nil {
			e.writeErrors.Add(1)
			e.byKind[peer.kind].writeErrors.Add(1)
		} else {
			e.forwarded.Add(1)
			e.byKind[peer.kind].forwarded.Add(1)
			e.forwardedBytes.Add(uint64(len(item.packet.Payload)))
			e.byKind[peer.kind].forwardedBytes.Add(uint64(len(item.packet.Payload)))
		}
	}
}
func (e *engine) keyframe(source *mediaPeer) {
	if source == nil || source.kind == "AUDIO" || source.closed.Load() || source.ssrc.Load() == 0 {
		return
	}
	now := time.Now().UnixNano()
	before := source.lastPLI.Load()
	if now-before < int64(250*time.Millisecond) || !source.lastPLI.CompareAndSwap(before, now) {
		return
	}
	// A stalled RTCP transport must never block RTP ingress or another subscriber.
	select {
	case source.keyframes <- struct{}{}:
	default:
	}
}
func (e *engine) sendKeyframes(source *mediaPeer) {
	for {
		select {
		case <-source.ctx.Done():
			return
		case <-source.keyframes:
			if source.closed.Load() {
				return
			}
			e.pli.Add(1)
			e.byKind[source.kind].pli.Add(1)
			_ = source.pc.WriteRTCP([]rtcp.Packet{&rtcp.PictureLossIndication{MediaSSRC: source.ssrc.Load()}})
		}
	}
}
func (peer *mediaPeer) close() {
	peer.closeOnce.Do(func() {
		peer.closed.Store(true)
		peer.cancel()
		if peer.queue != nil {
			peer.queue.close()
			peer.queue.clear()
		}
		_ = peer.pc.Close()
	})
}
func (e *engine) removePeer(peer *mediaPeer) {
	e.mu.Lock()
	if e.peers[peer.id] != peer {
		e.mu.Unlock()
		peer.close()
		return
	}
	delete(e.peers, peer.id)
	removed := []*mediaPeer{peer}
	if peer.publisher {
		for id, other := range e.peers {
			if other.upstream == peer {
				delete(e.peers, id)
				removed = append(removed, other)
			}
		}
	}
	e.mu.Unlock()
	for _, item := range removed {
		item.close()
	}
}
func (e *engine) remove(owner string, generation int64, revoke bool, expiredAt ...time.Time) {
	e.mu.Lock()
	removed := []*mediaPeer{}
	lease, ok := e.leases[owner]
	if len(expiredAt) > 0 && (!ok || !lease.expiry.Equal(expiredAt[0]) || time.Now().Before(lease.expiry)) {
		e.mu.Unlock()
		return
	}
	if ok && lease.generation != generation {
		e.mu.Unlock()
		return
	}
	if revoke {
		delete(e.leases, owner)
		for _, kind := range []string{"AUDIO", "VIDEO", "SCREEN"} {
			delete(e.stopVersions, owner+":"+kind)
		}
	} else if ok {
		lease.version = e.leaseSequence.Add(1)
		e.leases[owner] = lease
	}
	for id, peer := range e.peers {
		if (peer.owner == owner && (revoke || peer.publisher)) || peer.source == owner {
			delete(e.peers, id)
			removed = append(removed, peer)
		}
	}
	e.mu.Unlock()
	for _, peer := range removed {
		peer.close()
	}
}
func (e *engine) expire() {
	e.mu.Lock()
	expired := map[string]mediaLease{}
	for owner, lease := range e.leases {
		if time.Now().After(lease.expiry) {
			expired[owner] = lease
		}
	}
	e.mu.Unlock()
	for owner, lease := range expired {
		e.remove(owner, lease.generation, true, lease.expiry)
	}
}
func (e *engine) close() {
	if !e.stopped.CompareAndSwap(false, true) {
		return
	}
	e.mu.Lock()
	peers := e.peers
	e.peers = map[string]*mediaPeer{}
	e.leases = map[string]mediaLease{}
	e.mu.Unlock()
	for _, peer := range peers {
		peer.close()
	}
}

func kindCodec(kind string) webrtc.RTPCodecCapability {
	if kind == "AUDIO" {
		return webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeOpus, ClockRate: 48000, Channels: 2, SDPFmtpLine: "minptime=10;useinbandfec=1"}
	}
	return webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000}
}
func matchesKind(kind string, track *webrtc.TrackRemote) bool {
	c := kindCodec(kind)
	return strings.EqualFold(track.Codec().MimeType, c.MimeType)
}
func (e *engine) screenLocked(room string) *mediaPeer {
	for _, p := range e.peers {
		if p.publisher && p.kind == "SCREEN" && p.room == room && !p.closed.Load() {
			return p
		}
	}
	return nil
}
func (e *engine) stopKind(owner string, generation int64, kind string) {
	e.mu.Lock()
	lease, ok := e.leases[owner]
	if !ok || lease.generation != generation {
		e.mu.Unlock()
		return
	}
	e.stopVersions[owner+":"+kind]++
	removed := []*mediaPeer{}
	for id, p := range e.peers {
		if p.kind == kind && ((p.publisher && p.owner == owner) || p.source == owner) {
			delete(e.peers, id)
			removed = append(removed, p)
		}
	}
	e.mu.Unlock()
	for _, p := range removed {
		p.close()
	}
}
