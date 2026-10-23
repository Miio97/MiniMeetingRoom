package client.UI;

import client.UI.screens.AuthScreen;
import client.UI.screens.LobbyScreen;
import client.auth.AuthClient;
import client.auth.DeviceSessionStore;
import client.filetransfer.FileTransferClient;
import common.auth.AuthResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import support.FxTestRuntime;
import support.RoomTestFixture;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class DeviceSessionUiTest {
    @TempDir static Path temporary;
    private static RoomTestFixture.Tls tls;
    private RoomTestFixture fixture;
    private DeviceSessionStore store;
    private SceneManager manager;
    private Stage stage;
    private AuthResponse account;
    private final CountDownLatch exited = new CountDownLatch(1), resumeReceived = new CountDownLatch(1), resumeRelease = new CountDownLatch(1);
    private final AtomicBoolean blockResume = new AtomicBoolean();

    @BeforeAll @Timeout(60) static void runtime() throws Exception {
        tls = RoomTestFixture.certificates(temporary); FxTestRuntime.start();
    }
    @BeforeEach void setup() throws Exception {
        fixture = new RoomTestFixture(tls, request -> {
            if ("RESUME_SESSION".equals(request.action()) && blockResume.get()) {
                resumeReceived.countDown();
                try { assertTrue(resumeRelease.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            }
        });
        store = new DeviceSessionStore(temporary.resolve(UUID.randomUUID() + ".properties"));
        account = savedAccount("first", true);
    }
    @AfterEach void cleanup() throws Exception {
        resumeRelease.countDown();
        if (manager != null) FxTestRuntime.call(() -> { manager.shutdown(); stage.hide(); return null; });
        if (fixture != null) fixture.close();
    }
    private AuthResponse savedAccount(String username, boolean active) throws Exception {
        var client = fixture.client();
        assertTrue(client.register(username, "Tên " + username, username + "@example.com", RoomTestFixture.PASSWORD).success());
        var response = client.login(username, RoomTestFixture.PASSWORD, store.clientInfo());
        assertTrue(response.success());
        store.saveSession(response.user(), response.token(), Instant.parse(response.expiresAt()), active);
        return response;
    }
    private void open(AuthClient client) throws Exception {
        FxTestRuntime.call(() -> {
            stage = new Stage();
            manager = new SceneManager(stage, client, new FileTransferClient(), null, exited::countDown, store);
            manager.auth(); return null;
        });
    }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!check(condition) && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue(check(condition));
    }
    private boolean check(BooleanSupplier condition) throws Exception {
        return FxTestRuntime.call(() -> { layout(); return condition.getAsBoolean(); });
    }
    private void layout() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
    private void awaitLobby() throws Exception { await(() -> stage.getScene().getRoot() instanceof LobbyScreen); }
    private void click(String id) throws Exception {
        FxTestRuntime.call(() -> { layout(); ((Button) stage.getScene().getRoot().lookup("#" + id)).fire(); return null; });
    }

    @Test void automaticallyResumesThenSwitchesWithoutRevocationAndLogoutRemovesOnlyCurrentAccount() throws Exception {
        var second = savedAccount("second", false);
        open(fixture.client()); awaitLobby();
        assertEquals(account.token(), FxTestRuntime.call(manager::sessionToken));
        FxTestRuntime.call(() -> { manager.switchAccount(); return null; });
        await(() -> stage.getScene().getRoot() instanceof AuthScreen);
        assertTrue(store.activeSession().isEmpty()); assertEquals(2, store.listSessions().size());
        assertTrue(fixture.client().resumeSession(account.token(), store.clientInfo()).success());
        click("auth-resume-" + second.user().id()); awaitLobby();
        assertEquals(second.token(), FxTestRuntime.call(manager::sessionToken));
        FxTestRuntime.call(() -> { manager.logout(); return null; });
        await(() -> stage.getScene().getRoot() instanceof AuthScreen);
        assertEquals("first", store.listSessions().getFirst().username());
        assertEquals("SESSION_EXPIRED", fixture.client().resumeSession(second.token(), store.clientInfo()).code());
        assertTrue(fixture.client().resumeSession(account.token(), store.clientInfo()).success());
    }

    @Test void windowCloseKeepsSavedLoginActiveOnServerAndDisk() throws Exception {
        open(fixture.client()); awaitLobby();
        FxTestRuntime.call(() -> { manager.closeApplication(); return null; });
        assertTrue(exited.await(10, TimeUnit.SECONDS));
        assertEquals(account.token(), store.activeSession().orElseThrow().token());
        assertTrue(fixture.client().resumeSession(account.token(), store.clientInfo()).success());
    }

    @Test void switchingAccountsLeavesTheJoinedRoomBeforeClearingMemoryAndKeepsTheLogin() throws Exception {
        open(fixture.client()); awaitLobby();
        var created = fixture.client().createRoom(account.token(), "Phòng chuyển tài khoản", "1234", 3);
        assertTrue(created.success());
        FxTestRuntime.call(() -> { manager.preJoin(created.room()); manager.switchAccount(); return null; });
        await(() -> stage.getScene().getRoot() instanceof AuthScreen && !manager.logoutPendingProperty().get());
        assertNull(FxTestRuntime.call(manager::sessionToken)); assertFalse(FxTestRuntime.call(manager::isRoomJoined));
        try (var connection = fixture.connection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL")) {
            assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
        }
        assertTrue(store.activeSession().isEmpty()); assertEquals(1, store.listSessions().size());
        assertTrue(fixture.client().resumeSession(account.token(), store.clientInfo()).success());
    }

    @Test void closingDuringRestoreDoesNotRevokeTheSavedTokenAsAnUnacceptedNewLogin() throws Exception {
        blockResume.set(true); open(fixture.client());
        assertTrue(resumeReceived.await(5, TimeUnit.SECONDS));
        FxTestRuntime.call(() -> { manager.closeApplication(); return null; });
        resumeRelease.countDown(); assertTrue(exited.await(10, TimeUnit.SECONDS)); blockResume.set(false);
        assertTrue(fixture.client().resumeSession(account.token(), store.clientInfo()).success());
        assertEquals(account.token(), store.activeSession().orElseThrow().token());
    }

    @Test void expiredSessionDuringAccountSwitchClearsPendingStateAndTheSavedToken() throws Exception {
        open(fixture.client()); awaitLobby();
        var created = fixture.client().createRoom(account.token(), "Phòng phiên hết hạn", "1234", 3);
        assertTrue(created.success()); fixture.client().logout(account.token());
        FxTestRuntime.call(() -> { manager.preJoin(created.room()); manager.switchAccount(); return null; });
        await(() -> stage.getScene().getRoot() instanceof AuthScreen && !manager.logoutPendingProperty().get());
        assertTrue(store.listSessions().isEmpty());
    }

    @Test void removingASavedAccountRevokesOnlyItsToken() throws Exception {
        var second = savedAccount("second", false); store.clearActiveAccount();
        open(fixture.client());
        click("auth-remove-" + account.user().id());
        await(() -> !((Button) stage.getScene().getRoot().lookup("#auth-submit")).isDisabled());
        assertEquals(1, store.listSessions().size()); assertEquals("second", store.listSessions().getFirst().username());
        assertEquals("SESSION_EXPIRED", fixture.client().resumeSession(account.token(), store.clientInfo()).code());
        assertTrue(fixture.client().resumeSession(second.token(), store.clientInfo()).success());
    }

    @Test void revokedAutomaticSessionIsRemovedWithoutRemovingOtherAccounts() throws Exception {
        savedAccount("second", false); fixture.client().logout(account.token());
        open(fixture.client());
        await(() -> stage.getScene().getRoot() instanceof AuthScreen
                && !((Button) stage.getScene().getRoot().lookup("#auth-submit")).isDisabled()
                && ((Label) stage.getScene().getRoot().lookup("#auth-status")).isVisible());
        assertTrue(store.activeSession().isEmpty()); assertEquals("second", store.listSessions().getFirst().username());
    }

    @Test void temporaryNetworkFailureKeepsTheSavedAccountAvailableForRetry() throws Exception {
        open(new AuthClient("127.0.0.1", 0, tls.client(), 200));
        await(() -> stage.getScene().getRoot() instanceof AuthScreen
                && ((Label) stage.getScene().getRoot().lookup("#auth-status")).isVisible());
        assertEquals(account.token(), store.activeSession().orElseThrow().token());
        assertNotNull(FxTestRuntime.call(() -> stage.getScene().getRoot().lookup("#auth-resume-" + account.user().id())));
    }
}
