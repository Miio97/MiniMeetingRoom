package main

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/interceptor"
	"github.com/pion/logging"
	"github.com/pion/rtcp"
	"github.com/pion/webrtc/v4"
)

const maxJSONBytes = 128 * 1024

var errPeerConflict = errors.New("peer_conflict")

// Disable library messages, including those enabled by inherited Pion logging
// environment variables. No SDP, ICE address, secret or per-packet log is emitted.
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

type quietLoggerFactory struct{}

func (quietLoggerFactory) NewLogger(string) logging.LeveledLogger { return quietLogger{} }

func newAPI() (*webrtc.API, error) {
	media := &webrtc.MediaEngine{}
	if err := media.RegisterCodec(webrtc.RTPCodecParameters{RTPCodecCapability: webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeOpus, ClockRate: 48000, Channels: 2,
		SDPFmtpLine: "minptime=10;useinbandfec=1"}, PayloadType: 111}, webrtc.RTPCodecTypeAudio); err != nil {
		return nil, err
	}
	if err := media.RegisterCodec(webrtc.RTPCodecParameters{RTPCodecCapability: webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000,
		RTCPFeedback: []webrtc.RTCPFeedback{{Type: "nack"}, {Type: "nack", Parameter: "pli"},
			{Type: "ccm", Parameter: "fir"}}}, PayloadType: 96}, webrtc.RTPCodecTypeVideo); err != nil {
		return nil, err
	}
	registry := &interceptor.Registry{}
	if err := webrtc.RegisterDefaultInterceptorsWithOptions(media, registry,
		webrtc.WithInterceptorLoggerFactory(quietLoggerFactory{})); err != nil {
		return nil, err
	}
	settings := webrtc.SettingEngine{LoggerFactory: quietLoggerFactory{}}
	settings.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	settings.SetIncludeLoopbackCandidate(true)
	// libwebrtc desktop excludes loopback by default. Advertise the machine's
	// IPv4 host interfaces as well so two native clients can reach this local
	// process. The authenticated HTTP control listener remains loopback-only.
	settings.SetICETimeouts(5*time.Second, 8*time.Second, time.Second)
	return webrtc.NewAPI(webrtc.WithMediaEngine(media), webrtc.WithInterceptorRegistry(registry),
		webrtc.WithSettingEngine(settings)), nil
}

type sessionStats struct {
	ActivePeerConnections     int64                 `json:"active_peer_connections"`
	PublisherConnected        bool                  `json:"publisher_connected"`
	SubscriberConnected       bool                  `json:"subscriber_connected"`
	PublisherConnectionState  int32                 `json:"publisher_connection_state"`
	SubscriberConnectionState int32                 `json:"subscriber_connection_state"`
	PublisherICECandidates    int64                 `json:"publisher_ice_candidates"`
	SubscriberICECandidates   int64                 `json:"subscriber_ice_candidates"`
	CleanupTimedOut           bool                  `json:"cleanup_timed_out"`
	PeerCloseErrors           int64                 `json:"peer_close_errors"`
	Closed                    bool                  `json:"closed"`
	Tracks                    map[string]trackStats `json:"tracks"`
}

type session struct {
	api                       *webrtc.API
	ctx                       context.Context
	cancel                    context.CancelFunc
	mu                        sync.Mutex
	publisher                 *webrtc.PeerConnection
	subscriber                *webrtc.PeerConnection
	workers                   sync.WaitGroup
	routers                   sync.WaitGroup
	closed                    atomic.Bool
	cleanupTimedOut           atomic.Bool
	closeComplete             atomic.Bool
	peerCloseErrors           atomic.Int64
	closeDone                 chan struct{}
	activePeers               atomic.Int64
	publisherConnected        atomic.Bool
	subscriberConnected       atomic.Bool
	publisherConnectionState  atomic.Int32
	subscriberConnectionState atomic.Int32
	publisherICECandidates    atomic.Int64
	subscriberICECandidates   atomic.Int64
	audioInstalled            atomic.Bool
	videoInstalled            atomic.Bool
	videoSSRC                 atomic.Uint32
	ready                     chan struct{}
	readyOnce                 sync.Once
	pli                       chan struct{}
	routes                    map[string]*route
}

func newSession(api *webrtc.API) (*session, error) {
	audio, err := newRoute(false)
	if err != nil {
		return nil, err
	}
	video, err := newRoute(true)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &session{api: api, ctx: ctx, cancel: cancel, ready: make(chan struct{}), closeDone: make(chan struct{}),
		pli: make(chan struct{}, 1), routes: map[string]*route{"audio": audio, "video": video}}
	for _, route := range s.routes {
		s.routers.Add(1)
		s.startTask(func() { defer s.routers.Done(); s.forward(route) })
	}
	s.startTask(s.keyFrameWorker)
	return s, nil
}

