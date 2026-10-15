package client.UI;

import client.UI.components.ChatPanel;
import client.auth.AuthClient;
import client.filetransfer.FileTransferClient;
import common.auth.AuthResponse;
import common.auth.AuthUser;
import common.room.RoomInfo;
import java.time.Instant;
import java.time.Clock;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Stream;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import support.FxTestRuntime;
import support.RoomUiAssertions;
import support.RoomTestFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Displayed times follow Vietnam even when the client JVM uses a different timezone. */
@Timeout(25)
@ResourceLock("jvm-default-time-zone")
class VietnamTimeUiTest {
    @TempDir static Path temporary;
    private static RoomTestFixture.Tls tls;
    private SceneManager manager;
    private Stage stage;
    private ChatPanel chatPanel;

    @BeforeAll
    static void runtime() throws Exception { FxTestRuntime.start(); tls = RoomTestFixture.certificates(temporary); }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "America/Los_Angeles"})
    void lobbyDisplaysVietnamDateAcrossMidnightInsteadOfRawUtc(String defaultZone) throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(defaultZone));
            FxTestRuntime.call(() -> {
                stage = new Stage();
                FileTransferClient unusedFiles = new FileTransferClient(); unusedFiles.close();
                manager = new SceneManager(stage, new AuthClient(), unusedFiles);
                manager.acceptSession(AuthResponse.ok("Test session", new AuthUser(1, "TimeHost", "Chủ phòng", null),
                        "ui-time-test-token", Instant.now().plusSeconds(3600).toString()));
                RoomInfo room = new RoomInfo(73, "Phòng kiểm tra giờ Việt Nam", "MMR-ABCD1234", 1,
                        "2026-10-15T18:05:00Z", "DA_DONG", false, 3, 0, List.of());
                manager.cacheRecentRooms(List.of(room));
                manager.lobby();
                Parent lobby = stage.getScene().getRoot(); lobby.applyCss(); lobby.layout();
                List<String> labels = nodes(lobby).filter(Label.class::isInstance).map(Label.class::cast)
                        .filter(Label::isVisible).map(Label::getText).toList();
                assertTrue(labels.stream().anyMatch(text -> text.contains("16/10/2026 01:05")), labels::toString);
                assertFalse(labels.stream().anyMatch(text -> text.contains("2026-10-15T18:05:00Z")), labels::toString);
                RoomUiAssertions.noRoomIds(lobby, room.id());
                assertFalse(stage.isShowing());
                return null;
            });
        } finally {
            try { closeHiddenWindow(); }
            finally { TimeZone.setDefault(original); }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "America/Los_Angeles"})
    void submittingChatUsesCurrentVietnamTimeUnderAnotherDefaultZone(String defaultZone) throws Exception {
        TimeZone original = TimeZone.getDefault();
        try (RoomTestFixture fixture = new RoomTestFixture(tls);
                var rooms = new server.meeting.RoomService(fixture::connection, Clock.systemUTC());
                var server = new server.AuthServer(tls.server(), new InetSocketAddress("127.0.0.1", 0),
                        request -> AuthResponse.error("INVALID_REQUEST", "Unused auth route"), rooms)) {
            TimeZone.setDefault(TimeZone.getTimeZone(defaultZone));
            fixture.execute("CREATE TABLE Tin_Nhan (Ma_Tin_Nhan BIGINT AUTO_INCREMENT PRIMARY KEY, Ma_Phong BIGINT NOT NULL, Ma_Nguoi_Gui BIGINT NOT NULL, Loai_Tin_Nhan VARCHAR(20), Noi_Dung TEXT, Ngay_Gui TIMESTAMP NOT NULL, Ngay_Xoa TIMESTAMP)");
            server.start();
            var account = fixture.account("TimeChatHost", "Khoa");
            var transport = new AuthClient("localhost", server.port(), tls.client(), 5000);
            var joined = transport.meeting(common.meeting.RoomRequest.create(account.token(), "Giờ chat", "1234", 3));
            FxTestRuntime.call(() -> {
                stage = new Stage(); manager = new SceneManager(stage, transport, new FileTransferClient());
                manager.acceptSession(account); manager.updateMeeting(joined);
                manager.updateRoom(new RoomInfo(joined.room().id(), joined.room().name(), joined.room().code(), account.user().id(),
                        Instant.now().toString(), "DANG_HOAT_DONG", false, 3, 1, List.of()));
                chatPanel = new ChatPanel(manager);
                stage.setScene(new Scene(chatPanel, 420, 540)); chatPanel.applyCss(); chatPanel.layout();
                return null;
            });
            FxTestRuntime.await(() -> !((javafx.scene.control.Button) chatPanel.lookup("#chat-send")).isDisabled());
            String before = VietnamTime.timeNow();
            FxTestRuntime.call(() -> {
                ChatPanel chat = chatPanel;
                TextField input = nodes(chat).filter(TextField.class::isInstance).map(TextField.class::cast)
                        .findFirst().orElseThrow();
                String message = "Tin nhắn kiểm tra giờ Việt Nam";
                input.setText(message);
                input.fireEvent(new ActionEvent());
                return null;
            });
            FxTestRuntime.await(() -> nodes(chatPanel).filter(Label.class::isInstance).map(Label.class::cast)
                    .anyMatch(label -> "Tin nhắn kiểm tra giờ Việt Nam".equals(label.getText())));
            String after = VietnamTime.timeNow();
            FxTestRuntime.call(() -> {
                ChatPanel chat = chatPanel;
                String message = "Tin nhắn kiểm tra giờ Việt Nam";
                Label bubble = nodes(chat).filter(Label.class::isInstance).map(Label.class::cast)
                        .filter(label -> message.equals(label.getText())).findFirst().orElseThrow();
                Label caption = bubble.getParent().getChildrenUnmodifiable().stream()
                        .filter(Label.class::isInstance).map(Label.class::cast)
                        .filter(label -> label != bubble).findFirst().orElseThrow();
                assertTrue(List.of(before, after).stream().anyMatch(time -> caption.getText().equals("Bạn  ·  " + time)),
                        caption::getText);
                assertEquals("", ((TextField) chat.lookup("#chat-input")).getText());
                RoomUiAssertions.noRoomIds(chat, 73);
                assertFalse(stage.isShowing());
                return null;
            });
        } finally {
            try { closeHiddenWindow(); }
            finally { TimeZone.setDefault(original); }
        }
    }

    private void closeHiddenWindow() throws Exception {
        FxTestRuntime.call(() -> {
            if (chatPanel != null) { chatPanel.dispose(); chatPanel = null; }
            if (manager != null) manager.shutdown();
            if (stage != null) stage.hide();
            return null;
        });
    }

    private static Stream<Node> nodes(Node node) {
        if (node instanceof ScrollPane scroll && scroll.getContent() != null)
            return Stream.concat(Stream.of(node), nodes(scroll.getContent()));
        return node instanceof Parent parent ? Stream.concat(Stream.of(node),
                parent.getChildrenUnmodifiable().stream().flatMap(VietnamTimeUiTest::nodes)) : Stream.of(node);
    }
}
