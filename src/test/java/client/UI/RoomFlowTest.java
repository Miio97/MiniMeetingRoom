package client.UI;

import client.UI.dialogs.CreateRoomDialog;
import client.UI.screens.LobbyScreen;
import client.UI.screens.MeetingScreen;
import client.UI.screens.PreJoinScreen;
import client.filetransfer.FileTransferClient;
import common.auth.AuthResponse;
import common.room.RoomInfo;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import support.FxTestRuntime;
import support.RoomTestFixture;
import server.filetransfer.FileMetadataRepository;
import server.filetransfer.FileStorageService;
import server.filetransfer.FileTransferServer;
import server.filetransfer.TransferConfig;
import static org.junit.jupiter.api.Assertions.*;

/** Existing forms drive production room commands through TLS without opening visible windows. */
@Timeout(30)
class RoomFlowTest {
    @TempDir static Path temporary;
    private static RoomTestFixture.Tls tls;
    private RoomTestFixture fixture;
    private FileStorageService fileStorage;
    private FileTransferServer fileServer;
    private FileTransferClient fileClient;
    private SceneManager manager;
    private Stage stage;
    private CreateRoomDialog dialog;
    private AuthResponse host;
    private final List<String> actions = new CopyOnWriteArrayList<>();
    private final CountDownLatch blockedRequestReceived = new CountDownLatch(1);
    private final CountDownLatch blockedRequestRelease = new CountDownLatch(1);
    private volatile String blockedAction;

    @BeforeAll @Timeout(60)
    static void runtime() throws Exception {
        tls = RoomTestFixture.certificates(temporary);
        FxTestRuntime.start();
    }