// The same mutex prevents WaitGroup.Add after shutdown has begun.
func (s *session) startTask(task func()) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed.Load() {
		return false
	}
	s.workers.Add(1)
	go func() { defer s.workers.Done(); task() }()
	return true
}

func (s *session) addPeer(publish bool) (*webrtc.PeerConnection, error) {
	pc, err := s.api.NewPeerConnection(webrtc.Configuration{BundlePolicy: webrtc.BundlePolicyMaxBundle})
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	if s.closed.Load() || (publish && s.publisher != nil) || (!publish && s.subscriber != nil) {
		s.mu.Unlock()
		_ = pc.Close()
		return nil, errPeerConflict
	}
	if publish {
		s.publisher = pc
	} else {
		s.subscriber = pc
	}
	s.activePeers.Add(1)
	s.mu.Unlock()
	pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		connected := state == webrtc.PeerConnectionStateConnected && !s.closed.Load()
		if publish {
			s.publisherConnectionState.Store(int32(state))
			s.publisherConnected.Store(connected)
		} else {
			s.subscriberConnectionState.Store(int32(state))
			s.subscriberConnected.Store(connected)
			if connected {
				s.readyOnce.Do(func() { close(s.ready) })
				s.requestKeyFrame()
			}
		}
	})
	pc.OnICECandidate(func(candidate *webrtc.ICECandidate) {
		if candidate == nil {
			return
		}
		if publish {
			s.publisherICECandidates.Add(1)
		} else {
			s.subscriberICECandidates.Add(1)
		}
	})
	if publish {
		pc.OnTrack(func(track *webrtc.TrackRemote, receiver *webrtc.RTPReceiver) {
			if s.closed.Load() {
				return
			}
			kind := "audio"
			installed := &s.audioInstalled
			expected := webrtc.MimeTypeOpus
			if track.Kind() == webrtc.RTPCodecTypeVideo {
				kind, installed, expected = "video", &s.videoInstalled, webrtc.MimeTypeVP8
			}
			if !strings.EqualFold(track.Codec().MimeType, expected) || !installed.CompareAndSwap(false, true) {
				return
			}
			if kind == "video" {
				s.videoSSRC.Store(uint32(track.SSRC()))
				s.requestKeyFrame()
			}
			s.startTask(func() { s.readTrack(track, s.routes[kind]) })
			s.startTask(func() {
				for {
					if _, _, err := receiver.ReadRTCP(); err != nil {
						return
					}
				}
			})
		})
	} else {
		for _, kind := range []string{"audio", "video"} {
			sender, addErr := pc.AddTrack(s.routes[kind].track)
			if addErr != nil {
				return nil, addErr
			}
			s.startTask(func() { s.readFeedback(sender) })
		}
	}
	return pc, nil
}

func (s *session) readTrack(track *webrtc.TrackRemote, route *route) {
	for {
		packet, _, err := track.ReadRTP()
		if err != nil {
			if !s.closed.Load() && !errors.Is(err, io.EOF) {
				route.mu.Lock()
				route.stats.ReadErrors++
				route.mu.Unlock()
			}
			return
		}
		if s.closed.Load() {
			return
		}
		if !route.accept(packet, time.Now()) && route.video {
			s.requestKeyFrame()
		}
	}
}

func (s *session) forward(route *route) {
	select {
	case <-s.ctx.Done():
		return
	case <-s.ready:
	}
	for {
		item, ok := route.queue.pop(s.ctx)
		if !ok {
			return
		}
		if time.Since(item.queuedAt) > queueMaxAge {
			route.mu.Lock()
			route.stats.ExpiredDrops++
			route.mu.Unlock()
			if route.video {
				s.requestKeyFrame()
			}
			continue
		}
		// An unbound TrackLocalStaticRTP returns nil without writing. Do not
		// count that as a forwarded packet when the subscriber has disconnected.
		if !s.subscriberConnected.Load() {
			route.mu.Lock()
			route.stats.QueueDrops++
			route.mu.Unlock()
			continue
		}
		if err := route.track.WriteRTP(item.packet); err != nil {
			route.mu.Lock()
			route.stats.WriteErrors++
			route.mu.Unlock()
			if route.video {
				s.requestKeyFrame()
			}
			continue
		}
		route.forwarded(item.packet)
	}
}

