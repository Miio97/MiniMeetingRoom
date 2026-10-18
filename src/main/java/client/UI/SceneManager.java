package client.UI;

import client.UI.mock.MockData;
import client.UI.screens.*;
import client.UI.components.Ui;
import client.auth.AuthClient;
import client.filetransfer.FileTransferClient;
import common.auth.AuthResponse;
import common.auth.AuthUser;
import common.room.RoomInfo;
import common.meeting.*;
import common.chat.*;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.io.IOException;
import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Điều hướng và dừng animation của màn hình cũ, tránh Timeline chạy ngầm. */
public final class SceneManager {
    private boolean legacyMeetingUi;
    private final Stage stage;
    private Runnable cleanup = () -> { };
    private ParallelTransition transition;
    private final AuthClient authClient;
    private final FileTransferClient fileTransferClient;
    private final ExecutorService authWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread worker = new Thread(runnable, "mini-meeting-auth"); worker.setDaemon(true); return worker;
    });
    private final java.util.concurrent.ThreadPoolExecutor roomReadWorker = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(2),
            Thread.ofPlatform().daemon().name("mini-meeting-room-read").factory());
    private java.util.List<RoomInfo> recentRooms;
    private long recentRoomsAt;
    public java.util.List<RoomInfo> recentRooms() {
        return recentRooms != null && System.nanoTime() - recentRoomsAt < java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
                ? recentRooms : null;
    }
    public void cacheRecentRooms(java.util.List<RoomInfo> rooms) {
        recentRooms = java.util.List.copyOf(rooms); recentRoomsAt = System.nanoTime();
    }
    private final ReadOnlyBooleanWrapper logoutPending = new ReadOnlyBooleanWrapper();
    private client.UI.dialogs.AccountPasswordResetDialog passwordRecovery;
    private AuthUser authenticatedUser;
    private String sessionToken;
    private java.time.Instant sessionExpiresAt;
    private client.meeting.MeetingSession sfuSession;
    private final java.util.function.BiFunction<String, java.time.Instant, client.meeting.MeetingSession> meetingFactory;
    private boolean usingSfuRoom, leavingSfuRoom;
    private final javafx.beans.value.ChangeListener<common.meeting.RoomSnapshot> meetingListener = (observable, before, after) -> meetingChanged(after);
    private void meetingChanged(common.meeting.RoomSnapshot after) {
        if (!usingSfuRoom || closing) return;
        if (after != null && after.collaborationRoom() != null) updateRoom(after.collaborationRoom());
        else if (after == null && !leavingSfuRoom && roomJoined) {
            if (sfuSession != null) endReason = sfuSession.noticeProperty().get();
            int count = roomInfo == null ? 0 : roomInfo.people();
            long seconds = sfuSession == null ? 0 : sfuSession.elapsedSeconds();
            roomJoined = false; pendingRoomId = -1; usingSfuRoom = false; invitationPin = "";
            ended(seconds, count);
        }
    }
    private volatile String pendingToken;
    private volatile long pendingRoomId = -1;
    private volatile boolean closing;
    private volatile boolean closed;
    private PauseTransition closeDeadline;
    private MockData.Room room = MockData.rooms().getFirst();
    private RoomInfo roomInfo;
    private boolean roomJoined;
    private volatile RoomResponse meetingState;
    private String endReason = "";
    private String invitationPin = "";
    public String invitationPin() { return invitationPin; }
    public String endReason() { return endReason; }
    public RoomResponse meetingState() { return meetingState; }
    public void enterRoom(RoomResponse response, String pin) {
        if (!hasSession() || response.pending() || response.room() == null) return;
        updateMeeting(response);
        roomInfo = new RoomInfo(response.room().id(), response.room().name(), response.room().code(), response.members().stream().filter(Participant::host).mapToLong(Participant::userId).findFirst().orElse(authenticatedUser.id()),
                java.time.Instant.now().toString(), "DANG_HOAT_DONG", response.room().locked(), response.room().capacity(), response.members().size(),
                response.members().stream().map(person -> new common.room.RoomParticipant(person.userId(), person.name(), person.host() ? "CHU_PHONG" : "THANH_VIEN")).toList());
        room = new MockData.Room(response.room().name(), response.room().code(), Math.max(1, response.members().size()), "", pin, response.room().capacity());
        meeting();
    }
    public void updateMeeting(RoomResponse response) {
        meetingState = response; locked = response.room().locked();
        response.members().stream().filter(person -> person.userId() == authenticatedUser.id()).findFirst().ifPresent(person -> { mic = person.microphone(); camera = person.camera(); });
    }
    public RoomRequest roomCommand(String action, long target) {
        if (hasVideoRoom()) return RoomRequest.command(action, sessionToken, roomInfo.id(), sfuSession.selfParticipantId(), target);
        RoomResponse state = meetingState;
        return RoomRequest.command(action, sessionToken, state.room().id(), state.ticket(), target);
    }
    public void runMeetingRequest(RoomRequest request, Consumer<RoomResponse> success, Consumer<Throwable> failure) {
        if (closing) return;
        authWorker.execute(() -> {
            try {
                RoomResponse response = authClient.meeting(request);
                Platform.runLater(() -> {
                    if (!closing) success.accept(response);
                    else releaseRoom(response, request.token());
                });
            }
            catch (Exception error) { Platform.runLater(() -> { if (!closing) failure.accept(error); }); }
        });
    }
    public void leaveRoomQuietly() {
        RoomResponse state = meetingState; String token = sessionToken; meetingState = null;
        releaseRoom(state, token);
    }
    public void runChatRequest(ChatRequest request, Consumer<ChatResponse> success, Consumer<Throwable> failure) {
        if (closing) return;
        authWorker.execute(() -> {
            try { ChatResponse response = authClient.chat(request); Platform.runLater(() -> { if (!closing) success.accept(response); }); }
            catch (Exception error) { Platform.runLater(() -> { if (!closing) failure.accept(error); }); }
        });
    }
    public void releaseRoom(RoomResponse state, String token) {
        if (state != null && state.room() != null && !state.room().closed()
                && state.ticket() != null && !authWorker.isShutdown()) authWorker.execute(() -> {
            try { authClient.meeting(RoomRequest.command("ROOM_LEAVE", token, state.room().id(), state.ticket(), 0)); } catch (IOException ignored) { }
        });
    }
    private boolean mic = true, camera = true, locked;
    private final java.util.Map<String, String> preferences = new java.util.HashMap<>();
    public String preference(String key, String fallback) { return preferences.getOrDefault(key, fallback); }
    public void preferenceSet(String key, String value) { preferences.put(key, value); }
    public SceneManager(Stage stage) { this(stage, new AuthClient(), new FileTransferClient(), client.meeting.MeetingSession::new); }
    SceneManager(Stage stage, AuthClient authClient) { this(stage, authClient, new FileTransferClient()); }
    SceneManager(Stage stage, AuthClient authClient, boolean legacy) { this(stage, authClient); legacyMeetingUi = legacy; }
    SceneManager(Stage stage, AuthClient authClient, FileTransferClient fileTransferClient) {
        // Explicitly injected legacy transport retained for compatibility tests and old rooms.
        this(stage, authClient, fileTransferClient, null);
    }
    public SceneManager(Stage stage, AuthClient authClient, FileTransferClient fileTransferClient,
            java.util.function.BiFunction<String, java.time.Instant, client.meeting.MeetingSession> meetingFactory) {
        this.stage = java.util.Objects.requireNonNull(stage);
        this.authClient = java.util.Objects.requireNonNull(authClient);
        this.fileTransferClient = java.util.Objects.requireNonNull(fileTransferClient);
        this.meetingFactory = meetingFactory;
    }
    public Stage stage() { return stage; }
    public AuthClient authClient() { return authClient; }
    public FileTransferClient fileTransferClient() { return fileTransferClient; }
    public String sessionToken() { return sessionToken; }
    public RoomInfo roomInfo() { return roomInfo; }
    /** File sharing uses the same authoritative room snapshot as the room controls. */
    public RoomInfo serverRoom() { return roomJoined || meetingState != null ? roomInfo : null; }
    public boolean isRoomJoined() { return roomJoined; }
    public boolean sessionExpired(AuthResponse response) {
        if (!"SESSION_EXPIRED".equals(response.code())) return false;
        for (javafx.stage.Window owned : java.util.List.copyOf(javafx.stage.Window.getWindows())) {
            if ((owned instanceof Stage dialog && dialog.getOwner() == stage)
                    || (owned instanceof javafx.stage.PopupWindow popup && popup.getOwnerWindow() == stage)) owned.hide();
        }
        clearSession(); auth(); return true;
    }
    /** Cập nhật phần trình bày từ snapshot của server; không giữ PIN trên client. */
    public void updateRoom(RoomInfo next) {
        recentRooms = null;
        roomInfo = java.util.Objects.requireNonNull(next);
        room = new MockData.Room(next.name(), next.code(), Math.max(1, next.people()), next.createdAt(), "", next.capacity());
        locked = next.locked();
    }
    public void leaveRoom(Runnable success, Consumer<String> failure) {
        if (!roomJoined || roomInfo == null) { success.run(); return; }
        if (usingSfuRoom && sfuSession != null) {
            var session = sfuSession;
            client.UI.dialogs.HostLeaveDialog.Choice choice = null;
            if (session.isHost()) {
                var dialog = new client.UI.dialogs.HostLeaveDialog(stage, session.snapshot(), session.selfParticipantId());
                javafx.beans.value.ChangeListener<common.meeting.RoomSnapshot> changed = (observable, before, after) -> {
                    if (after == null || !session.isHost()) dialog.close();
                };
                session.roomProperty().addListener(changed);
                java.util.Optional<client.UI.dialogs.HostLeaveDialog.Choice> selected;
                try { selected = dialog.showAndWait(); } finally { session.roomProperty().removeListener(changed); }
                if (selected.isEmpty()) { failure.accept(""); return; }
                choice = selected.get();
            }
            if (closing || sfuSession != session || session.snapshot() == null) { failure.accept("Phòng đã thay đổi. Vui lòng thử lại."); return; }
            leavingSfuRoom = true;
            Parent leavingView = stage.getScene().getRoot();
            if (leavingView instanceof MeetingScreen meeting) meeting.pauseFileTransfers();
            var leaving = choice == null ? session.leave(null, false) : session.leave(choice.successorParticipantId(), choice.closeRoom());
            leaving.whenComplete((value, error) -> Platform.runLater(() -> {
                if (closing) return;
                if (error != null && session.snapshot() != null) {
                    session.refresh();
                    leavingSfuRoom = false; restoreRoomFiles(leavingView); failure.accept(connectionError(error)); return;
                }
                leavingSfuRoom = false; roomJoined = false; pendingRoomId = -1;
                closeSfuSession(); success.run();
            }));
            return;
        }
        String token = sessionToken; long id = roomInfo.id();
        Parent leavingView = stage.getScene().getRoot();
        if (leavingView instanceof MeetingScreen meeting) meeting.pauseFileTransfers();
        runAuthRequest(() -> authClient.leaveRoom(token, id), response -> {
            if (response.success()) {
                roomJoined = false; pendingRoomId = -1;
                if (response.room() != null) updateRoom(response.room());
                success.run();
            } else if (!sessionExpired(response)) {
                restoreRoomFiles(leavingView); failure.accept(response.message());
            }
        }, error -> { restoreRoomFiles(leavingView); failure.accept(connectionError(error)); });
    }
    private void restoreRoomFiles(Parent view) {
        if (stage.getScene().getRoot() == view && view instanceof MeetingScreen meeting && roomJoined)
            meeting.restoreFileTransfers();
    }
    public AuthUser authenticatedUser() { return authenticatedUser; }
    public String user() { return authenticatedUser == null ? "" : authenticatedUser.displayName(); }
    public String username() { return authenticatedUser == null ? "" : authenticatedUser.username(); }
    public String email() { return authenticatedUser == null || authenticatedUser.email() == null ? "" : authenticatedUser.email(); }
    private final ReadOnlyBooleanWrapper emailVerified = new ReadOnlyBooleanWrapper(false);
    public boolean emailVerified() { return emailVerified.get(); }
    public ReadOnlyBooleanProperty emailVerifiedProperty() { return emailVerified.getReadOnlyProperty(); }
    public void updateAccount(AuthUser user) {
        if (authenticatedUser == null || user == null || user.id() != authenticatedUser.id())
            throw new IllegalArgumentException("Thông tin tài khoản không khớp phiên đăng nhập.");
        authenticatedUser = user;
        emailVerified.set(user.emailVerified());
    }
    public ReadOnlyBooleanProperty logoutPendingProperty() { return logoutPending.getReadOnlyProperty(); }
    public void acceptSession(AuthResponse response) {
        if (!response.success() || response.user() == null || response.token() == null || response.token().isBlank()) {
            throw new IllegalArgumentException("Phiên đăng nhập không hợp lệ.");
        }
        recentRooms = null; recentRoomsAt = 0;
        authenticatedUser = response.user(); sessionToken = response.token(); pendingToken = null;
        emailVerified.set(authenticatedUser.emailVerified());
        sessionExpiresAt = response.expiresAt() == null ? java.time.Instant.EPOCH : java.time.Instant.parse(response.expiresAt());
    }
    /** Gửi mạng ngoài luồng JavaFX; chỉ cập nhật màn hình khi ứng dụng còn hoạt động. */
    public void runAuthRequest(Callable<AuthResponse> request, Consumer<AuthResponse> success, Consumer<Throwable> failure) {
        if (closing) return;
        // Capture FX-owned session state before the worker starts; window close can clear it concurrently.
        long requestUserId = authenticatedUser == null ? -1 : authenticatedUser.id();
        String requestToken = sessionToken;
        authWorker.execute(() -> {
            try {
                AuthResponse response = request.call();
                if (response.token() != null && !response.token().isBlank()) pendingToken = response.token();
                if (response.success() && response.room() != null && requestUserId > 0
                        && response.room().participants().stream().anyMatch(p -> p.userId() == requestUserId)) {
                    pendingRoomId = response.room().id();
                }
                if (closing) {
                    if (response.success() && response.room() != null) leaveQuietly(requestToken, response.room().id());
                    revokeQuietly(response.token()); pendingToken = null; return;
                }
                Platform.runLater(() -> {
                    if (!closing) success.accept(response);
                });
            } catch (Exception error) {
                if (!closing) Platform.runLater(() -> { if (!closing) failure.accept(error); });
            }
        });
    }
    /** Read-only polling does not queue ahead of login/join/leave/logout actions. */
    public void runBackgroundRoomRequest(Callable<AuthResponse> request, Consumer<AuthResponse> success, Consumer<Throwable> failure) {
        if (closing) return;
        String ownerToken = sessionToken;
        try {
            roomReadWorker.execute(() -> {
                if (closing) return;
                try {
                    AuthResponse response = request.call();
                    Platform.runLater(() -> { if (!closing && java.util.Objects.equals(ownerToken, sessionToken)) success.accept(response); });
                } catch (Exception error) {
                    Platform.runLater(() -> { if (!closing && java.util.Objects.equals(ownerToken, sessionToken)) failure.accept(error); });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) { failure.accept(new IOException("Đang cập nhật phòng. Vui lòng thử lại.")); }
    }
    public static String connectionError(Throwable error) {
        error = unwrap(error);
        if (error instanceof common.signaling.SignalException signal) return switch (signal.code()) {
            case "APPROVAL_PENDING" -> "Đang chờ chủ phòng duyệt.";
            case "ADMISSION_EXPIRED" -> "Yêu cầu chờ đã hết hạn sau 5 phút. Bạn có thể gửi lại.";
            case "ADMISSION_FORBIDDEN" -> "Yêu cầu chờ không thuộc kết nối hiện tại. Hãy tham gia lại.";
            case "SERVER_UPDATE_REQUIRED" -> "Máy chủ đang chạy bản cũ. Hãy cập nhật và khởi động lại server rồi tham gia lại.";
            case "HOST_DECISION_REQUIRED" -> "Bạn đang là chủ phòng. Hãy chọn người kế nhiệm hoặc đóng phòng khi rời.";
            case "PARTICIPANT_NOT_FOUND" -> "Thành viên hoặc yêu cầu chờ không còn tồn tại. Vui lòng chọn lại.";
            case "PIN_RATE_LIMIT" -> "Bạn đã thử PIN quá nhiều lần. Vui lòng chờ một phút rồi thử lại.";
            case "ROOM_NOT_ACTIVE" -> "Phòng đã đóng hoặc không còn hoạt động.";
            case "ADMISSION_REJECTED" -> "Chủ phòng đã từ chối yêu cầu tham gia.";
            case "QUEUE_FULL" -> "Danh sách chờ đã đầy.";
            case "WRONG_PIN" -> "PIN phòng không đúng.";
            case "ROOM_FULL" -> "Phòng đã đủ số người tham gia.";
            case "ROOM_LOCKED" -> "Phòng đang khóa.";
            case "ROOM_NOT_FOUND" -> "Không tìm thấy phòng đang hoạt động.";
            case "ROOM_CLOSED" -> "Phòng đã kết thúc. Chủ phòng có thể mở lại phòng.";
            case "ROOM_ALREADY_ACTIVE" -> "Phòng đã được mở. Hãy làm mới danh sách và tham gia phòng.";
            case "ROOM_NOT_STARTED" -> "Chủ phòng chưa vào phòng. Vui lòng thử lại sau.";
            case "HOST_REQUIRED" -> "Chỉ chủ phòng mới được thực hiện thao tác này.";
            case "HISTORY_UNAVAILABLE" -> "Máy chủ đang lưu trạng thái phòng. Vui lòng thử lại sau.";
            case "REQUEST_ALREADY_ENDED" -> "Lượt mở hoặc tham gia phòng này đã kết thúc. Hãy làm mới danh sách và thử lại.";
            case "REQUEST_EXPIRED" -> "Yêu cầu mở phòng đã hết hạn. Vui lòng thử lại.";
            case "STALE_ROOM" -> "Phòng đã được mở lại. Hãy tham gia lại từ sảnh.";
            case "SESSION_INVALID" -> "Phiên đăng nhập không còn hợp lệ. Hãy đăng nhập lại.";
            case "ALREADY_JOINED", "ALREADY_IN_ROOM" -> "Tài khoản đã tham gia phòng trên kết nối khác.";
            case "INVALID_ROOM" -> "Tên phòng hoặc số người tối đa không hợp lệ.";
            default -> "Kết nối phòng chưa hoàn tất. Kiểm tra máy chủ và thử lại.";
        };
        if (error instanceof SocketTimeoutException) return "Máy chủ phản hồi quá lâu. Vui lòng thử lại.";
        if (error instanceof UnknownHostException) return "Không tìm thấy máy chủ. Kiểm tra địa chỉ trong config/auth-client.properties.";
        if (error instanceof SocketException || error instanceof EOFException) return "Kết nối máy chủ bị gián đoạn. Vui lòng thử lại.";
        if (error instanceof IOException && error.getMessage() != null && !error.getMessage().isBlank()) return error.getMessage();
        return "Không thể kết nối máy chủ. Kiểm tra kết nối và khởi động server rồi thử lại.";
    }
    public void logout() {
        performLogout();
    }
    /** Settings recovery proves the saved email by OTP and keeps the current server session. */
    public void forgotPassword() {
        if (closing || logoutPending.get()) return;
        if (sessionToken == null) { auth(); openPasswordReset(); return; }
        if (passwordRecovery != null && passwordRecovery.isShowing()) return;
        passwordRecovery = new client.UI.dialogs.AccountPasswordResetDialog(this);
        passwordRecovery.show();
    }
    private void openPasswordReset() {
        if (!closing && stage.getScene() != null && stage.getScene().getRoot() instanceof AuthScreen screen)
            screen.showPasswordReset();
    }
    private void performLogout() {
        if (closing || logoutPending.get()) return;
        if (sessionToken == null) { clearSession(); auth(); return; }
        boolean videoWasOpen = hasVideoRoom();
        closeSfuSession();
        logoutPending.set(true); stage.getScene().getRoot().setDisable(true);
        String token = sessionToken;
        long joinedId = roomJoined && roomInfo != null ? roomInfo.id() : -1;
        java.util.concurrent.atomic.AtomicBoolean leftRoom = new java.util.concurrent.atomic.AtomicBoolean();
        leftRoom.set(videoWasOpen);
        // Stop file sockets and polling before the membership/session is revoked.
        dispose();
        runAuthRequest(() -> {
            if (joinedId > 0) {
                AuthResponse leave = authClient.leaveRoom(token, joinedId);
                if (!leave.success()) return leave;
                leftRoom.set(true);
            }
            return authClient.logout(token);
        }, response -> {
            logoutPending.set(false); stage.getScene().getRoot().setDisable(false);
            if (leftRoom.get()) { roomJoined = false; pendingRoomId = -1; }
            if (sessionExpired(response)) return;
            if (response.success()) { clearSession(); auth(); }
            else { restoreAfterLogoutFailure(leftRoom.get()); logoutError(response.message()); }
        }, error -> {
            logoutPending.set(false); stage.getScene().getRoot().setDisable(false);
            if (leftRoom.get()) { roomJoined = false; pendingRoomId = -1; }
            restoreAfterLogoutFailure(leftRoom.get());
            logoutError(connectionError(error));
        });
    }
    private void restoreAfterLogoutFailure(boolean roomLeft) {
        if (roomLeft) { lobby(); return; }
        Parent current = stage.getScene().getRoot();
        if (current instanceof MeetingScreen && roomJoined) meeting();
        else if (current instanceof PreJoinScreen && roomJoined) preJoin(roomInfo);
        else if (current instanceof LobbyScreen) lobby();
        else if (current instanceof SettingsScreen) settingsAccount();
    }
    private void logoutError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR); alert.initOwner(stage); alert.setTitle("Chưa thể đăng xuất");
        alert.setHeaderText("Phiên đăng nhập vẫn được giữ lại.");
        alert.setContentText((message == null ? "Không thể thu hồi phiên trên máy chủ." : message) + "\nVui lòng thử đăng xuất lại.");
        Ui.style(alert); alert.showAndWait();
    }
    private void clearSession() {
        recentRooms = null; recentRoomsAt = 0;
        closeSfuSession(); sessionExpiresAt = null;
        authenticatedUser = null; sessionToken = null; preferences.clear(); meetingState = null;
        emailVerified.set(false);
        roomInfo = null; roomJoined = false; pendingRoomId = -1;
        mic = true; camera = true; locked = false; room = MockData.rooms().getFirst();
    }
    /** Giữ cửa sổ tối đa 6 giây để thử thu hồi phiên trước khi đóng. */
    public void closeApplication() {
        if (closing) return;
        closing = true; closeSfuSession(); dispose();
        if (stage.getScene() != null) {
            stage.getScene().getRoot().setDisable(true);
            if (stage.getScene().getRoot() instanceof AuthScreen auth) auth.clearSecrets();
        }
        closeDeadline = new PauseTransition(Duration.seconds(6)); closeDeadline.setOnFinished(event -> finishClose()); closeDeadline.play();
        String token = sessionToken;
        long joinedId = roomJoined && roomInfo != null ? roomInfo.id() : -1;
        authWorker.execute(() -> {
            leaveQuietly(token, pendingRoomId > 0 ? pendingRoomId : joinedId);
            revokeQuietly(token);
            revokeQuietly(pendingToken); pendingToken = null;
            if (!closed) Platform.runLater(() -> { if (!closed) finishClose(); });
        });
    }
    private void revokeQuietly(String token) {
        if (token == null || token.isBlank()) return;
        try { authClient.logout(token); } catch (IOException ignored) { /* Kết nối có timeout; phiên cũng có hạn dùng phía server. */ }
    }
    private void leaveQuietly(String token, long id) {
        if (token == null || token.isBlank() || id <= 0) return;
        try { authClient.leaveRoom(token, id); } catch (IOException ignored) { }
    }
    private void finishClose() {
        if (closed) return;
        closed = true;
        if (closeDeadline != null) closeDeadline.stop();
        clearSession(); pendingToken = null; authWorker.shutdownNow(); roomReadWorker.shutdownNow(); fileTransferClient.close(); stage.hide(); Platform.exit();
    }
    public void shutdown() {
        closing = true; closed = true; dispose();
        if (closeDeadline != null) closeDeadline.stop();
        clearSession(); pendingToken = null; authWorker.shutdownNow(); roomReadWorker.shutdownNow(); fileTransferClient.close();
    }
    public MockData.Room room() { return room; }
    public boolean mic() { return mic; }
    public boolean camera() { return camera; }
    public void mic(boolean value) { mic = value; }
    public void camera(boolean value) { camera = value; }
    public boolean locked() { return locked; }
    public void locked(boolean value) { locked = value; }
    public void auth() {
        if (closing) return;
        if (authenticatedUser != null) { logout(); return; }
        boolean restoreWindow = stage.isShowing() && (stage.isMaximized() || stage.isFullScreen());
        // Windows có thể áp lại kích thước Lobby sau sự kiện unmaximize.
        // Ẩn cửa sổ khi thoát trạng thái mở rộng để dựng lại ở kích thước Auth.
        if (restoreWindow) stage.hide();
        stage.setMinWidth(0); stage.setMinHeight(0);
        stage.setFullScreen(false); stage.setMaximized(false);
        AuthScreen view = new AuthScreen(this); view.setPrefSize(960, 600);
        show(view, 960, 600, false, view::dispose);
        if (restoreWindow) stage.show();
        // Window manager khôi phục cửa sổ sau khi thoát fullscreen/maximized.
        // Đặt lại kích thước theo form khi sự kiện đó đã được xử lý.
        javafx.application.Platform.runLater(() -> {
            if (closing || stage.getScene().getRoot() != view) return;
            Scene scene = stage.getScene();
            stage.setWidth(960 + Math.max(0, stage.getWidth() - scene.getWidth()));
            stage.setHeight(600 + Math.max(0, stage.getHeight() - scene.getHeight()));
            stage.centerOnScreen();
        });
    }
    private boolean hasSession() { if (closing) return false; if (authenticatedUser == null) { auth(); return false; } return true; }
    public void lobby() {
        if (!hasSession()) return;
        if (legacyMeetingUi) { LegacyLobbyScreen view = new LegacyLobbyScreen(this); show(view, 1280, 800, true, view::dispose); return; } lobby(""); }
    public client.meeting.MeetingSession sfuSession() {
        if (sessionToken == null || sessionExpiresAt == null) throw new IllegalStateException("SESSION_REQUIRED");
        if (sfuSession == null) {
            sfuSession = meetingFactory == null ? new client.meeting.MeetingSession(sessionToken, sessionExpiresAt)
                    : meetingFactory.apply(sessionToken, sessionExpiresAt);
            sfuSession.roomProperty().addListener(meetingListener);
        }
        return sfuSession;
    }
    public boolean hasVideoRoom() { return usingSfuRoom && sfuSession != null && sfuSession.snapshot() != null; }
    public int maxRoomCapacity() {
        if (sfuSession != null) {
            int cap = sfuSession.maxRoomCapacity();
            if (cap > 0) return Math.min(50, cap);
        }
        return meetingFactory == null ? common.room.RoomValidation.MAX_CAPACITY : 20;
    }
    public boolean usesSignalingRooms() { return meetingFactory != null; }
    public void cancelAdmission() { closeSfuSession(); }
    private void closeSfuSession() {
        invitationPin = "";
        if (sfuSession != null) { sfuSession.roomProperty().removeListener(meetingListener); sfuSession.close(); sfuSession = null; }
        if (usingSfuRoom) { roomJoined = false; pendingRoomId = -1; }
        usingSfuRoom = false; leavingSfuRoom = false;
    }
    public void requestCreateRoom(String name, String pin, int capacity, boolean locked,
            Consumer<RoomInfo> success, Consumer<Throwable> failure) {
        String token = sessionToken;
        if (meetingFactory == null) { legacyRoomRequest(() -> authClient.createRoom(token, name, pin, capacity), success, failure); return; }
        var session = sfuSession();
        deliverMeetingRoom(session.create(new common.meeting.CreateRoomParams(name, pin, capacity, locked))
                .thenCompose(preview -> session.join(false, false)), room -> { invitationPin = pin; success.accept(room); }, failure);
    }
    public void requestJoinRoom(String identifier, String pin, Consumer<RoomInfo> success, Consumer<Throwable> failure) {
        String token = sessionToken;
        if (meetingFactory == null) { legacyRoomRequest(() -> authClient.joinRoom(token, identifier, pin), success, failure); return; }
        var session = sfuSession();
        deliverMeetingRoom(session.lookup(identifier, pin).thenCompose(preview -> session.join(false, false)), room -> { invitationPin = pin; success.accept(room); }, error -> {
            Throwable cause = unwrap(error);
            if (cause instanceof common.signaling.SignalException signal && "ROOM_NOT_FOUND".equals(signal.code())) {
                closeSfuSession();
                legacyRoomRequest(() -> authClient.joinRoom(token, identifier, pin), success, failure);
            } else failure.accept(error);
        });
    }
    public void requestReopenRoom(long roomId, Consumer<RoomInfo> success, Consumer<Throwable> failure) {
        if (closing || sessionToken == null) return;
        if (roomJoined) { failure.accept(new IOException("Hãy rời phòng hiện tại trước khi mở lại phòng.")); return; }
        String token = sessionToken;
        Consumer<Throwable> rejected = error -> {
            if (closing || !java.util.Objects.equals(token, sessionToken)) return;
            Throwable cause = unwrap(error);
            if (cause instanceof common.signaling.SignalException signal && "SESSION_INVALID".equals(signal.code())) {
                sessionExpired(AuthResponse.error("SESSION_EXPIRED", "Phiên đăng nhập đã hết hạn.")); return;
            }
            failure.accept(cause);
        };
        Consumer<RoomInfo> reopened = room -> {
            if (!closing && java.util.Objects.equals(token, sessionToken)) success.accept(room);
        };
        if (meetingFactory == null) { legacyRoomRequest(() -> authClient.reopenRoom(token, roomId), reopened, rejected); return; }
        var session = sfuSession();
        deliverMeetingRoom(session.reopen(Long.toString(roomId)).thenCompose(preview -> session.join(false, false)), reopened, error -> {
            Throwable cause = unwrap(error);
            if (cause instanceof common.signaling.SignalException signal && "ROOM_NOT_FOUND".equals(signal.code())) {
                closeSfuSession();
                legacyRoomRequest(() -> authClient.reopenRoom(token, roomId), reopened, rejected);
            } else rejected.accept(cause);
        });
    }
    private void legacyRoomRequest(Callable<AuthResponse> request, Consumer<RoomInfo> success, Consumer<Throwable> failure) {
        String ownerToken = sessionToken;
        runAuthRequest(request, response -> {
            if (closing || !java.util.Objects.equals(ownerToken, sessionToken)) return;
            if (sessionExpired(response)) return;
            if (response.success() && response.room() != null) success.accept(response.room());
            else failure.accept(new IOException(response.message() == null ? "Yêu cầu phòng chưa hoàn tất." : response.message()));
        }, error -> { if (!closing && java.util.Objects.equals(ownerToken, sessionToken)) failure.accept(error); });
    }
    private void deliverMeetingRoom(java.util.concurrent.CompletableFuture<common.meeting.RoomSnapshot> request,
            Consumer<RoomInfo> success, Consumer<Throwable> failure) {
        String ownerToken = sessionToken;
        request.whenComplete((snapshot, error) -> Platform.runLater(() -> {
            if (closing || !java.util.Objects.equals(ownerToken, sessionToken)) return;
            if (error != null) { failure.accept(unwrap(error)); return; }
            if (snapshot.collaborationRoom() == null) { failure.accept(new IOException("Máy chủ chưa hỗ trợ phòng video trên giao diện chính.")); return; }
            usingSfuRoom = true; roomJoined = true; mic = false; camera = false;
            updateRoom(snapshot.collaborationRoom()); success.accept(snapshot.collaborationRoom());
        }));
    }
    private static Throwable unwrap(Throwable error) {
        while ((error instanceof java.util.concurrent.CompletionException || error instanceof java.util.concurrent.ExecutionException) && error.getCause() != null) error = error.getCause();
        return error;
    }
    public void sfuRooms() {
        if (!hasSession()) return;
        if (roomJoined) { roomError("Hãy rời phòng hiện tại trước khi mở phòng SFU."); return; }
        SfuRoomScreen view = new SfuRoomScreen(this, sfuSession());
        show(view, 1100, 700, true, view::dispose);
    }
    public void lobby(String code) { if (!hasSession()) return; LobbyScreen view = new LobbyScreen(this, code); show(view, 1100, 700, true, view::dispose); }
    public void history() { if (hasSession()) show(new HistoryScreen(this), 1100, 700, true, () -> { }); }
    public void settings() { if (hasSession()) { SettingsScreen view = new SettingsScreen(this, false); show(view, 1100, 700, true, view::dispose); } }
    public void settingsAccount() { if (hasSession()) { SettingsScreen view = new SettingsScreen(this, true); show(view, 1100, 700, true, view::dispose); } }
    public void preJoin(MockData.Room next) {
        // Lối vào cũ từ màn hình kết thúc: luôn yêu cầu nhập lại PIN và được server xác nhận.
        lobby(next.code());
    }
    public void preJoin(RoomInfo next) {
        if (!hasSession()) return;
        updateRoom(next); roomJoined = true;
        if (Boolean.parseBoolean(preference("muteMic", "false"))) mic = false;
        if (Boolean.parseBoolean(preference("muteCamera", "false"))) camera = false;
        PreJoinScreen view = new PreJoinScreen(this);
        show(view, 1100, 700, true, view::dispose);
        // Pre-join can stack its panels and scroll independently of its pinned footer.
        stage.setMinWidth(560); stage.setMinHeight(480);
    }
    public void meeting() {
        if (legacyMeetingUi && meetingState != null) { LegacyMeetingScreen view = new LegacyMeetingScreen(this); show(view, 1280, 800, true, view::dispose); return; }
        if (!hasSession()) return;
        if (!roomJoined || roomInfo == null) { lobby(); return; }
        if (hasVideoRoom()) {
            if (!(stage.getScene().getRoot() instanceof PreJoinScreen waiting)) return;
            if (!waiting.cameraPermissionGranted()) { waiting.requireCameraPermission(); return; }
        }
        MeetingScreen view = new MeetingScreen(this);
        show(view, 1280, 800, true, view::dispose);
        if (hasVideoRoom()) sfuSession.video().publishPreparedCamera();
    }
    public void ended(long seconds, int count, String reason) { endReason = reason; ended(seconds, count); }
    public void ended(long seconds, int count) {
        if (!hasSession()) return;
        if (roomJoined) {
            leaveRoom(() -> ended(seconds, count), this::roomError);
            return;
        }
        MeetingEndedScreen view = new MeetingEndedScreen(this, seconds, count);
        show(view, 1100, 700, true, view::dispose);
    }
    /** Existing entry points use the same room authority as the visible forms. */
    public void createRoom(String name, String pin, int capacity, boolean locked) {
        if (!hasSession()) return;
        Parent original = stage.getScene().getRoot(); original.setDisable(true);
        requestCreateRoom(name, pin, capacity, locked, room -> { original.setDisable(false); preJoin(room); },
                error -> { original.setDisable(false); roomError(connectionError(error)); });
    }
    public void joinRoom(String code, String pin) {
        if (!hasSession()) return;
        Parent original = stage.getScene().getRoot(); original.setDisable(true);
        requestJoinRoom(code, pin, room -> { original.setDisable(false); preJoin(room); },
                error -> { original.setDisable(false); roomError(connectionError(error)); });
    }
    private void roomError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR); alert.initOwner(stage);
        alert.setTitle("Yêu cầu phòng chưa hoàn tất"); alert.setHeaderText("Không thể xử lý phòng");
        alert.setContentText(message); Ui.style(alert); alert.showAndWait();
    }
    private void show(Parent root, int width, int height, boolean resize, Runnable onDispose) {
        dispose(); cleanup = onDispose;
        boolean expanded = stage.isMaximized() || stage.isFullScreen();
        stage.setMinWidth(0); stage.setMinHeight(0);
        if (!expanded) stage.setResizable(resize);
        Scene scene = stage.getScene();
        if (scene == null) {
            scene = new Scene(root, width, height);
            scene.getStylesheets().add(SceneManager.class.getResource("/styles/app.css").toExternalForm());
            stage.setScene(scene);
        } else scene.setRoot(root);
        ThemeManager.attach(root);
        if (!stage.isShowing()) stage.sizeToScene();
        else if (!expanded) {
            stage.setWidth(width + Math.max(0, stage.getWidth() - scene.getWidth()));
            stage.setHeight(height + Math.max(0, stage.getHeight() - scene.getHeight()));
        }
        if (resize) { stage.setMinWidth(1000); stage.setMinHeight(650); }
        // Không translate root Scene: có thể làm vùng nội dung hụt chiều cao khi resize.
        root.setOpacity(0); root.setTranslateY(0);
        FadeTransition fade = new FadeTransition(Duration.millis(200), root); fade.setToValue(1);
        transition = new ParallelTransition(fade); transition.play();
    }
    public void dispose() {
        if (passwordRecovery != null) { passwordRecovery.close(); passwordRecovery = null; }
        cleanup.run(); cleanup = () -> { }; if (transition != null) transition.stop();
    }
}