    @BeforeEach
    void server() throws Exception {
        fixture = new RoomTestFixture(tls, request -> {
            actions.add(request.action());
            if (request.action().equals(blockedAction)) {
                blockedRequestReceived.countDown();
                try {
                    if (!blockedRequestRelease.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("The UI test did not release the room response");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(exception);
                }
            }
        });
        host = fixture.account("HostUser", "Chủ phòng Khoa");
        startFileService();
    }

    private void startFileService() throws Exception {
        // The meeting screen starts file polling even when the test only exercises room controls.
        // Keep that traffic on the same temporary database and TLS certificates as the room fixture.
        String schema = Files.readString(Path.of("database/schema.sql"))
                .replaceAll("(?m)^\\s*--.*$", "").replace("ENGINE = InnoDB", "");
        for (String sql : schema.split(";")) {
            String statement = sql.strip();
            if (statement.startsWith("CREATE TABLE Tin_Nhan") || statement.startsWith("CREATE TABLE Tep_Dinh_Kem")) {
                fixture.execute(statement);
            }
        }
        TransferConfig config = new TransferConfig(true, "127.0.0.1", 0, 65536, 2, 1, 5000,
                Files.createTempDirectory(temporary, "room-ui-storage-"), 1024 * 1024, 8 * 1024 * 1024,
                2, Duration.ofHours(1), true);
        fileStorage = new FileStorageService(config.storagePath(), config.quotaBytes(), config.maxFileBytes(),
                config.maxPendingPerUser(), config.expiry(), config.resumeEnabled());
        fileServer = new FileTransferServer(tls.server(), config, new FileMetadataRepository(fixture::connection), fileStorage);
        fileServer.start();
        fileClient = new FileTransferClient("localhost", fileServer.port(), tls.client(), 5000);
    }

    private void hiddenClient(AuthResponse session) throws Exception {
        FxTestRuntime.call(() -> {
            stage = new Stage(); manager = new SceneManager(stage, fixture.client(), fileClient);
            manager.acceptSession(session); manager.lobby();
            root().applyCss(); root().layout(); assertFalse(stage.isShowing()); return null;
        });
        FxTestRuntime.await(() -> !node(root(), "refresh-rooms", Button.class).isDisabled());
    }

    @AfterEach
    void cleanup() throws Exception {
        blockedRequestRelease.countDown();
        try {
            if (manager != null) FxTestRuntime.call(() -> {
                if (dialog != null) dialog.close(); manager.shutdown(); stage.hide(); return null;
            });
        } finally {
            try {
                if (fileClient != null) fileClient.close();
                if (fileServer != null) fileServer.close();
                else if (fileStorage != null) fileStorage.close(); // Constructor failure still releases the storage lock.
            } finally { if (fixture != null) fixture.close(); }
        }
    }

    @Test
    void createDialogValidatesLocallyWaitsWithoutBlockingAndUsesServerMetadata() throws Exception {
        hiddenClient(host);
        FxTestRuntime.call(() -> {
            dialog = new CreateRoomDialog(manager);
            dialog.getDialogPane().applyCss(); dialog.getDialogPane().layout();
            assertFalse(dialog.isShowing());
            TextField name = node(dialog.getDialogPane(), "room-name", TextField.class);
            TextField pin = node(dialog.getDialogPane(), "room-pin", TextField.class);
            Button create = node(dialog.getDialogPane(), "create-room-submit", Button.class);
            name.setText(""); pin.setText("1234"); create.fire();
            assertFalse(node(dialog.getDialogPane(), "create-room-error", Label.class).getText().isBlank());
            name.setText("Họp nhóm JavaFX"); pin.setText("12ab"); create.fire();
            assertFalse(node(dialog.getDialogPane(), "create-room-error", Label.class).getText().isBlank());
            assertFalse(actions.contains("CREATE_ROOM"), "Invalid room forms must never reach the server");
            pin.setText("654321"); return null;
        });
        snapshot("room-create.png", dialog.getDialogPane(), 460, 560);
        blockedAction = "CREATE_ROOM";
        FxTestRuntime.call(() -> {
            node(dialog.getDialogPane(), "create-room-submit", Button.class).fire();
            assertTrue(node(dialog.getDialogPane(), "create-room-submit", Button.class).isDisabled());
            assertTrue(node(dialog.getDialogPane(), "room-name", TextField.class).isDisabled());
            assertNull(dialog.getResult()); return null;
        });
        assertTrue(blockedRequestReceived.await(5, TimeUnit.SECONDS));
        // A round trip to the FX thread succeeds while the TLS server deliberately waits.
        FxTestRuntime.call(() -> { assertNull(dialog.getResult()); assertFalse(stage.isShowing()); return null; });
        blockedRequestRelease.countDown();
        FxTestRuntime.await(() -> dialog.getResult() != null);
        RoomInfo created = FxTestRuntime.call(() -> dialog.getResult());
        assertEquals(host.user().id(), created.hostId()); assertTrue(created.id() > 0);
        assertEquals(1, created.people()); assertEquals("Họp nhóm JavaFX", created.name());
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM Phong_Hop"));
        FxTestRuntime.call(() -> {
            assertEquals("", node(dialog.getDialogPane(), "room-pin", TextField.class).getText());
            manager.preJoin(created); assertTrue(root() instanceof PreJoinScreen);
            assertTrue(hasLabel(root(), created.code())); assertTrue(hasLabel(root(), "ID " + created.id()));
            assertTrue(manager.isRoomJoined()); assertEquals("", manager.room().pin()); return null;
        });
        snapshot("room-prejoin.png", root(), 1100, 700);
        FxTestRuntime.call(() -> { node(root(), "enter-meeting", Button.class).fire(); return null; });
        FxTestRuntime.await(() -> root() instanceof MeetingScreen);
        FxTestRuntime.await(() -> {
            TabPane tabs = nodes(root()).filter(TabPane.class::isInstance).map(TabPane.class::cast).findFirst().orElseThrow();
            return hasLabel((Parent) tabs.getTabs().get(2).getContent(), "Chưa có tệp trong phòng.");
        });
        FxTestRuntime.call(() -> {
            assertTrue(hasLabel(root(), "1/10 người")); assertFalse(hasLabel(root(), "UI DEMO"));
            assertFalse(stage.isShowing()); return null;
        });
        snapshot("room-meeting.png", root(), 1280, 800);
    }

    @Test void returningToLobbyReusesRecentListAndManualRefreshStillQueriesServer() throws Exception {
        hiddenClient(host);
        long requests = actions.stream().filter("LIST_ROOMS"::equals).count();
        FxTestRuntime.call(() -> { manager.lobby(); root().applyCss(); root().layout(); return null; });
        assertEquals(requests, actions.stream().filter("LIST_ROOMS"::equals).count());
        FxTestRuntime.call(() -> { node(root(), "refresh-rooms", Button.class).fire(); return null; });
        FxTestRuntime.await(() -> !node(root(), "refresh-rooms", Button.class).isDisabled());
        assertEquals(requests + 1, actions.stream().filter("LIST_ROOMS"::equals).count());
    }

    @Test void blockedBackgroundListDoesNotDelayForegroundRoomAction() throws Exception {
        var room = fixture.client().createRoom(host.token(), "Responsive room", "1234", 3).room();
        hiddenClient(host); blockedAction = "LIST_ROOMS";
        FxTestRuntime.call(() -> { node(root(), "refresh-rooms", Button.class).fire(); return null; });
        assertTrue(blockedRequestReceived.await(3, TimeUnit.SECONDS));
        CountDownLatch foreground = new CountDownLatch(1);
        FxTestRuntime.call(() -> {
            manager.runAuthRequest(() -> fixture.client().getRoom(host.token(), room.id()), response -> {
                if (response.success()) foreground.countDown();
            }, failure -> { });
            return null;
        });
        assertTrue(foreground.await(3, TimeUnit.SECONDS), "A polling response must not queue ahead of the user's action");
        blockedRequestRelease.countDown();
    }

    @Test
    void lobbyRejectsWrongPinJoinsRealRoomByIdAndBackButtonRemovesMembership() throws Exception {
        RoomInfo room = fixture.client().createRoom(host.token(), "Họp hai máy", "7642", 10).room();
        AuthResponse guest = fixture.account("GuestUser", "Khách tham gia");
        hiddenClient(guest);
        blockedAction = "JOIN_ROOM";
        FxTestRuntime.call(() -> {
            node(root(), "join-room-identifier", TextField.class).setText(Long.toString(room.id()));
            node(root(), "join-room-pin", TextField.class).setText("9999");
            node(root(), "join-room-submit", Button.class).fire();
            assertTrue(root().isDisabled()); return null;
        });
        assertTrue(blockedRequestReceived.await(5, TimeUnit.SECONDS));
        FxTestRuntime.call(() -> { assertTrue(root() instanceof LobbyScreen); assertTrue(root().isDisabled()); return null; });
        blockedRequestRelease.countDown();
        FxTestRuntime.await(() -> root() instanceof LobbyScreen && !root().isDisabled()
                && node(root(), "join-room-error", Label.class).getText().contains("PIN phòng không đúng"));
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
        snapshot("rooms-lobby.png", root(), 1100, 700);
        Parent oldLobby = FxTestRuntime.call(this::root);
        FxTestRuntime.call(() -> {
            node(root(), "join-room-pin", TextField.class).setText("7642");
            node(root(), "join-room-submit", Button.class).fire(); return null;
        });
        FxTestRuntime.await(() -> root() instanceof PreJoinScreen);
        FxTestRuntime.call(() -> {
            assertEquals(room.id(), manager.roomInfo().id()); assertEquals(2, manager.roomInfo().people());
            assertEquals("", node(oldLobby, "join-room-pin", TextField.class).getText());
            assertTrue(manager.isRoomJoined()); assertFalse(stage.isShowing()); return null;
        });
        assertEquals(2, fixture.client().getRoom(host.token(), room.id()).room().people());
        FxTestRuntime.call(() -> { node(root(), "cancel-prejoin", Button.class).fire(); return null; });
        FxTestRuntime.await(() -> root() instanceof LobbyScreen && !manager.isRoomJoined());
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
        FxTestRuntime.await(() -> !node(root(), "refresh-rooms", Button.class).isDisabled());
        FxTestRuntime.call(() -> {
            assertTrue(hasLabel(root(), room.name())); assertTrue(hasLabel(root(), room.code()));
            assertFalse(stage.isShowing()); return null;
        });
    }

    private Parent root() { return stage.getScene().getRoot(); }

    private static <T extends Node> T node(Parent root, String id, Class<T> type) {
        return nodes(root).filter(candidate -> id.equals(candidate.getId())).map(type::cast).findFirst()
                .orElseThrow(() -> new AssertionError("Missing UI node: " + id));
    }

    private static boolean hasLabel(Parent root, String text) {
        return nodes(root).filter(Label.class::isInstance).map(Label.class::cast)
                .anyMatch(label -> label.isVisible() && label.getText().contains(text));
    }

    private static Stream<Node> nodes(Node node) {
        // A hidden screen may not have a ScrollPane skin yet. Its content still represents
        // the live form, even before CSS attaches that content to the skin's viewport.
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            return Stream.concat(Stream.of(node), nodes(scroll.getContent()));
        }
        return node instanceof Parent parent
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream().flatMap(RoomFlowTest::nodes))
                : Stream.of(node);
    }

    private static void snapshot(String filename, Parent screen, int width, int height) throws Exception {
        WritableImage image = FxTestRuntime.call(() -> {
            screen.resize(width, height); screen.applyCss(); screen.layout(); screen.setOpacity(1);
            return screen.snapshot(new SnapshotParameters(), null);
        });
        BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) {
            png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        }
        Path output = Path.of("target", "qa", filename); Files.createDirectories(output.getParent());
        assertTrue(ImageIO.write(png, "png", output.toFile()));
    }
}
