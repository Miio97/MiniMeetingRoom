package client.media;

import com.google.gson.JsonObject;
import common.meeting.RoomSnapshot;
import common.signaling.SignalException;
import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.video.VideoDevice;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.property.*;

/** Bounded media owner and coalesced poll; camera metadata/preferences never queue ahead of receive work. */
public final class VideoCallController implements AutoCloseable {
    @FunctionalInterface public interface Signaling { CompletableFuture<JsonObject> request(String type, JsonObject body, String requestId); }
    private record Subscription(String publication, NativeVideoSession.Peer peer) { }
    private final Signaling signaling;
    private final AudioCallController audio;
    private final ScreenCallController screen;
    public ScreenCallController screen() { return screen; }
    public AudioCallController audio() { return audio; }
    private final Supplier<RoomSnapshot> room;
    private final Supplier<String> self;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
            Thread.ofPlatform().daemon().name("video-client-owner").factory());
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("video-poll").factory());
    private final AtomicBoolean closed = new AtomicBoolean(), polling = new AtomicBoolean(), statusScheduled = new AtomicBoolean();
    private final ThreadPoolExecutor preferences = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
            Thread.ofPlatform().daemon().name("camera-preferences").factory());
    private final ThreadPoolExecutor discovery = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2),
            Thread.ofPlatform().daemon().name("camera-discovery").factory());
    private final Set<CompletableFuture<?>> jobs = ConcurrentHashMap.newKeySet();
    private final Map<String, VideoFrameMailbox> frames = new ConcurrentHashMap<>();
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService statsSampler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("video-stats-sampler").factory());
    private final NetworkStatsCalculator statsCalculator = new NetworkStatsCalculator();
    private final RtcQualityMonitor videoQualityMonitor = new RtcQualityMonitor();
    private int qualityCursor;
    private final AdaptiveVideoPolicy adaptivePolicy = new AdaptiveVideoPolicy();
    private final AtomicBoolean adapting = new AtomicBoolean();
    private volatile AdaptiveVideoPolicy.Profile appliedQuality = AdaptiveVideoPolicy.Profile.NORMAL;
    private final ReadOnlyObjectWrapper<AdaptiveVideoPolicy.Profile> quality = new ReadOnlyObjectWrapper<>(AdaptiveVideoPolicy.Profile.NORMAL);
    public ReadOnlyObjectProperty<AdaptiveVideoPolicy.Profile> qualityProperty() { return quality.getReadOnlyProperty(); }
    private final ReadOnlyObjectWrapper<NetworkStatsCalculator.CalculatedSample> latestStats = new ReadOnlyObjectWrapper<>();
    public ReadOnlyObjectProperty<NetworkStatsCalculator.CalculatedSample> statsProperty() { return latestStats.getReadOnlyProperty(); }
    public NetworkStatsCalculator.CalculatedSample latestStats() { return latestStats.get(); }
    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper("Camera đang tắt.");
    private final ReadOnlyBooleanWrapper publishing = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper cameraRequested = new ReadOnlyBooleanWrapper(), cameraPending = new ReadOnlyBooleanWrapper();
    private final CameraSwitch cameraSwitch = new CameraSwitch(this::updateCameraUi);
    private final Map<String, List<dev.onvoid.webrtc.media.video.VideoCaptureCapability>> formatCache = new ConcurrentHashMap<>();
    private volatile List<VideoDevice> knownCameras = List.of();
    private volatile boolean inMeeting;
    private volatile boolean controlConnected = true;
    /** Transport recovery preserves the controller borrowed by the view, but never reopens capture. */
    public void controlConnected(boolean connected) {
        controlConnected = connected; audio.controlConnected(connected); screen.controlConnected(connected);
        if (!connected) submit(() -> {
            cameraSwitch.resetOff(); stopLocalCamera();
            subscriptions.values().forEach(s -> s.peer().close()); subscriptions.clear();
            adaptivePolicy.reset(); appliedQuality = AdaptiveVideoPolicy.Profile.NORMAL;
            Platform.runLater(() -> { if (!closed.get()) quality.set(AdaptiveVideoPolicy.Profile.NORMAL); }); return null;
        });
    }
    private final AtomicReference<String> latestStatus = new AtomicReference<>();
    private final CompletableFuture<Void> closeComplete = new CompletableFuture<>();
    public CompletableFuture<Void> closeComplete() { return closeComplete; }
    private volatile boolean activeCamera;
    private NativeVideoSession nativeSession;
    private volatile NativeVideoSession.Peer publisher;
    private NativeVideoSession.Preview preview;
    private VideoInput input;
    private boolean inputStarted;
    private final CameraConsentStore cameraConsent;
    private final Supplier<List<VideoDevice>> deviceProvider;
    public VideoCallController(Signaling signaling, Supplier<RoomSnapshot> room, Supplier<String> self) {
        this(signaling, room, self, CameraConsentStore.local(), MediaDevices::getVideoCaptureDevices);
    }
    public VideoCallController(Signaling signaling, Supplier<RoomSnapshot> room, Supplier<String> self,
            CameraConsentStore cameraConsent, Supplier<List<VideoDevice>> deviceProvider) {
        this(signaling,room,self,cameraConsent,deviceProvider,false,MicrophoneConsentStore.local());
    }
    public VideoCallController(Signaling signaling, Supplier<RoomSnapshot> room, Supplier<String> self,
            CameraConsentStore cameraConsent, Supplier<List<VideoDevice>> deviceProvider, boolean headless, MicrophoneConsentStore microphoneConsent) {
        this.signaling = signaling; this.room = room; this.self = self;
        this.audio = new AudioCallController(signaling,room,self,headless,microphoneConsent);
        this.screen = new ScreenCallController(signaling,room,self,headless);
        this.cameraConsent = Objects.requireNonNull(cameraConsent); this.deviceProvider = Objects.requireNonNull(deviceProvider);
        timer.scheduleWithFixedDelay(() -> {
            if (closed.get() || room.get() == null || !polling.compareAndSet(false, true)) return;
            submit(() -> { synchronize(); return null; }).whenComplete((value, failure) -> polling.set(false));
        }, 1, 2, TimeUnit.SECONDS);
        statsSampler.scheduleWithFixedDelay(this::sampleNetworkStats, 1, 1, TimeUnit.SECONDS);
    }
    public ReadOnlyStringProperty statusProperty() { return status.getReadOnlyProperty(); }
    public ReadOnlyBooleanProperty publishingProperty() { return publishing.getReadOnlyProperty(); }
    public ReadOnlyBooleanProperty cameraRequestedProperty() { return cameraRequested.getReadOnlyProperty(); }
    public ReadOnlyBooleanProperty cameraPendingProperty() { return cameraPending.getReadOnlyProperty(); }
    private void updateCameraUi() {
        Runnable update = () -> {
            publishing.set(!closed.get() && activeCamera);
            cameraRequested.set(!closed.get() && (cameraSwitch.pending() ? cameraSwitch.wanted() : activeCamera));
            cameraPending.set(!closed.get() && cameraSwitch.pending());
        };
        if (Platform.isFxApplicationThread()) update.run(); else Platform.runLater(update);
    }
    public VideoFrameMailbox frames(String participant) { return frames.computeIfAbsent(participant, ignored -> new VideoFrameMailbox()); }
    public CompletableFuture<CameraConsentStore.Choice> cameraChoice() { return submitTo(preferences, cameraConsent::read).orTimeout(3, TimeUnit.SECONDS); }
    public CompletableFuture<Void> cameraAccess(boolean allowed) { return this.<Void>submitTo(preferences, () -> { cameraConsent.access(allowed); return null; }).orTimeout(3, TimeUnit.SECONDS); }
    public CompletableFuture<Void> preferredCamera(VideoDevice device) { return this.<Void>submitTo(preferences, () -> { cameraConsent.device(device.getDescriptor()); return null; }).orTimeout(3, TimeUnit.SECONDS); }
    public CompletableFuture<List<VideoDevice>> cameras() {
        return submitTo(discovery, () -> { requireCameraAccess(); knownCameras = CameraSelection.physicalDevices(deviceProvider.get()); return knownCameras; }).orTimeout(6, TimeUnit.SECONDS);
    }
    private void requireCameraAccess() throws java.io.IOException {
        if (cameraConsent.read().access() != CameraConsentStore.Access.ALLOWED) throw new SignalException("CAMERA_PERMISSION_REQUIRED");
    }
    private VideoInput cameraInput(VideoDevice device, java.util.List<dev.onvoid.webrtc.media.video.VideoCaptureCapability> capabilities) {
        try {
            requireCameraAccess();
            return new CameraVideoInput(Objects.requireNonNull(device), capabilities);
        } catch (Exception failure) { throw new CompletionException(failure); }
    }
    public CompletableFuture<Void> toggleSelectedCamera() {
        return cameraSwitch.toggle(activeCamera, () -> cameraChoice().thenCombine(knownCameras.isEmpty() ? cameras() : CompletableFuture.completedFuture(knownCameras),
                (choice, devices) -> CameraSelection.choose(devices, choice.preferredDevice())).thenCompose(device -> startRequestedCamera(device, false)), this::stopCamera);
    }
    public CompletableFuture<Void> toggleCamera(VideoDevice device, boolean previewOnly) {
        return cameraSwitch.toggle(activeCamera, () -> startRequestedCamera(device, previewOnly), this::stopCamera);
    }
    CompletableFuture<Void> toggleInput(Supplier<VideoInput> source, boolean previewOnly) {
        return cameraSwitch.toggle(activeCamera, () -> previewOnly && !inMeeting ? previewInput(source, cameraSwitch::wanted)
                : startInput(source, cameraSwitch::wanted), this::stopCamera);
    }
    private CompletableFuture<Void> startRequestedCamera(VideoDevice device, boolean previewOnly) {
        if (!cameraSwitch.wanted()) return CompletableFuture.failedFuture(new CancellationException());
        if (device == null) return CompletableFuture.failedFuture(new IllegalStateException("CAMERA_NOT_FOUND"));
        return cameraFormats(device).thenCompose(formats -> {
            if (!cameraSwitch.wanted()) return CompletableFuture.failedFuture(new CancellationException());
            return previewOnly && !inMeeting ? previewInput(() -> cameraInput(device, formats), cameraSwitch::wanted)
                    : startInput(() -> cameraInput(device, formats), cameraSwitch::wanted);
        });
    }
    private CompletableFuture<java.util.List<dev.onvoid.webrtc.media.video.VideoCaptureCapability>> cameraFormats(VideoDevice device) {
        return submitTo(discovery, () -> {
            requireCameraAccess();
            var cached = formatCache.get(device.getDescriptor()); if (cached != null) return cached;
            var formats = CameraFormats.read(device); formatCache.put(device.getDescriptor(), formats); return formats;
        }).orTimeout(10, TimeUnit.SECONDS)
                .whenComplete((value, error) -> { if (error != null) mediaFailure("camera_formats", error); });
    }
    public CompletableFuture<Void> startCamera(VideoDevice device) { return cameraFormats(device).thenCompose(formats -> startInput(() -> cameraInput(device, formats))); }
    public CompletableFuture<Void> previewCamera(VideoDevice device) { return cameraFormats(device).thenCompose(formats -> previewInput(() -> cameraInput(device, formats))); }
    public CompletableFuture<Void> previewInput(Supplier<VideoInput> source) {
        return previewInput(source, () -> true);
    }
    private CompletableFuture<Void> previewInput(Supplier<VideoInput> source, java.util.function.BooleanSupplier wanted) {
        return submit(() -> {
            requireWanted(wanted);
            if (room.get() == null || self.get() == null) throw new SignalException("NOT_A_MEMBER");
            if (publisher != null) throw new SignalException("ALREADY_PUBLISHING");
            stopLocalCamera();
            try {
                if (nativeSession == null) nativeSession = new NativeVideoSession();
                input = source.get(); preview = nativeSession.preview(input, frames(self.get()));
                requireWanted(wanted); long before = frames(self.get()).receivedFrames(); input.start(); inputStarted = true;
                awaitCameraFrame(before, wanted); activeCamera = true;
                show("Camera xem trước • Chỉ bạn nhìn thấy. Video được gửi khi bạn vào phòng họp.");
            } catch (Exception | LinkageError failure) { stopLocalCamera(); if (!(failure instanceof CancellationException)) { mediaFailure("preview", failure); show(CameraErrors.message(failure)); } throw failure; }
            return null;
        });
    }
    public CompletableFuture<Void> publishPreparedCamera() {
        inMeeting = true; audio.enter(); screen.enter();
        return submit(() -> { if (preview != null && input != null && (!cameraSwitch.pending() || cameraSwitch.wanted())) publishInput(() -> !cameraSwitch.pending() || cameraSwitch.wanted()); return null; });
    }
    public CompletableFuture<Void> startInput(Supplier<VideoInput> source) {
        return startInput(source, () -> true);
    }
    private CompletableFuture<Void> startInput(Supplier<VideoInput> source, java.util.function.BooleanSupplier wanted) {
        return submit(() -> {
            requireWanted(wanted);
            if (room.get() == null || self.get() == null) throw new SignalException("NOT_A_MEMBER");
            if (publisher != null) return null;
            show("Đang bật camera và thiết lập WebRTC…");
            stopLocalCamera();
            try {
                if (nativeSession == null) nativeSession = new NativeVideoSession();
                input = source.get(); publishInput(wanted);
            } catch (Exception | LinkageError failure) { stopLocalCamera(); if (!(failure instanceof CancellationException)) { mediaFailure("capture", failure); show(CameraErrors.message(failure)); } throw failure; }
            return null;
        });
    }
    private void publishInput(java.util.function.BooleanSupplier wanted) throws Exception {
        boolean publicationAttempted = false;
        try {
            requireWanted(wanted);
            if (preview != null) { preview.close(); preview = null; }
            if (closed.get()) throw new SignalException("CONNECTION_CLOSED");
            publisher = nativeSession.publisher(input, frames(self.get()));
            if (!inputStarted) { requireWanted(wanted); long before = frames(self.get()).receivedFrames(); input.start(); inputStarted = true; awaitCameraFrame(before, wanted); }
            requireWanted(wanted); String offer = publisher.offer(); requireWanted(wanted);
            JsonObject body = new JsonObject(); body.addProperty("sdp", offer);
            publicationAttempted = true; JsonObject answer = command("VIDEO_PUBLISH", body);
            if (closed.get()) throw new SignalException("CONNECTION_CLOSED");
            publisher.answer(answer.get("sdp").getAsString()); requireWanted(wanted);
            publisher.quality(appliedQuality); activeCamera = true;
            show("Camera đang bật; đang thiết lập kết nối WebRTC với SFU.");
        } catch (Exception | LinkageError failure) {
            stopLocalCamera(); if (publicationAttempted) try { command("VIDEO_STOP", new JsonObject()); } catch (Exception ignored) { }
            if (!(failure instanceof CancellationException)) { mediaFailure("publish", failure); show(CameraErrors.message(failure)); } throw failure;
        }
    }
    private void requireWanted(java.util.function.BooleanSupplier wanted) { if (!wanted.getAsBoolean()) throw new CancellationException(); }
    private void awaitCameraFrame(long before, java.util.function.BooleanSupplier wanted) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (frames(self.get()).receivedFrames() <= before) {
            requireWanted(wanted);
            if (closed.get()) throw new SignalException("CONNECTION_CLOSED");
            if (System.nanoTime() >= deadline) throw new IllegalStateException("CAMERA_NO_FRAMES");
            Thread.sleep(30);
        }
        requireWanted(wanted);
    }
    private void mediaFailure(String phase, Throwable failure) {
        common.diagnostics.Diagnostics.current().failure("video.client.operation.failed", failure, Map.of("phase", phase, "code", CameraErrors.code(failure)));
    }
    public CompletableFuture<Void> stopCamera() {
        return submit(() -> {
            boolean published = publisher != null;
            stopLocalCamera(); if (published) command("VIDEO_STOP", new JsonObject());
            show("Camera đã tắt. Bạn vẫn có thể nhận video của người khác."); return null;
        });
    }
    private void stopLocalCamera() {
        activeCamera = false;
        // Stop capture before dropping native tracks; borrowed view mailboxes stay reusable.
        VideoInput current = input; input = null; inputStarted = false;
        try { if (current != null) current.close(); }
        finally {
            if (preview != null) { preview.close(); preview = null; }
            if (publisher != null) { publisher.close(); publisher = null; }
        }
        show("Camera đã tắt.");
    }
    private void synchronize() throws Exception {
        if (!controlConnected) return;
        RoomSnapshot current = room.get();
        if (current == null) { stopLocalCamera(); return; }
        String own = self.get();
        if (publisher != null && (publisher.failed() || current.participants().stream().filter(p -> p.participantId().equals(own))
                .noneMatch(p -> p.mediaState().cameraRequested()))) { cameraSwitch.resetOff(); stopLocalCamera(); }
        JsonObject sources;
        try { sources = command("VIDEO_STATE", new JsonObject()).getAsJsonObject("sources"); }
        catch (Exception failure) {
            cameraSwitch.resetOff();
            stopLocalCamera(); subscriptions.values().forEach(s -> s.peer().close()); subscriptions.clear();
            show("Kết nối video gián đoạn. Camera đã tắt; hãy bật lại bằng tay khi server sẵn sàng."); throw failure;
        }
        if (appliedQuality == AdaptiveVideoPolicy.Profile.VOICE_PRIORITY) {
            subscriptions.values().forEach(subscription -> subscription.peer().close()); subscriptions.clear();
            return;
        }
        for (String target : new ArrayList<>(subscriptions.keySet())) {
            Subscription subscription = subscriptions.get(target);
            if (!sources.has(target) || !sources.get(target).getAsString().equals(subscription.publication()) || subscription.peer().failed()) {
                subscription.peer().close(); subscriptions.remove(target);
            }
        }
        for (var source : sources.entrySet()) {
            if (closed.get()) return;
            String target = source.getKey(), publication = source.getValue().getAsString();
            if (target.equals(own) || subscriptions.containsKey(target) || current.participants().stream().noneMatch(p -> p.participantId().equals(target))) continue;
            if (nativeSession == null) nativeSession = new NativeVideoSession();
            NativeVideoSession.Peer peer = nativeSession.subscriber(frames(target));
            try {
                JsonObject body = new JsonObject(); body.addProperty("source", target); body.addProperty("publication", publication); body.addProperty("sdp", peer.offer());
                JsonObject answer = command("VIDEO_SUBSCRIBE", body); peer.answer(answer.get("sdp").getAsString());
                subscriptions.put(target, new Subscription(publication, peer));
            } catch (Exception failure) { peer.close(); mediaFailure("subscribe", failure); show("Không nhận được một video: " + CameraErrors.message(failure)); }
        }
        for (String participant : new ArrayList<>(frames.keySet())) if (current.participants().stream().noneMatch(p -> p.participantId().equals(participant))) {
            VideoFrameMailbox old = frames.remove(participant); if (old != null) old.close();
        }
        if (preview == null && (activeCamera || !subscriptions.isEmpty())) show("Video qua WebRTC/SFU • " + subscriptions.size() + " luồng camera nhận.");
    }
    private JsonObject command(String type, JsonObject body) throws Exception {
        if (!controlConnected) throw new SignalException("CONNECTION_RESTORING");
        if (closed.get()) throw new SignalException("CONNECTION_CLOSED");
        try { return signaling.request(type, body, UUID.randomUUID().toString()).get(16, TimeUnit.SECONDS); }
        catch (ExecutionException failure) { if (failure.getCause() instanceof Exception cause) throw cause; throw failure; }
    }
    private <T> CompletableFuture<T> submit(Callable<T> operation) {
        return submitTo(worker, operation);
    }
    private <T> CompletableFuture<T> submitTo(ThreadPoolExecutor executor, Callable<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed.get()) { result.completeExceptionally(new SignalException("CONNECTION_CLOSED")); return result; }
        jobs.add(result);
        try { executor.execute(() -> {
            try { if (closed.get()) throw new SignalException("CONNECTION_CLOSED"); result.complete(operation.call()); }
            catch (Exception | LinkageError failure) { result.completeExceptionally(failure); }
            finally { jobs.remove(result); }
        }); } catch (RejectedExecutionException busy) { jobs.remove(result); result.completeExceptionally(new SignalException("VIDEO_BUSY")); }
        return result;
    }
    private void show(String text) {
        latestStatus.set(text);
        if (!statusScheduled.compareAndSet(false, true)) return;
        Platform.runLater(() -> {
            status.set(latestStatus.getAndSet(null)); updateCameraUi(); statusScheduled.set(false);
            if (latestStatus.get() != null) show(latestStatus.get());
        });
    }

    private void sampleNetworkStats() {
        if (!controlConnected) return;
        if (closed.get() || room.get() == null || self.get() == null) return;
        try {
            var voiceEvidence = audio.networkQuality();
            videoQualityMonitor.begin();
            long statsDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1000);
            Double rttSeconds = null;
            Double maxJitterSeconds = null;
            Long packetsLost = null;
            Long packetsReceived = null;
            Long bytesSent = null;
            Long framesSent = null;
            Integer width = null;
            Integer height = null;

            NativeVideoSession.Peer pub = publisher;
            if (pub != null) {
                dev.onvoid.webrtc.RTCStatsReport pubReport = pub.stats(500);
                videoQualityMonitor.accept(pub, pubReport, appliedQuality == AdaptiveVideoPolicy.Profile.VOICE_PRIORITY);
                if (pubReport != null) {
                    for (dev.onvoid.webrtc.RTCStats stat : pubReport.getStats().values()) {
                        if (stat.getType() == dev.onvoid.webrtc.RTCStatsType.CANDIDATE_PAIR) {
                            Object rttObj = stat.getAttributes().get("currentRoundTripTime");
                            if (rttObj instanceof Number num) {
                                double val = num.doubleValue();
                                if (val >= 0 && (rttSeconds == null || val < rttSeconds)) rttSeconds = val;
                            }
                        } else if (stat.getType() == dev.onvoid.webrtc.RTCStatsType.REMOTE_INBOUND_RTP) {
                            Object rttObj = stat.getAttributes().get("roundTripTime");
                            if (rttObj instanceof Number num) {
                                double val = num.doubleValue();
                                if (val >= 0 && (rttSeconds == null || val < rttSeconds)) rttSeconds = val;
                            }
                        } else if (stat.getType() == dev.onvoid.webrtc.RTCStatsType.OUTBOUND_RTP) {
                            Object bytesObj = stat.getAttributes().get("bytesSent");
                            if (bytesObj instanceof Number num) bytesSent = num.longValue();
                            Object framesObj = stat.getAttributes().get("framesSent");
                            if (framesObj == null) framesObj = stat.getAttributes().get("framesEncoded");
                            if (framesObj instanceof Number num) framesSent = num.longValue();
                            Object wObj = stat.getAttributes().get("frameWidth");
                            if (wObj instanceof Number num) width = num.intValue();
                            Object hObj = stat.getAttributes().get("frameHeight");
                            if (hObj instanceof Number num) height = num.intValue();
                        } else if (stat.getType() == dev.onvoid.webrtc.RTCStatsType.MEDIA_SOURCE) {
                            if (width == null) {
                                Object wObj = stat.getAttributes().get("width");
                                if (wObj instanceof Number num) width = num.intValue();
                            }
                            if (height == null) {
                                Object hObj = stat.getAttributes().get("height");
                                if (hObj instanceof Number num) height = num.intValue();
                            }
                        }
                    }
                }
            }

            List<Subscription> qualitySubscriptions = List.copyOf(subscriptions.values());
            int examined = 0;
            for (; examined < qualitySubscriptions.size(); examined++) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(statsDeadline - System.nanoTime());
                if (closed.get() || remainingMs <= 0) break;
                Subscription sub = qualitySubscriptions.get(Math.floorMod(qualityCursor + examined, qualitySubscriptions.size()));
                dev.onvoid.webrtc.RTCStatsReport subReport = sub.peer().stats(Math.min(100, remainingMs));
                videoQualityMonitor.accept(sub.peer(), subReport);
                if (subReport == null) continue;
                for (dev.onvoid.webrtc.RTCStats stat : subReport.getStats().values()) {
                    if (stat.getType() == dev.onvoid.webrtc.RTCStatsType.CANDIDATE_PAIR && rttSeconds == null) {
                        Object rttObj = stat.getAttributes().get("currentRoundTripTime");
                        if (rttObj instanceof Number num) {
                            double val = num.doubleValue();
                            if (val >= 0) rttSeconds = val;
                        }
                    } else if (stat.getType() == dev.onvoid.webrtc.RTCStatsType.INBOUND_RTP) {
                        Object jitterObj = stat.getAttributes().get("jitter");
                        if (jitterObj instanceof Number num) {
                            double j = num.doubleValue();
                            if (j >= 0) maxJitterSeconds = maxJitterSeconds == null ? j : Math.max(maxJitterSeconds, j);
                        }
                        Object lostObj = stat.getAttributes().get("packetsLost");
                        if (lostObj instanceof Number num) {
                            packetsLost = (packetsLost == null ? 0L : packetsLost) + num.longValue();
                        }
                        Object recvObj = stat.getAttributes().get("packetsReceived");
                        if (recvObj instanceof Number num) {
                            packetsReceived = (packetsReceived == null ? 0L : packetsReceived) + num.longValue();
                        }
                    }
                }
            }
            qualityCursor = qualitySubscriptions.isEmpty() ? 0 : Math.floorMod(qualityCursor + examined, qualitySubscriptions.size());

            NetworkStatsCalculator.RawCounters raw = new NetworkStatsCalculator.RawCounters(
                    System.nanoTime(), rttSeconds, maxJitterSeconds,
                    packetsLost, packetsReceived, bytesSent, framesSent, width, height
            );
            NetworkStatsCalculator.CalculatedSample counters = statsCalculator.process(raw);
            var videoEvidence = videoQualityMonitor.finish().merge(screen.transportQuality());
            var connectionEvidence = videoEvidence.merge(voiceEvidence);
            NetworkStatsCalculator.CalculatedSample sample = new NetworkStatsCalculator.CalculatedSample(
                    connectionEvidence.rttMs(), connectionEvidence.jitterMs(), connectionEvidence.lossPct(),
                    counters.bitrateKbps(), counters.fps(), counters.width(), counters.height());
            if (adapting.compareAndSet(false, true)) {
                observeNetwork(System.nanoTime(), videoEvidence, voiceEvidence).whenComplete((value, failure) -> {
                    adapting.set(false);
                    if (failure != null && !closed.get()) mediaFailure("adaptive_quality", failure);
                });
            }
            if (!closed.get()) {
                try {
                    Platform.runLater(() -> latestStats.set(sample));
                } catch (IllegalStateException ignored) {
                    latestStats.set(sample);
                }
            }
            if (sample.isEmpty() || closed.get()) return;

            JsonObject body = new JsonObject();
            if (sample.rttMs() != null) body.addProperty("rtt", sample.rttMs());
            if (sample.jitterMs() != null) body.addProperty("jitter", sample.jitterMs());
            if (sample.lossPct() != null) body.addProperty("loss", sample.lossPct());
            if (sample.bitrateKbps() != null) body.addProperty("bitrate", sample.bitrateKbps());
            if (sample.fps() != null) body.addProperty("fps", sample.fps());
            if (sample.width() != null) body.addProperty("width", sample.width());
            if (sample.height() != null) body.addProperty("height", sample.height());

            signaling.request("NETWORK_STATS", body, UUID.randomUUID().toString());
        } catch (Exception ignored) {
        }
    }

    /** All native sender changes share the camera owner, so a manual off/host stop always wins. */
    CompletableFuture<Void> observeNetwork(long observedAt, AdaptiveVideoPolicy.Evidence videoEvidence, AdaptiveVideoPolicy.Evidence voiceEvidence) {
        return submit(() -> {
            if (!controlConnected || !inMeeting || room.get() == null || self.get() == null || System.nanoTime() - observedAt > TimeUnit.SECONDS.toNanos(3)) return null;
            var next = adaptivePolicy.observe(observedAt, videoEvidence, voiceEvidence);
            if (next == appliedQuality) return null;
            if (publisher != null) publisher.quality(next);
            screen.prioritizeVoice(next == AdaptiveVideoPolicy.Profile.VOICE_PRIORITY).whenComplete((value, failure) -> {
                if (failure != null && !closed.get()) mediaFailure("adaptive_screen", failure);
            });
            appliedQuality = next;
            if (next == AdaptiveVideoPolicy.Profile.VOICE_PRIORITY) {
                subscriptions.values().forEach(subscription -> subscription.peer().close()); subscriptions.clear();
            }
            Platform.runLater(() -> { if (!closed.get()) quality.set(next); });
            common.diagnostics.Diagnostics.current().event("video.quality.changed", Map.of("status", next.name()));
            return null;
        });
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        statsSampler.shutdownNow();
        try {
            Platform.runLater(() -> latestStats.set(null));
        } catch (IllegalStateException ignored) {
            latestStats.set(null);
        }
        cameraSwitch.close(); audio.close(); screen.close(); formatCache.clear(); knownCameras = List.of();
        timer.shutdownNow(); worker.shutdownNow(); preferences.shutdownNow(); discovery.shutdownNow(); frames.values().forEach(VideoFrameMailbox::close);
        jobs.forEach(future -> future.completeExceptionally(new SignalException("CONNECTION_CLOSED"))); jobs.clear();
        Thread.ofVirtual().name("video-client-close").start(() -> {
            long started = System.nanoTime();
            try {
            try { while (!worker.awaitTermination(2, TimeUnit.SECONDS)) { /* Do not dispose a factory still used by a native call. */ } }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            stopLocalCamera(); subscriptions.values().forEach(s -> s.peer().close()); subscriptions.clear();
            if (nativeSession != null) nativeSession.close(); audio.closeComplete().get(20, TimeUnit.SECONDS); screen.closeComplete().get(20, TimeUnit.SECONDS); show("Video đã đóng.");
            common.diagnostics.Diagnostics.current().event("video.client.closed", Map.of("shutdown_ms", (System.nanoTime() - started) / 1_000_000.0));
            closeComplete.complete(null);
            } catch (Exception | LinkageError failure) {
                common.diagnostics.Diagnostics.current().failure("video.client.close_failed", failure, Map.of("phase", "close"));
                closeComplete.completeExceptionally(new IllegalStateException("VIDEO_CLEANUP_FAILED"));
            }
        });
    }
}