func (s *session) readFeedback(sender *webrtc.RTPSender) {
	for {
		packets, _, err := sender.ReadRTCP()
		if err != nil {
			return
		}
		for _, packet := range packets {
			switch packet.(type) {
			case *rtcp.PictureLossIndication, *rtcp.FullIntraRequest:
				s.requestKeyFrame()
			}
		}
	}
}

func (s *session) requestKeyFrame() {
	if s.closed.Load() {
		return
	}
	select {
	case s.pli <- struct{}{}:
	default:
	}
}

func (s *session) keyFrameWorker() {
	var last time.Time
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-s.pli:
		}
		if time.Since(last) < 500*time.Millisecond {
			continue
		}
		ssrc := s.videoSSRC.Load()
		if ssrc == 0 || !s.subscriberConnected.Load() {
			continue
		}
		s.mu.Lock()
		pc := s.publisher
		s.mu.Unlock()
		if pc == nil {
			continue
		}
		if err := pc.WriteRTCP([]rtcp.Packet{&rtcp.PictureLossIndication{MediaSSRC: ssrc}}); err == nil {
			last = time.Now()
			route := s.routes["video"]
			route.mu.Lock()
			route.stats.PLIRequests++
			route.mu.Unlock()
		}
	}
}

func (s *session) snapshot() sessionStats {
	result := sessionStats{ActivePeerConnections: s.activePeers.Load(),
		PublisherConnected: s.publisherConnected.Load(), SubscriberConnected: s.subscriberConnected.Load(),
		PublisherConnectionState: s.publisherConnectionState.Load(), SubscriberConnectionState: s.subscriberConnectionState.Load(),
		PublisherICECandidates: s.publisherICECandidates.Load(), SubscriberICECandidates: s.subscriberICECandidates.Load(),
		Closed: s.closeComplete.Load(), CleanupTimedOut: s.cleanupTimedOut.Load(),
		PeerCloseErrors: s.peerCloseErrors.Load(), Tracks: map[string]trackStats{}}
	for kind, route := range s.routes {
		result.Tracks[kind] = route.snapshot()
	}
	return result
}

func waitGroup(group *sync.WaitGroup, deadline time.Duration) bool {
	done := make(chan struct{})
	go func() { group.Wait(); close(done) }()
	select {
	case <-done:
		return true
	case <-time.After(deadline):
		return false
	}
}

func (s *session) close() sessionStats {
	s.mu.Lock()
	if !s.closed.CompareAndSwap(false, true) {
		s.mu.Unlock()
		select {
		case <-s.closeDone:
		case <-time.After(6 * time.Second):
			s.cleanupTimedOut.Store(true)
		}
		return s.snapshot()
	}
	defer close(s.closeDone)
	publisher, subscriber := s.publisher, s.subscriber
	s.mu.Unlock()
	// Stop ingress first. Retain the egress SRTP transport long enough to drain
	// packets already admitted, then stop all workers before reporting final stats.
	if publisher != nil {
		if err := publisher.Close(); err != nil {
			s.peerCloseErrors.Add(1)
		}
		if publisher.ConnectionState() == webrtc.PeerConnectionStateClosed {
			s.activePeers.Add(-1)
		}
	}
	s.publisherConnected.Store(false)
	for _, route := range s.routes {
		route.queue.close()
	}
	if !s.subscriberConnected.Load() {
		s.cancel()
	}
	if !waitGroup(&s.routers, 2*time.Second) {
		s.cleanupTimedOut.Store(true)
	}
	if subscriber != nil {
		if err := subscriber.Close(); err != nil {
			s.peerCloseErrors.Add(1)
		}
		if subscriber.ConnectionState() == webrtc.PeerConnectionStateClosed {
			s.activePeers.Add(-1)
		}
	}
	s.subscriberConnected.Store(false)
	s.cancel()
	if !waitGroup(&s.workers, 3*time.Second) {
		s.cleanupTimedOut.Store(true)
	}
	for _, route := range s.routes {
		route.clearQueue()
	}
	s.closeComplete.Store(!s.cleanupTimedOut.Load() && s.activePeers.Load() == 0 && s.peerCloseErrors.Load() == 0)
	return s.snapshot()
}

type probeService struct {
	api      *webrtc.API
	secret   [32]byte
	mu       sync.Mutex
	control  chan struct{}
	current  *session
	shutdown func()
}

func newService(secret string) (*probeService, error) {
	api, err := newAPI()
	if err != nil {
		return nil, err
	}
	return &probeService{api: api, secret: sha256.Sum256([]byte(secret)), control: make(chan struct{}, 1)}, nil
}

func writeJSON(w http.ResponseWriter, status int, value interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeCode(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, map[string]string{"code": code})
}

