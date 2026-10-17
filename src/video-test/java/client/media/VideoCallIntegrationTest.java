package client.media;

import client.meeting.MeetingSession;
import common.meeting.CreateRoomParams;
import dev.onvoid.webrtc.media.video.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.scene.Scene;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import support.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual native encode -> authenticated room signaling -> Go RTP fanout -> native decode/render. */
@Timeout(90)
public class VideoCallIntegrationTest {
    @TempDir static Path temporary;
    private static RoomTestFixture.Tls tls;
    @BeforeAll static void runtime() throws Exception { FxTestRuntime.start(); tls = RoomTestFixture.certificates(temporary); }
    @Test void rapidCameraPressesCancelAnOpeningCaptureAndFinishAtTheLastChoice() throws Exception {
        try (VideoTestFixture fixture = new VideoTestFixture(tls, temporary.resolve("rapid-toggle"))) {
            var account = fixture.database.account("rapidHost", "Rapid Host");
            MeetingSession host = fixture.session(account); VideoCallController video = null;
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var starts = new java.util.concurrent.atomic.AtomicInteger();
            try {
                host.create(new CreateRoomParams("Rapid camera", "1234", 2, false)).get(10, TimeUnit.SECONDS);
                host.join(false, false).get(10, TimeUnit.SECONDS); video = host.video(); var camera = video;
                java.util.function.Supplier<VideoInput> source = () -> {
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("TEST_CAPTURE_TIMEOUT"); }
                    catch (InterruptedException failure) { throw new CompletionException(failure); }
                    var generated = new GeneratedCamera(0);
                    return new VideoInput() {
                        public VideoTrackSource source() { return generated.source(); }
                        public void start() { starts.incrementAndGet(); generated.start(); }
                        public void close() { generated.close(); }
                    };
                };
                var pending = FxTestRuntime.call(() -> camera.toggleInput(source, true));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                FxTestRuntime.call(() -> {
                    for (int i = 0; i < 99; i++) camera.toggleInput(source, true);
                    assertFalse(camera.cameraRequestedProperty().get()); assertTrue(camera.cameraPendingProperty().get()); return null;
                });
                release.countDown(); pending.get(10, TimeUnit.SECONDS);
                assertEquals(0, starts.get(), "The final off press must cancel opening before capture starts");
                assertEquals(0, camera.frames(host.selfParticipantId()).receivedFrames());
                FxTestRuntime.call(() -> camera.toggleInput(source, false)).get(15, TimeUnit.SECONDS);
                FxTestRuntime.await(() -> camera.publishingProperty().get());
                assertEquals(1, starts.get());
                FxTestRuntime.call(() -> {
                    CompletableFuture<Void> latest = null;
                    for (int i = 0; i < 101; i++) latest = camera.toggleInput(source, false);
                    assertFalse(camera.cameraRequestedProperty().get()); return latest;
                }).get(15, TimeUnit.SECONDS);
                FxTestRuntime.await(() -> !camera.publishingProperty().get() && !camera.cameraPendingProperty().get());
                var state = host.videoRequest("VIDEO_STATE", new com.google.gson.JsonObject(), UUID.randomUUID().toString()).get(8, TimeUnit.SECONDS);
                assertTrue(state.getAsJsonObject("sources").isEmpty(), "The final off choice must remove the SFU publication");
            } finally {
                release.countDown(); host.close(); if (video != null) video.closeComplete().get(10, TimeUnit.SECONDS);
            }
        }
    }
    @Test void separateReceiveOnlyJvmGetsVideoWhileCameraDiscoveryIsStalled() throws Exception {
        try (VideoTestFixture fixture = new VideoTestFixture(tls, temporary.resolve("separate-jvm"))) {
            var host = fixture.database.account("separateHost", "Test Host"); var guest = fixture.database.account("separateGuest", "Test Guest");
            MeetingSession publisher = fixture.session(host); Process child = null; VideoCallController source = null;
            try {
                var room = publisher.create(new CreateRoomParams("Separate native JVM", "1234", 2, false)).get(10, TimeUnit.SECONDS);
                publisher.join(false, false).get(10, TimeUnit.SECONDS); source = publisher.video();
                source.startInput(() -> new GeneratedCamera(0)).get(15, TimeUnit.SECONDS);
                Path output = temporary.resolve("separate-jvm-result.properties");
                String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
                var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-cp", classpath, VideoReceiverProcess.class.getName());
                var environment = builder.environment();
                environment.put("MMR_TEST_TOKEN", guest.token()); environment.put("MMR_TEST_EXPIRY", guest.expiresAt());
                environment.put("MMR_TEST_CERTIFICATE", temporary.resolve("room-test.cer").toString());
                environment.put("MMR_TEST_SIGNAL_PORT", Integer.toString(fixture.signaling.port()));
                environment.put("MMR_TEST_ROOM_CODE", room.joinCode()); environment.put("MMR_TEST_SOURCE", publisher.selfParticipantId());
                environment.put("MMR_TEST_RESULT", output.toString());
                environment.remove("MMR_DB_URL"); environment.remove("MMR_DB_USER"); environment.remove("MMR_DB_PASSWORD");
                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD); builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                child = builder.start();
                assertTrue(child.waitFor(40, TimeUnit.SECONDS), "The isolated receiver must finish without a native hang");
                assertTrue(Files.exists(output), "The child must write sanitized test counters");
                Properties result = new Properties(); try (var reader = Files.newBufferedReader(output)) { result.load(reader); }
                assertNull(result.getProperty("error"), "Separate receiver reported an error");
                assertTrue(Long.parseLong(result.getProperty("received", "0")) >= 5);
                assertTrue(Long.parseLong(result.getProperty("rendered", "0")) > 0);
            } finally {
                if (child != null && child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
                publisher.close(); if (source != null) source.closeComplete().get(10, TimeUnit.SECONDS);
            }
        }
    }
    @Test void existingCreateJoinPrejoinMeetingAndFileTabUseTheSameVideoRoom() throws Exception {
        try (VideoTestFixture fixture = new VideoTestFixture(tls, temporary.resolve("main-ui"))) {
            String schema = Files.readString(Path.of("database/schema.sql"))
                    .replaceAll("(?m)^\\s*--.*$", "").replace("ENGINE = InnoDB", "");
            for (String sql : schema.split(";")) {
                String statement = sql.strip();
                if (statement.startsWith("CREATE TABLE Tin_Nhan") || statement.startsWith("CREATE TABLE Tep_Dinh_Kem")) fixture.database.execute(statement);
            }
            var config = new server.filetransfer.TransferConfig(true, "127.0.0.1", 0, 65536, 4, 2, 5000,
                    temporary.resolve("main-ui-storage"), 1024 * 1024, 8 * 1024 * 1024, 4, java.time.Duration.ofHours(1), true);
            var storage = new server.filetransfer.FileStorageService(config.storagePath(), config.quotaBytes(), config.maxFileBytes(), config.maxPendingPerUser(), config.expiry(), config.resumeEnabled());
            try (var files = new server.filetransfer.FileTransferServer(tls.server(), config,
                    new server.filetransfer.FileMetadataRepository(fixture.database::connection), storage)) {
                files.start();
                var host = fixture.database.account("mainHost", "Main Host"); var guest = fixture.database.account("mainGuest", "Main Guest");
                var hostFiles = new client.filetransfer.FileTransferClient("localhost", files.port(), tls.client(), 5000);
                var guestFiles = new client.filetransfer.FileTransferClient("localhost", files.port(), tls.client(), 5000);
                var managers = new ArrayList<client.UI.SceneManager>(); var stages = new ArrayList<javafx.stage.Stage>();
                client.UI.dialogs.CreateRoomDialog dialog = null;
                VideoCallController hostVideo = null, guestVideo = null;
                try {
                    FxTestRuntime.call(() -> {
                        for (var account : List.of(host, guest)) {
                            var stage = new javafx.stage.Stage(); stages.add(stage);
                            var manager = new client.UI.SceneManager(stage, fixture.client(), account == host ? hostFiles : guestFiles,
                                    (token, expiry) -> fixture.session(account));
                            managers.add(manager); manager.acceptSession(account); manager.lobby();
                            assertNull(stage.getScene().getRoot().lookup("#open-sfu-rooms"));
                        }
                        return null;
                    });
                    dialog = FxTestRuntime.call(() -> {
                        var create = new client.UI.dialogs.CreateRoomDialog(managers.get(0));
                        create.getDialogPane().applyCss(); create.getDialogPane().layout();
                        find(create.getDialogPane(), "room-name", javafx.scene.control.TextField.class).setText("Main video room");
                        find(create.getDialogPane(), "room-pin", javafx.scene.control.TextField.class).setText("1234");
                        find(create.getDialogPane(), "create-room-submit", javafx.scene.control.Button.class).fire(); return create;
                    });
                    var createdDialog = dialog; FxTestRuntime.await(() -> createdDialog.getResult() != null);
                    var room = FxTestRuntime.call(() -> {
                        var result = createdDialog.getResult(); managers.get(0).preJoin(result);
                        assertTrue(stages.get(0).getScene().getRoot() instanceof client.UI.screens.PreJoinScreen);
                        RoomUiAssertions.noRoomIds(stages.get(0).getScene().getRoot(), result.id());
                        assertTrue(managers.get(0).hasVideoRoom()); return result;
                    });
                    assertEquals(1, fixture.database.count("SELECT COUNT(*) FROM Phong_Hop"));
                    assertEquals(1, fixture.database.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
                    FxTestRuntime.call(() -> {
                        var lobby = stages.get(1).getScene().getRoot();
                        RoomUiAssertions.noRoomIds(lobby, room.id());
                        find(lobby, "join-room-identifier", javafx.scene.control.TextField.class).setText(room.code());
                        find(lobby, "join-room-pin", javafx.scene.control.TextField.class).setText("9999");
                        find(lobby, "join-room-submit", javafx.scene.control.Button.class).fire(); return null;
                    });
                    FxTestRuntime.await(() -> find(stages.get(1).getScene().getRoot(), "join-room-error", javafx.scene.control.Label.class).getText().contains("PIN phòng không đúng"));
                    FxTestRuntime.call(() -> {
                        var lobby = stages.get(1).getScene().getRoot();
                        find(lobby, "join-room-pin", javafx.scene.control.TextField.class).setText("1234");
                        find(lobby, "join-room-submit", javafx.scene.control.Button.class).fire(); return null;
                    });
                    FxTestRuntime.await(() -> stages.get(1).getScene().getRoot() instanceof client.UI.screens.PreJoinScreen);
                    FxTestRuntime.await(() -> stages.stream().allMatch(stage -> !find(stage.getScene().getRoot(), "video-camera-toggle", javafx.scene.control.Button.class).isDisabled()));
                    FxTestRuntime.call(() -> {
                        var waiting = stages.get(0).getScene().getRoot();
                        assertTrue(find(waiting, "enter-meeting", javafx.scene.control.Button.class).isDisabled());
                        assertNull(waiting.lookup("#camera-permission"));
                        managers.get(0).meeting();
                        assertSame(waiting, stages.get(0).getScene().getRoot(), "The entry method must also reject missing permission");
                        assertTrue(nodes(waiting).filter(javafx.scene.control.Label.class::isInstance).map(javafx.scene.control.Label.class::cast)
                                .noneMatch(label -> label.getText().contains("camera vật lý") || label.getText().contains("sẽ được bổ sung")));
                        find(waiting, "video-camera-toggle", javafx.scene.control.Button.class).fire(); return null;
                    });
                    answerCameraPermission(false);
                    FxTestRuntime.await(() -> !find(stages.get(0).getScene().getRoot(), "video-camera-toggle", javafx.scene.control.Button.class).isDisabled());
                    FxTestRuntime.call(() -> {
                        var waiting = stages.get(0).getScene().getRoot();
                        assertTrue(find(waiting, "enter-meeting", javafx.scene.control.Button.class).isDisabled());
                        managers.get(0).meeting(); assertSame(waiting, stages.get(0).getScene().getRoot());
                        find(waiting, "video-camera-toggle", javafx.scene.control.Button.class).fire(); return null;
                    });
                    answerCameraPermission(true);
                    FxTestRuntime.await(() -> !find(stages.get(0).getScene().getRoot(), "enter-meeting", javafx.scene.control.Button.class).isDisabled());
                    FxTestRuntime.call(() -> { find(stages.get(1).getScene().getRoot(), "video-camera-toggle", javafx.scene.control.Button.class).fire(); return null; });
                    answerCameraPermission(true);
                    FxTestRuntime.await(() -> !find(stages.get(1).getScene().getRoot(), "enter-meeting", javafx.scene.control.Button.class).isDisabled());
                    hostVideo = FxTestRuntime.call(() -> managers.get(0).sfuSession().video());
                    guestVideo = FxTestRuntime.call(() -> managers.get(1).sfuSession().video());
                    var hostSession = FxTestRuntime.call(() -> managers.get(0).sfuSession());
                    var guestSession = FxTestRuntime.call(() -> managers.get(1).sfuSession());
                    hostVideo.previewInput(() -> new GeneratedCamera(0)).get(10, TimeUnit.SECONDS);
                    guestVideo.previewInput(() -> new GeneratedCamera(1)).get(10, TimeUnit.SECONDS);
                    var hostMedia = hostVideo; var guestMedia = guestVideo;
                    FxTestRuntime.await(() -> hostMedia.frames(hostSession.selfParticipantId()).receivedFrames() >= 3
                            && guestMedia.frames(guestSession.selfParticipantId()).receivedFrames() >= 3);
                    FxTestRuntime.call(() -> {
                        var root = stages.get(0).getScene().getRoot(); root.applyCss(); root.layout();
                        var snapshot = root.snapshot(null, null); int width = (int) snapshot.getWidth(), height = (int) snapshot.getHeight();
                        int[] pixels = new int[width * height]; snapshot.getPixelReader().getPixels(0, 0, width, height, javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, width);
                        var picture = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB); picture.setRGB(0, 0, width, height, pixels, 0, width);
                        javax.imageio.ImageIO.write(picture, "png", Path.of("target/video-prejoin-clean-preview.png").toFile()); return null;
                    });
                    hostMedia.audio().access(true).get(5, TimeUnit.SECONDS);
                    var beforeMeeting = hostSession.videoRequest("VIDEO_STATE", new com.google.gson.JsonObject(), UUID.randomUUID().toString()).get(8, TimeUnit.SECONDS);
                    assertTrue(beforeMeeting.getAsJsonObject("sources").isEmpty(), "Pre-join camera must stay private until Enter Meeting");
                    FxTestRuntime.call(() -> {
                        for (var stage : stages) find(stage.getScene().getRoot(), "enter-meeting", javafx.scene.control.Button.class).fire();
                        assertTrue(stages.stream().allMatch(stage -> stage.getScene().getRoot() instanceof client.UI.screens.MeetingScreen));
                        stages.forEach(stage -> RoomUiAssertions.noRoomIds(stage.getScene().getRoot(), room.id())); return null;
                    });
                    String hostId = hostSession.selfParticipantId(), guestId = guestSession.selfParticipantId();
                    FxTestRuntime.await(() -> hostMedia.frames(guestId).receivedFrames() >= 3 && guestMedia.frames(hostId).receivedFrames() >= 3);
                    FxTestRuntime.await(() -> !find(stages.getFirst().getScene().getRoot(),"audio-microphone-toggle",javafx.scene.control.Button.class).isDisabled());
                    FxTestRuntime.call(() -> {find(stages.getFirst().getScene().getRoot(),"meeting-mic",javafx.scene.control.Button.class).fire();return null;});
                    FxTestRuntime.await(() -> hostMedia.audio().requestedProperty().get());
                    AudioCallIntegrationTest.await(() -> guestMedia.audio().receivedSamples(hostId)>4800);
                    FxTestRuntime.call(() -> {find(stages.getFirst().getScene().getRoot(),"meeting-mic",javafx.scene.control.Button.class).fire();return null;});
                    FxTestRuntime.await(() -> !hostMedia.audio().requestedProperty().get());
                    FxTestRuntime.call(() -> hostMedia.screen().toggleInput(GeneratedScreen::new)).get(20,TimeUnit.SECONDS);
                    FxTestRuntime.await(() -> nodes(stages.get(1).getScene().getRoot()).anyMatch(n -> "meeting-screen-image".equals(n.getId())));
                    FxTestRuntime.await(() -> find(stages.get(1).getScene().getRoot(),"meeting-screen-image",javafx.scene.image.ImageView.class).getImage()!=null);
                    FxTestRuntime.call(() -> {
                        var root=stages.get(1).getScene().getRoot();root.applyCss();root.layout();
                        var snapshot=root.snapshot(null,null);int width=(int)snapshot.getWidth(),height=(int)snapshot.getHeight();
                        int[] pixels=new int[width*height];snapshot.getPixelReader().getPixels(0,0,width,height,javafx.scene.image.PixelFormat.getIntArgbInstance(),pixels,0,width);
                        var picture=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB);picture.setRGB(0,0,width,height,pixels,0,width);
                        javax.imageio.ImageIO.write(picture,"png",Path.of("target/screen-generated-preview.png").toFile());return null;
                    });
                    FxTestRuntime.call(() -> {
                        var share=find(stages.getFirst().getScene().getRoot(),"meeting-share",javafx.scene.control.Button.class);
                        assertTrue(share.getPseudoClassStates().contains(javafx.css.PseudoClass.getPseudoClass("active")));share.fire();return null;
                    });
                    FxTestRuntime.await(() -> !hostMedia.screen().requestedProperty().get());
                    double expandedHeight = FxTestRuntime.call(() -> {
                        var root = stages.get(0).getScene().getRoot(); root.applyCss(); root.layout();
                        assertTrue(nodes(root).noneMatch(node -> "video-camera-list".equals(node.getId())));
                        assertTrue(nodes(root).noneMatch(node -> "camera-controls".equals(node.getId())), "Camera configuration belongs in Settings, not the meeting footer");
                        assertNull(root.lookup("#room-management"));
                        assertTrue(find(root, "meeting-tabs", javafx.scene.control.TabPane.class).getTabs().get(3).isDisable());
                        find(root, "meeting-settings", javafx.scene.control.Button.class).fire();
                        return find(root, "meeting-video-area", javafx.scene.layout.StackPane.class).getHeight();
                    });
                    FxTestRuntime.await(() -> javafx.stage.Window.getWindows().stream().anyMatch(window -> window.getScene() != null
                            && nodes(window.getScene().getRoot()).anyMatch(node -> "camera-controls".equals(node.getId()))));
                    FxTestRuntime.call(() -> {
                        for (var window : List.copyOf(javafx.stage.Window.getWindows())) if (window.getScene() != null && window.getScene().getRoot() instanceof javafx.scene.control.DialogPane pane
                                && nodes(pane).anyMatch(node -> "camera-controls".equals(node.getId()))) {
                            assertTrue(find(pane, "room-settings-section", javafx.scene.layout.VBox.class).isManaged());
                            assertFalse(nodes(pane).filter(javafx.scene.control.Button.class::isInstance).map(javafx.scene.control.Button.class::cast)
                                    .anyMatch(button -> "Đóng phòng".equals(button.getText())));
                            saveUi(pane, "room-settings-preview.png");
                            find(pane, "room-approval", javafx.scene.control.CheckBox.class).fire();
                            var root = (javafx.scene.layout.Region) stages.get(0).getScene().getRoot();
                            boolean original = client.UI.ThemeManager.isLight();
                            find(pane, "theme-light", javafx.scene.control.Button.class).fire(); root.applyCss(); pane.applyCss();
                            var light = root.getBackground().getFills().getFirst().getFill();
                            find(pane, "theme-dark", javafx.scene.control.Button.class).fire(); root.applyCss(); pane.applyCss();
                            assertNotEquals(light, root.getBackground().getFills().getFirst().getFill(), "Meeting must change palette with its Settings dialog");
                            client.UI.ThemeManager.setLight(original);
                            ((javafx.scene.control.Button) pane.lookupButton(javafx.scene.control.ButtonType.CLOSE)).fire();
                        }
                        return null;
                    });
                    var waitingAccount = fixture.database.account("uiWaiting", "Waiting Guest");
                    var waiter = fixture.session(waitingAccount);
                    try {
                        // Wait for the host's settings reply before submitting the admission.
                        long settingsDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                        boolean approvalReady;
                        do {
                            approvalReady = fixture.client().meeting(common.meeting.RoomRequest.command("ROOM_POLL", host.token(), room.id(), hostSession.selfParticipantId(), 0)).room().approval();
                            if (!approvalReady) Thread.sleep(40);
                        } while (!approvalReady && System.nanoTime() < settingsDeadline);
                        assertTrue(approvalReady);
                        FxTestRuntime.await(() -> !find(stages.get(0).getScene().getRoot(), "meeting-tabs", javafx.scene.control.TabPane.class).getTabs().get(3).isDisable());
                        FxTestRuntime.call(() -> {
                            var root = stages.get(0).getScene().getRoot();
                            var tabs = find(root, "meeting-tabs", javafx.scene.control.TabPane.class);
                            assertEquals("waiting-room-tab", tabs.getTabs().get(3).getId());
                            tabs.getSelectionModel().select(3); root.applyCss(); root.layout();
                            var guestTabs = find(stages.get(1).getScene().getRoot(), "meeting-tabs", javafx.scene.control.TabPane.class);
                            assertTrue(guestTabs.getTabs().get(3).isDisable());
                            assertTrue(nodes(find(root, "meeting-toolbar-content", javafx.scene.layout.VBox.class))
                                    .filter(javafx.scene.control.Label.class::isInstance).map(javafx.scene.control.Label.class::cast)
                                    .noneMatch(label -> label.isVisible() && (label.getText().contains("cùng phòng") || label.getText().contains("WebRTC/SFU"))));
                            return null;
                        });
                        waiter.lookup(room.code(), "1234").get(10, TimeUnit.SECONDS); var admission = waiter.join(false, false);
                        FxTestRuntime.await(() -> {
                            var root = stages.get(0).getScene().getRoot(); root.applyCss(); root.layout();
                            return root.lookup("#admit-" + waitingAccount.user().id()) != null;
                        });
                        FxTestRuntime.call(() -> { saveUi(stages.get(0).getScene().getRoot(), "room-admission-preview.png"); return null; });
                        long beforeAdmission = hostMedia.frames(guestId).receivedFrames();
                        Thread.sleep(6000); assertFalse(admission.isDone());
                        assertTrue(hostMedia.frames(guestId).receivedFrames() > beforeAdmission, "Video must continue while another client waits");
                        FxTestRuntime.call(() -> { find(stages.get(0).getScene().getRoot(), "admit-" + waitingAccount.user().id(), javafx.scene.control.Button.class).fire(); return null; });
                        admission.get(10, TimeUnit.SECONDS);
                        FxTestRuntime.await(() -> hostSession.roomProperty().get().participants().size() == 3);
                        FxTestRuntime.call(() -> {
                            // Hold the FX event queue while the network worker removes a member.
                            // A pin click must still render the coherent, currently displayed room.
                            waiter.leave().get(5, TimeUnit.SECONDS);
                            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                            while (hostSession.snapshot().participants().size() != 2 && System.nanoTime() < until) Thread.sleep(10);
                            assertEquals(2, hostSession.snapshot().participants().size());
                            assertEquals(3, hostSession.roomProperty().get().participants().size());
                            var root = stages.get(0).getScene().getRoot();
                            ((javafx.scene.control.Button) root.lookup(".pin-action")).fire();
                            root.applyCss(); root.layout();
                            assertEquals(3, root.lookupAll(".video-tile").size()); return null;
                        });
                        FxTestRuntime.await(() -> "Chờ duyệt".equals(find(stages.get(0).getScene().getRoot(), "meeting-tabs", javafx.scene.control.TabPane.class).getTabs().get(3).getText()));
                        FxTestRuntime.call(() -> {
                            var root = stages.get(0).getScene().getRoot();
                            assertNull(root.lookup("#admit-" + waitingAccount.user().id()));
                            assertEquals(3, find(root, "meeting-tabs", javafx.scene.control.TabPane.class).getSelectionModel().getSelectedIndex()); return null;
                        });
                        assertTrue(fixture.client().meeting(common.meeting.RoomRequest.settings(host.token(), room.id(), hostSession.selfParticipantId(), null, false)).success());
                        FxTestRuntime.await(() -> {
                            var tabs = find(stages.get(0).getScene().getRoot(), "meeting-tabs", javafx.scene.control.TabPane.class);
                            return tabs.getTabs().get(3).isDisable() && tabs.getSelectionModel().getSelectedIndex() == 0;
                        });
                    } finally { waiter.close(); }
                    long beforeHidden = hostMedia.frames(guestId).receivedFrames();
                    FxTestRuntime.call(() -> {
                        var root = stages.get(0).getScene().getRoot();
                        find(root, "toggle-meeting-toolbar", javafx.scene.control.Button.class).fire(); root.layout();
                        assertFalse(find(root, "meeting-toolbar-content", javafx.scene.layout.VBox.class).isManaged());
                        assertTrue(find(root, "meeting-video-area", javafx.scene.layout.StackPane.class).getHeight() > expandedHeight + 60,
                                "Hidden tools must return space to the video area");
                        assertTrue(find(root, "show-meeting-toolbar", javafx.scene.control.Button.class).isVisible()); return null;
                    });
                    FxTestRuntime.await(() -> hostMedia.frames(guestId).receivedFrames() > beforeHidden + 3);
                    FxTestRuntime.call(() -> { saveUi(stages.get(0).getScene().getRoot(), "room-toolbar-hidden-preview.png"); return null; });
                    FxTestRuntime.call(() -> {
                        var root = stages.get(0).getScene().getRoot();
                        find(root, "show-meeting-toolbar", javafx.scene.control.Button.class).fire(); root.layout();
                        assertTrue(find(root, "meeting-toolbar-content", javafx.scene.layout.VBox.class).isManaged());
                        assertTrue(hostMedia.publishingProperty().get()); return null;
                    });
                    FxTestRuntime.call(() -> {
                        for (var stage : stages) {
                            var root = stage.getScene().getRoot(); root.applyCss(); root.layout();
                            nodes(root).filter(javafx.scene.control.TabPane.class::isInstance)
                                    .map(javafx.scene.control.TabPane.class::cast).findFirst().orElseThrow().getSelectionModel().select(1);
                            root.applyCss(); root.layout();
                        }
                        return null;
                    });
                    FxTestRuntime.await(() -> !find(stages.get(0).getScene().getRoot(), "chat-send", javafx.scene.control.Button.class).isDisabled());
                    FxTestRuntime.call(() -> {
                        find(stages.get(0).getScene().getRoot(), "chat-input", javafx.scene.control.TextField.class).setText("Video and chat in one room");
                        find(stages.get(0).getScene().getRoot(), "chat-send", javafx.scene.control.Button.class).fire(); return null;
                    });
                    try {
                        FxTestRuntime.await(() -> nodes(stages.get(1).getScene().getRoot()).filter(javafx.scene.control.Label.class::isInstance)
                                .map(javafx.scene.control.Label.class::cast).anyMatch(label -> label.getText().equals("Video and chat in one room")));
                    } catch (AssertionError failure) {
                        long stored = fixture.database.count("SELECT COUNT(*) FROM Tin_Nhan WHERE Loai_Tin_Nhan='VAN_BAN'");
                        var status = FxTestRuntime.call(() -> stages.stream().map(stage -> nodes(stage.getScene().getRoot()).filter(javafx.scene.control.Label.class::isInstance)
                                .map(javafx.scene.control.Label.class::cast).map(javafx.scene.control.Label::getText).toList()).toList());
                        throw new AssertionError("Synthetic chat stored=" + stored + ", UI labels=" + status, failure);
                    }
                    assertEquals(room.id(), FxTestRuntime.call(() -> managers.get(1).serverRoom().id()));
                    assertEquals(2, fixture.database.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
                    Path source = temporary.resolve("video-room-file.txt"); Files.writeString(source, "File sharing in the same authenticated video room.");
                    var upload = hostFiles.prepareUpload(room.id(), source, UUID.randomUUID(), new client.filetransfer.FileTransferClient.Cancellation(), null);
                    var metadata = hostFiles.upload(host.token(), upload, new client.filetransfer.FileTransferClient.Cancellation(), null);
                    assertEquals(metadata.id(), guestFiles.listFiles(guest.token(), room.id(), 0).getFirst().id());
                    Path downloaded = temporary.resolve("video-room-downloaded.txt");
                    guestFiles.download(guest.token(), metadata, downloaded, new client.filetransfer.FileTransferClient.Cancellation(), null);
                    assertEquals(Files.readString(source), Files.readString(downloaded));
                    FxTestRuntime.await(() -> {
                        var tabs = nodes(stages.get(1).getScene().getRoot()).filter(javafx.scene.control.TabPane.class::isInstance)
                                .map(javafx.scene.control.TabPane.class::cast).findFirst().orElseThrow();
                        return nodes(tabs.getTabs().get(2).getContent()).filter(javafx.scene.control.Label.class::isInstance)
                                .map(javafx.scene.control.Label.class::cast).anyMatch(label -> label.getText().contains("video-room-file.txt"));
                    });
                    FxTestRuntime.await(() -> nodes(stages.get(1).getScene().getRoot()).filter(javafx.scene.control.TabPane.class::isInstance)
                            .map(javafx.scene.control.TabPane.class::cast).findFirst().orElseThrow().getTabs().size() == 4);
                    FxTestRuntime.call(() -> {
                        var root = stages.get(0).getScene().getRoot(); root.applyCss(); root.layout();
                        var snapshot = root.snapshot(null, null); int width = (int) snapshot.getWidth(), height = (int) snapshot.getHeight();
                        int[] pixels = new int[width * height]; snapshot.getPixelReader().getPixels(0, 0, width, height, javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, width);
                        var picture = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB); picture.setRGB(0, 0, width, height, pixels, 0, width);
                        javax.imageio.ImageIO.write(picture, "png", Path.of("target/video-main-meeting-preview.png").toFile());
                        find(stages.get(1).getScene().getRoot(), "leave-meeting", javafx.scene.control.Button.class).fire(); return null;
                    });
                    guestMedia.closeComplete().get(10, TimeUnit.SECONDS);
                    FxTestRuntime.await(() -> stages.get(1).getScene().getRoot() instanceof client.UI.screens.MeetingEndedScreen && !managers.get(1).isRoomJoined());
                    FxTestRuntime.call(() -> { RoomUiAssertions.noRoomIds(stages.get(1).getScene().getRoot(), room.id()); return null; });
                    FxTestRuntime.await(() -> hostSession.participants().size() == 1);
                    assertTrue(FxTestRuntime.call(() -> hostMedia.publishingProperty().get()));
                    assertThrows(java.io.IOException.class, () -> guestFiles.listFiles(guest.token(), room.id(), 0));
                    FxTestRuntime.call(() -> {
                        assertEquals("Vào lại phòng", find(stages.get(1).getScene().getRoot(), "reopen-room-submit", javafx.scene.control.Button.class).getText());
                        javafx.application.Platform.runLater(() -> find(stages.get(0).getScene().getRoot(), "leave-meeting", javafx.scene.control.Button.class).fire()); return null;
                    });
                    FxTestRuntime.await(() -> hostLeavePane() != null);
                    FxTestRuntime.call(() -> { saveUi(hostLeavePane(), "room-host-leave-preview.png"); return null; });
                    FxTestRuntime.call(() -> { ((javafx.scene.control.Button) hostLeavePane().lookupButton(javafx.scene.control.ButtonType.CANCEL)).fire(); return null; });
                    FxTestRuntime.await(() -> !stages.get(0).getScene().getRoot().isDisabled());
                    assertTrue(hostMedia.publishingProperty().get(), "Canceling host departure must preserve the camera");
                    FxTestRuntime.call(() -> { javafx.application.Platform.runLater(() -> find(stages.get(0).getScene().getRoot(), "leave-meeting", javafx.scene.control.Button.class).fire()); return null; });
                    FxTestRuntime.await(() -> hostLeavePane() != null);
                    FxTestRuntime.call(() -> { ((javafx.scene.control.Button) hostLeavePane().lookup("#host-leave-close")).fire(); return null; });
                    hostMedia.closeComplete().get(10, TimeUnit.SECONDS);
                    FxTestRuntime.await(() -> stages.get(0).getScene().getRoot() instanceof client.UI.screens.MeetingEndedScreen
                            && find(stages.get(0).getScene().getRoot(), "reopen-room-submit", javafx.scene.control.Button.class).getText().equals("Mở lại phòng"));
                    FxTestRuntime.call(() -> { find(stages.get(0).getScene().getRoot(), "reopen-room-submit", javafx.scene.control.Button.class).fire(); return null; });
                    FxTestRuntime.await(() -> stages.get(0).getScene().getRoot() instanceof client.UI.screens.PreJoinScreen);
                    FxTestRuntime.call(() -> {
                        assertEquals(room.id(), managers.get(0).serverRoom().id()); assertEquals(room.code(), managers.get(0).serverRoom().code());
                        RoomUiAssertions.noRoomIds(stages.get(0).getScene().getRoot(), room.id()); return null;
                    });
                    assertEquals(1, fixture.database.count("SELECT COUNT(*) FROM Phong_Hop"));
                    assertEquals(1, fixture.database.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
                    assertThrows(java.io.IOException.class, () -> guestFiles.listFiles(guest.token(), room.id(), 0));
                } finally {
                    var closingDialog = dialog;
                    FxTestRuntime.call(() -> {
                        if (closingDialog != null) closingDialog.close();
                        managers.forEach(client.UI.SceneManager::shutdown); stages.forEach(javafx.stage.Stage::hide); return null;
                    });
                    if (hostVideo != null) hostVideo.closeComplete().get(10, TimeUnit.SECONDS);
                    if (guestVideo != null) guestVideo.closeComplete().get(10, TimeUnit.SECONDS);
                    hostFiles.close(); guestFiles.close();
                }
            }
        }
    }
    private static java.util.stream.Stream<javafx.scene.Node> nodes(javafx.scene.Node node) {
        if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null)
            return java.util.stream.Stream.concat(java.util.stream.Stream.of(node), nodes(scroll.getContent()));
        if (node instanceof javafx.scene.Parent parent)
            return java.util.stream.Stream.concat(java.util.stream.Stream.of(node), parent.getChildrenUnmodifiable().stream().flatMap(VideoCallIntegrationTest::nodes));
        return java.util.stream.Stream.of(node);
    }
    private static <T extends javafx.scene.Node> T find(javafx.scene.Parent root, String id, Class<T> type) {
        return nodes(root).filter(node -> id.equals(node.getId())).map(type::cast).findFirst().orElseThrow(() -> new AssertionError("Missing UI node " + id));
    }
    @Test void threeNativeClientsExchangeVideoAndCameraStopDoesNotBreakOthers() throws Exception {
        try (common.diagnostics.Diagnostics diagnostics = common.diagnostics.Diagnostics.start("client");
                VideoTestFixture fixture = new VideoTestFixture(tls, temporary)) {
            List<MeetingSession> sessions = new ArrayList<>();
            List<VideoCallController> controllers = new ArrayList<>();
            try {
                for (int user = 0; user < 3; user++) sessions.add(fixture.session(fixture.database.account("video" + user, "Video " + user)));
                var room = sessions.getFirst().create(new CreateRoomParams("Native video test", "1234", 3, false)).get(8, TimeUnit.SECONDS);
                sessions.getFirst().join(false, false).get(8, TimeUnit.SECONDS);
                for (int user = 1; user < 3; user++) {
                    sessions.get(user).lookup(room.joinCode(), "1234").get(8, TimeUnit.SECONDS);
                    sessions.get(user).join(false, false).get(8, TimeUnit.SECONDS);
                }
                FxTestRuntime.await(() -> sessions.stream().allMatch(s -> s.participants().size() == 3));
                for (MeetingSession session : sessions) controllers.add(session.video());
                for (int user = 0; user < 3; user++) {
                    int color = user;
                    controllers.get(user).startInput(() -> new GeneratedCamera(color)).get(15, TimeUnit.SECONDS);
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                boolean all;
                do {
                    all = true;
                    for (int receiver = 0; receiver < 3; receiver++) for (int source = 0; source < 3; source++) if (source != receiver) {
                        if (controllers.get(receiver).frames(sessions.get(source).selfParticipantId()).receivedFrames() < 5) all = false;
                    }
                    if (!all) Thread.sleep(100);
                } while (!all && System.nanoTime() < deadline);
                assertTrue(all, "Each native client must decode both other clients through the SFU");
                for (int receiver = 0; receiver < 3; receiver++) for (int source = 0; source < 3; source++) if (receiver != source) {
                    VideoFrameMailbox mailbox = controllers.get(receiver).frames(sessions.get(source).selfParticipantId());
                    diagnostics.event("video.integration.received", Map.of("kind", "VIDEO", "track_id", receiver * 10 + source,
                            "decoded_video_frames_cumulative", mailbox.receivedFrames(), "render_frames_cumulative", mailbox.renderedFrames(),
                            "render_dropped_frames_cumulative", mailbox.droppedFrames()));
                }
                FxTestRuntime.call(() -> {
                    for (int receiver = 0; receiver < 3; receiver++) for (int source = 0; source < 3; source++) if (source != receiver) {
                        var image = controllers.get(receiver).frames(sessions.get(source).selfParticipantId()).imageProperty().get();
                        assertNotNull(image, "Decoded video must reach the JavaFX image"); assertEquals(320, image.getWidth()); assertEquals(240, image.getHeight());
                    }
                    var preview = new client.UI.components.VideoCallPane(sessions.getFirst());
                    Scene scene = new Scene(preview, 1000, 650); scene.getStylesheets().add(client.UI.SceneManager.class.getResource("/styles/app.css").toExternalForm());
                    client.UI.ThemeManager.attach(preview); preview.resize(1000, 650); preview.applyCss(); preview.layout();
                    var snapshot = preview.snapshot(null, null); int width = (int) snapshot.getWidth(), height = (int) snapshot.getHeight();
                    var output = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    int[] pixels = new int[width * height]; snapshot.getPixelReader().getPixels(0, 0, width, height,
                            javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, width);
                    output.setRGB(0, 0, width, height, pixels, 0, width); Files.createDirectories(Path.of("target"));
                    javax.imageio.ImageIO.write(output, "png", Path.of("target/video-call-generated-preview.png").toFile()); preview.dispose(); return null;
                });
                controllers.get(1).stopCamera().get(8, TimeUnit.SECONDS);
                String stopped = sessions.get(1).selfParticipantId();
                FxTestRuntime.await(() -> controllers.get(1).frames(stopped).imageProperty().get() == null);
                var mediaState = sessions.get(0).videoRequest("VIDEO_STATE", new com.google.gson.JsonObject(), UUID.randomUUID().toString()).get(8, TimeUnit.SECONDS);
                assertFalse(mediaState.getAsJsonObject("sources").has(stopped), "Stopped camera must disappear from routable publications");
                FxTestRuntime.await(() -> controllers.get(0).frames(stopped).imageProperty().get() == null
                        && controllers.get(2).frames(stopped).imageProperty().get() == null);
                long continued = controllers.get(2).frames(sessions.getFirst().selfParticipantId()).receivedFrames();
                Thread.sleep(1500);
                assertTrue(controllers.get(2).frames(sessions.getFirst().selfParticipantId()).receivedFrames() > continued,
                        "Stopping one camera must not stop another publisher/subscriber");
                controllers.get(1).startInput(() -> new GeneratedCamera(1)).get(15, TimeUnit.SECONDS);
                long restarted = controllers.get(0).frames(stopped).receivedFrames();
                long restartDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (controllers.get(0).frames(stopped).receivedFrames() <= restarted + 3 && System.nanoTime() < restartDeadline) Thread.sleep(50);
                assertTrue(controllers.get(0).frames(stopped).receivedFrames() > restarted + 3, "Republish must establish a fresh receiving transport");
                sessions.get(0).stopPublish(stopped, "VIDEO").get(8, TimeUnit.SECONDS);
                FxTestRuntime.await(() -> !controllers.get(1).publishingProperty().get());
                sessions.get(0).kick(stopped).get(8, TimeUnit.SECONDS); controllers.get(1).closeComplete().get(8, TimeUnit.SECONDS);
                FxTestRuntime.await(() -> sessions.get(0).participants().size() == 2 && sessions.get(2).participants().size() == 2);
                String continuingId = sessions.get(2).selfParticipantId();
                sessions.getFirst().leave().get(10, TimeUnit.SECONDS);
                FxTestRuntime.await(() -> sessions.get(2).isHost() && sessions.get(2).participants().size() == 1);
                assertTrue(FxTestRuntime.call(() -> controllers.get(2).publishingProperty().get()), "Host departure must preserve the next host's video publication");
                long afterTransfer = controllers.get(2).frames(continuingId).receivedFrames();
                FxTestRuntime.await(() -> controllers.get(2).frames(continuingId).receivedFrames() > afterTransfer + 3);
                diagnostics.event("video.integration.completed", Map.of("outcome", "pass", "participant_count", 3));
            } finally {
                sessions.forEach(MeetingSession::close);
                for (VideoCallController controller : controllers) controller.closeComplete().get(10, TimeUnit.SECONDS);
            }
        }
    }
    @Test void logoutStopsNativeVideoAndMembershipWhileAnotherCameraContinues() throws Exception {
        try (VideoTestFixture fixture = new VideoTestFixture(tls, temporary.resolve("logout"))) {
            var hostAccount = fixture.database.account("videoHost", "Video Host");
            var guestAccount = fixture.database.account("videoGuest", "Video Guest");
            MeetingSession host = fixture.session(hostAccount), guest = fixture.session(guestAccount);
            VideoCallController hostVideo = null, guestVideo = null;
            try {
                var room = host.create(new CreateRoomParams("Video logout test", "1234", 2, false)).get(8, TimeUnit.SECONDS);
                host.join(false, false).get(8, TimeUnit.SECONDS);
                guest.lookup(room.joinCode(), "1234").get(8, TimeUnit.SECONDS); guest.join(false, false).get(8, TimeUnit.SECONDS);
                hostVideo = host.video(); guestVideo = guest.video();
                hostVideo.startInput(() -> new GeneratedCamera(0)).get(15, TimeUnit.SECONDS);
                guestVideo.startInput(() -> new GeneratedCamera(1)).get(15, TimeUnit.SECONDS);
                String guestId = guest.selfParticipantId();
                VideoCallController capturedHost = hostVideo, capturedGuest = guestVideo;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (hostVideo.frames(guestId).receivedFrames() < 3 && System.nanoTime() < deadline) Thread.sleep(50);
                assertTrue(hostVideo.frames(guestId).receivedFrames() >= 3);
                assertTrue(fixture.database.client().logout(guestAccount.token()).success());
                capturedGuest.closeComplete().get(10, TimeUnit.SECONDS);
                FxTestRuntime.await(() -> host.participants().size() == 1 && guest.roomProperty().get() == null);
                assertTrue(capturedHost.publishingProperty().get(), "Another account's logout must not stop host camera");
            } finally {
                host.close(); guest.close();
                if (hostVideo != null) hostVideo.closeComplete().get(10, TimeUnit.SECONDS);
                if (guestVideo != null) guestVideo.closeComplete().get(10, TimeUnit.SECONDS);
            }
        }
    }
    private static void saveUi(javafx.scene.Parent root, String filename) throws Exception {
        root.applyCss(); root.layout(); var snapshot = root.snapshot(null, null); int width = (int) snapshot.getWidth(), height = (int) snapshot.getHeight();
        int[] pixels = new int[width * height]; snapshot.getPixelReader().getPixels(0, 0, width, height, javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, width);
        var picture = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB); picture.setRGB(0, 0, width, height, pixels, 0, width);
        javax.imageio.ImageIO.write(picture, "png", Path.of("target", filename).toFile());
    }
    private static javafx.scene.control.DialogPane hostLeavePane() {
        return javafx.stage.Window.getWindows().stream().filter(window -> window.getScene() != null)
                .map(window -> window.getScene().getRoot()).filter(javafx.scene.control.DialogPane.class::isInstance).map(javafx.scene.control.DialogPane.class::cast)
                .filter(pane -> pane.lookup("#host-leave-close") != null).findFirst().orElse(null);
    }
    private static void answerCameraPermission(boolean allowed) throws Exception {
        FxTestRuntime.await(() -> javafx.stage.Window.getWindows().stream().anyMatch(window -> window.getScene() != null
                && window.getScene().getRoot() instanceof javafx.scene.control.DialogPane pane
                && pane.getHeaderText() != null && pane.getHeaderText().contains("MiniMeetingRoom")));
        FxTestRuntime.call(() -> {
            var pane = javafx.stage.Window.getWindows().stream().filter(window -> window.getScene() != null)
                    .map(window -> window.getScene().getRoot()).filter(javafx.scene.control.DialogPane.class::isInstance)
                    .map(javafx.scene.control.DialogPane.class::cast).filter(dialog -> dialog.getHeaderText() != null && dialog.getHeaderText().contains("MiniMeetingRoom")).findFirst().orElseThrow();
            var button = pane.getButtonTypes().stream().filter(type -> allowed ? type.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.YES
                    : type.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();
            ((javafx.scene.control.Button) pane.lookupButton(button)).fire(); return null;
        });
    }
    static final class GeneratedCamera implements VideoInput {
        private final CustomVideoSource source = new CustomVideoSource();
        private final ScheduledExecutorService producer = Executors.newSingleThreadScheduledExecutor();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final int color;
        private int frame;
        GeneratedCamera(int color) { this.color = color; }
        public VideoTrackSource source() { return source; }
        public void start() { producer.scheduleAtFixedRate(this::frame, 0, 67, TimeUnit.MILLISECONDS); }
        private synchronized void frame() {
            if (closed.get()) return;
            NativeI420Buffer buffer = NativeI420Buffer.allocate(320, 240);
            try {
                int[] luma = {80, 150, 110}, u = {60, 145, 205}, v = {200, 65, 90};
                fill(buffer.getDataY(), luma[color]); fill(buffer.getDataU(), u[color]); fill(buffer.getDataV(), v[color]);
                int column = (frame++ * 3) % 320;
                for (int y = 0; y < 240; y++) buffer.getDataY().put(y * buffer.getStrideY() + column, (byte) 235);
                source.pushFrame(new VideoFrame(buffer, System.nanoTime()));
            } finally { buffer.release(); }
        }
        private static void fill(ByteBuffer data, int value) { for (int index = 0; index < data.capacity(); index++) data.put(index, (byte) value); }
        public synchronized void close() { if (closed.compareAndSet(false, true)) { producer.shutdownNow(); source.dispose(); } }
    }
}