func (service *probeService) ServeHTTP(w http.ResponseWriter, request *http.Request) {
	provided := sha256.Sum256([]byte(request.Header.Get("X-MMR-Probe-Key")))
	if subtle.ConstantTimeCompare(service.secret[:], provided[:]) != 1 {
		writeCode(w, http.StatusUnauthorized, "UNAUTHORIZED")
		return
	}
	if request.URL.RawQuery != "" {
		writeCode(w, http.StatusBadRequest, "NO_QUERY")
		return
	}
	if request.URL.Path == "/health" && request.Method == http.MethodGet {
		writeJSON(w, http.StatusOK, map[string]bool{"ready": true})
		return
	}
	service.mu.Lock()
	current := service.current
	service.mu.Unlock()
	if request.URL.Path == "/stats" && request.Method == http.MethodGet {
		if current == nil {
			writeJSON(w, http.StatusOK, sessionStats{Closed: true, Tracks: map[string]trackStats{}})
		} else {
			writeJSON(w, http.StatusOK, current.snapshot())
		}
		return
	}
	validControl := (request.Method == http.MethodPost && (request.URL.Path == "/publish" || request.URL.Path == "/subscribe")) ||
		(request.Method == http.MethodDelete && request.URL.Path == "/session") ||
		(request.Method == http.MethodPost && request.URL.Path == "/shutdown")
	if !validControl {
		writeCode(w, http.StatusMethodNotAllowed, "METHOD_OR_PATH")
		return
	}
	select {
	case service.control <- struct{}{}:
		defer func() { <-service.control }()
	default:
		writeCode(w, http.StatusConflict, "BUSY")
		return
	}
	service.mu.Lock()
	current = service.current
	service.mu.Unlock()
	if request.Method == http.MethodDelete || request.URL.Path == "/shutdown" {
		if current == nil {
			writeJSON(w, http.StatusOK, sessionStats{Closed: true, Tracks: map[string]trackStats{}})
		} else {
			writeJSON(w, http.StatusOK, current.close())
		}
		if request.URL.Path == "/shutdown" && service.shutdown != nil {
			go service.shutdown()
		}
		return
	}
	request.Body = http.MaxBytesReader(w, request.Body, maxJSONBytes)
	defer request.Body.Close()
	decoder := json.NewDecoder(request.Body)
	decoder.DisallowUnknownFields()
	var offer struct {
		SDP string `json:"sdp"`
	}
	if err := decoder.Decode(&offer); err != nil || offer.SDP == "" {
		writeCode(w, http.StatusBadRequest, "INVALID_OFFER_JSON")
		return
	}
	var extra interface{}
	if err := decoder.Decode(&extra); err != io.EOF {
		writeCode(w, http.StatusBadRequest, "INVALID_OFFER_JSON")
		return
	}
	publish := request.URL.Path == "/publish"
	if publish {
		if current != nil && !current.closed.Load() {
			writeCode(w, http.StatusConflict, "SESSION_EXISTS")
			return
		}
		var err error
		current, err = newSession(service.api)
		if err != nil {
			writeCode(w, http.StatusInternalServerError, "SESSION_CREATE_FAILED")
			return
		}
		service.mu.Lock()
		service.current = current
		service.mu.Unlock()
	} else if current == nil || current.closed.Load() {
		writeCode(w, http.StatusConflict, "PUBLISH_REQUIRED")
		return
	}
	pc, err := current.addPeer(publish)
	if err != nil {
		if !errors.Is(err, errPeerConflict) {
			current.close()
		}
		writeCode(w, http.StatusConflict, "PEER_CREATE_FAILED")
		return
	}
	ctx, cancel := context.WithTimeout(request.Context(), 8*time.Second)
	defer cancel()
	answer, err := answerOffer(ctx, pc, offer.SDP)
	if err != nil {
		current.close()
		writeCode(w, http.StatusBadRequest, "NEGOTIATION_FAILED")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"sdp": answer})
}

func answerOffer(ctx context.Context, pc *webrtc.PeerConnection, sdp string) (string, error) {
	if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: sdp}); err != nil {
		return "", err
	}
	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		return "", err
	}
	gathered := webrtc.GatheringCompletePromise(pc)
	if err = pc.SetLocalDescription(answer); err != nil {
		return "", err
	}
	select {
	case <-ctx.Done():
		return "", ctx.Err()
	case <-gathered:
	}
	if pc.LocalDescription() == nil {
		return "", errors.New("no_local_description")
	}
	return pc.LocalDescription().SDP, nil
}

func (service *probeService) close() {
	service.mu.Lock()
	current := service.current
	service.mu.Unlock()
	if current != nil {
		current.close()
	}
}
