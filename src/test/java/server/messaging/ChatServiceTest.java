package server.messaging;

import common.auth.*;
import common.chat.*;
import common.meeting.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.sql.*;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import server.auth.AuthService;
import server.auth.PasswordHasher;
import server.meeting.RoomService;
import static org.junit.jupiter.api.Assertions.*;

/** Duc: chat permissions, persistence and delivery use real authenticated test accounts. */
@Timeout(30)
class ChatServiceTest {
    private String database;
    private AuthService auth;
    private RoomService rooms;
    private ChatService chats;
    private AuthResponse host, guest, third;
    private final TestClock clock = new TestClock();

    @BeforeEach void setup() throws Exception {
        database = "jdbc:h2:mem:chat_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE Nguoi_Dung (Ma_Nguoi_Dung BIGINT AUTO_INCREMENT PRIMARY KEY, Ten_Dang_Nhap VARCHAR_IGNORECASE(50) UNIQUE, Mat_Khau_Bam VARCHAR(255), Ten_Hien_Thi VARCHAR(100), Email VARCHAR(255) UNIQUE, Trang_Thai VARCHAR(20) DEFAULT 'HOAT_DONG', Lan_Dang_Nhap_Cuoi TIMESTAMP)");
            sql.execute("CREATE TABLE Phien_Dang_Nhap (Ma_Phien BIGINT AUTO_INCREMENT PRIMARY KEY, Ma_Nguoi_Dung BIGINT REFERENCES Nguoi_Dung, Token_Bam VARCHAR(255) UNIQUE, Ngay_Tao TIMESTAMP, Het_Han_Luc TIMESTAMP, Thu_Hoi_Luc TIMESTAMP)");
            sql.execute("CREATE TABLE Phong_Hop (Ma_Phong BIGINT AUTO_INCREMENT PRIMARY KEY, Ma_Phong_Tham_Gia VARCHAR(20) UNIQUE, Ten_Phong VARCHAR(100), Ma_PIN_Bam VARCHAR(255), Ma_Chu_Phong BIGINT REFERENCES Nguoi_Dung, Trang_Thai VARCHAR(20), Da_Khoa BOOLEAN, So_Nguoi_Toi_Da SMALLINT, Ngay_Dong TIMESTAMP)");
            sql.execute("CREATE TABLE Thanh_Vien_Phong (Ma_Thanh_Vien_Phong BIGINT AUTO_INCREMENT PRIMARY KEY, Ma_Phong BIGINT REFERENCES Phong_Hop, Ma_Nguoi_Dung BIGINT REFERENCES Nguoi_Dung, Vai_Tro VARCHAR(20), Tham_Gia_Luc TIMESTAMP DEFAULT CURRENT_TIMESTAMP, Roi_Phong_Luc TIMESTAMP, Ly_Do_Roi VARCHAR(20))");
            sql.execute("CREATE TABLE Tin_Nhan (Ma_Tin_Nhan BIGINT AUTO_INCREMENT PRIMARY KEY, Ma_Phong BIGINT REFERENCES Phong_Hop, Ma_Nguoi_Gui BIGINT REFERENCES Nguoi_Dung, Loai_Tin_Nhan VARCHAR(20), Noi_Dung TEXT, Ngay_Gui TIMESTAMP NOT NULL, Ngay_Xoa TIMESTAMP)");
        }
        auth = new AuthService(this::connection, new PasswordHasher(), clock, Duration.ofHours(24));
        rooms = new RoomService(this::connection, clock); chats = new ChatService(rooms, clock);
        host = account("host", "Đức"); guest = account("guest", "Thành viên"); third = account("third", "Người thứ ba");
    }
    @AfterEach void cleanup() throws Exception {
        chats.close(); rooms.close();
        try (var connection = connection(); var sql = connection.createStatement()) { sql.execute("DROP ALL OBJECTS"); }
    }
    private Connection connection() throws SQLException { return DriverManager.getConnection(database); }
    private AuthResponse account(String username, String name) throws Exception {
        assertTrue(auth.handle(new AuthRequest("REGISTER", username, "Password123!", name, null, null)).success());
        AuthResponse login = auth.handle(new AuthRequest("LOGIN", username, "Password123!", null, null, null)); assertTrue(login.success()); return login;
    }
    private RoomResponse create(AuthResponse user) throws Exception { return rooms.handle(RoomRequest.create(user.token(), "Phòng chat", "1234", 10)); }
    private RoomResponse join(AuthResponse user, RoomResponse room) throws Exception { return rooms.handle(RoomRequest.join(user.token(), room.room().code(), "1234")); }
    private ChatRequest listen(AuthResponse user, RoomResponse room) { return ChatRequest.listen(user.token(), room.room().id(), room.ticket()); }
    private ChatResponse send(AuthResponse user, RoomResponse room, String text) throws Exception { return chats.send(ChatRequest.send(user.token(), room.room().id(), room.ticket(), text)); }
    private RoomResponse command(AuthResponse user, RoomResponse room, String action, long target) throws Exception { return rooms.handle(RoomRequest.command(action, user.token(), room.room().id(), room.ticket(), target)); }
    private int count(String query) throws Exception {
        try (var connection = connection(); var sql = connection.createStatement(); var result = sql.executeQuery(query)) { assertTrue(result.next()); return result.getInt(1); }
    }

    @Test void committedMessageIsBroadcastToAllMembersWithAuthenticatedIdentity() throws Exception {
        RoomResponse a = create(host), b = join(guest, a), c = join(third, a);
        try (var first = chats.subscribe(listen(host, a), () -> { }); var second = chats.subscribe(listen(guest, b), () -> { }); var last = chats.subscribe(listen(third, c), () -> { })) {
            ChatResponse reply = send(guest, b, "  Xin chào Đức 👋  ");
            ChatMessage message = reply.messages().getFirst();
            assertEquals(guest.user().id(), message.userId()); assertEquals("Thành viên", message.name());
            assertEquals("Xin chào Đức 👋", message.text());
            assertEquals(reply, first.next()); assertEquals(reply, second.next()); assertEquals(reply, last.next());
            assertEquals(1, count("SELECT COUNT(*) FROM Tin_Nhan"));
        }
    }
    @Test void roomsAreIsolatedAndLateSubscribersLoadCommittedHistory() throws Exception {
        RoomResponse a = create(host), other = create(third);
        try (var isolated = chats.subscribe(listen(third, other), () -> { })) {
            ChatMessage message = send(host, a, "Riêng phòng A").messages().getFirst();
            assertNull(isolated.next());
            try (var late = chats.subscribe(listen(host, a), () -> { })) { assertEquals(List.of(message), late.history()); }
        }
    }
    @Test void forgedSessionsTicketsAndWaitingUsersCannotReadOrSendChat() throws Exception {
        RoomResponse a = create(host);
        assertEquals("SESSION_INVALID", assertThrows(ChatException.class, () -> chats.subscribe(ChatRequest.listen("forged", a.room().id(), a.ticket()), () -> { })).code());
        assertEquals("NOT_IN_ROOM", assertThrows(ChatException.class, () -> chats.send(ChatRequest.send(guest.token(), a.room().id(), a.ticket(), "forged"))).code());
        assertEquals("NOT_IN_ROOM", assertThrows(ChatException.class, () -> chats.subscribe(ChatRequest.listen(host.token(), a.room().id(), "forged"), () -> { })).code());
        rooms.handle(RoomRequest.settings(host.token(), a.room().id(), a.ticket(), null, true));
        RoomResponse waiting = join(guest, a); assertTrue(waiting.pending());
        assertEquals("NOT_IN_ROOM", assertThrows(ChatException.class, () -> send(guest, waiting, "Chưa được duyệt")).code());
        assertEquals("NOT_IN_ROOM", assertThrows(ChatException.class, () -> chats.subscribe(listen(guest, waiting), () -> { })).code());
        command(host, a, "ROOM_PENDING_APPROVE", guest.user().id());
        assertTrue(send(guest, waiting, "Đã được duyệt").success());
    }
    @Test void kickLeaveLogoutAndCloseRevokeChatAccess() throws Exception {
        RoomResponse a = create(host), b = join(guest, a), c = join(third, a);
        try (var listener = chats.subscribe(listen(guest, b), () -> { })) {
            command(host, a, "ROOM_KICK", guest.user().id());
            assertEquals("NOT_IN_ROOM", assertThrows(ChatException.class, () -> chats.validate(listener)).code());
            assertEquals("NOT_IN_ROOM", assertThrows(ChatException.class, () -> send(guest, b, "Sau khi bị xóa")).code());
        }
        command(third, c, "ROOM_LEAVE", 0);
        assertEquals("NOT_IN_ROOM", assertThrows(ChatException.class, () -> send(third, c, "Sau khi rời")).code());
        RoomResponse currentGuest = join(guest, a);
        auth.handle(new AuthRequest("LOGOUT", null, null, null, null, guest.token()));
        assertEquals("SESSION_INVALID", assertThrows(ChatException.class, () -> send(guest, currentGuest, "Sau khi đăng xuất")).code());
        command(host, a, "ROOM_CLOSE", 0);
        assertEquals("ROOM_CLOSED", assertThrows(ChatException.class, () -> send(host, a, "Sau khi đóng phòng")).code());
        assertEquals(0, count("SELECT COUNT(*) FROM Tin_Nhan"));
    }
    @Test void textValidationProtectsTheWireLimitAndPreservesVietnameseAndEmoji() throws Exception {
        RoomResponse a = create(host);
        for (String text : List.of("", "  ", "x".repeat(2001), "bad\u0000text", "\ud800")) {
            assertEquals("VALIDATION_ERROR", assertThrows(ChatException.class, () -> send(host, a, text)).code());
        }
        assertEquals(0, count("SELECT COUNT(*) FROM Tin_Nhan"));
        ChatResponse escaped = send(host, a, "<".repeat(2000));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); AuthWire.write(new DataOutputStream(bytes), escaped);
        assertTrue(bytes.size() <= AuthWire.MAX_FRAME_BYTES + 4);
        ChatMessage unicode = send(host, a, "Tiếng Việt 🎉").messages().getFirst(); assertEquals("Tiếng Việt 🎉", unicode.text());
        assertFalse(unicode.toString().contains("Tiếng")); assertFalse(listen(host, a).toString().contains(host.token()));
    }
    @Test void historySurvivesServiceRestartAndReentryWithANewTicket() throws Exception {
        RoomResponse a = create(host);
        ChatMessage message = send(host, a, "Lưu lịch sử thật").messages().getFirst();
        chats.close(); rooms.close();
        rooms = new RoomService(this::connection, clock); chats = new ChatService(rooms, clock);
        RoomResponse rejoined = join(host, a); assertTrue(rejoined.success()); assertNotEquals(a.ticket(), rejoined.ticket());
        try (var subscription = chats.subscribe(listen(host, rejoined), () -> { })) { assertEquals(List.of(message), subscription.history()); }
    }
    @Test void concurrentSendersShareAConsistentDatabaseAndBroadcastOrder() throws Exception {
        RoomResponse a = create(host), b = join(guest, a);
        try (var listener = chats.subscribe(listen(host, a), () -> { }); var executor = Executors.newFixedThreadPool(2)) {
            Future<ChatResponse> first = executor.submit(() -> send(host, a, "A"));
            Future<ChatResponse> second = executor.submit(() -> send(guest, b, "B"));
            assertTrue(first.get(5, TimeUnit.SECONDS).success()); assertTrue(second.get(5, TimeUnit.SECONDS).success());
            long earlier = listener.next().messages().getFirst().id(), later = listener.next().messages().getFirst().id();
            assertTrue(earlier < later); assertEquals(2, count("SELECT COUNT(*) FROM Tin_Nhan"));
        }
    }
    @Test void failedDatabaseWriteNeverBroadcastsAGhostMessage() throws Exception {
        RoomResponse a = create(host);
        try (var connection = connection(); var sql = connection.createStatement()) { sql.execute("ALTER TABLE Tin_Nhan ADD CONSTRAINT Test_Short_Message CHECK (CHAR_LENGTH(Noi_Dung) < 5)"); }
        try (var listener = chats.subscribe(listen(host, a), () -> { })) {
            assertThrows(SQLException.class, () -> send(host, a, "will-fail"));
            assertNull(listener.next()); assertEquals(0, count("SELECT COUNT(*) FROM Tin_Nhan"));
        }
    }
    @Test void slowSubscriberIsDisconnectedWithoutBlockingOtherSenders() throws Exception {
        RoomResponse a = create(host);
        CountDownLatch disconnected = new CountDownLatch(1);
        try (var listener = chats.subscribe(listen(host, a), disconnected::countDown)) {
            for (int index = 0; index < 65; index++) assertTrue(send(host, a, "Message " + index).success());
            assertTrue(listener.ended()); assertEquals(0, chats.subscriberCount());
            assertTrue(disconnected.await(2, TimeUnit.SECONDS)); assertEquals(65, count("SELECT COUNT(*) FROM Tin_Nhan"));
        }
    }
    @Test void expiredMembershipCannotBeRevivedByAChatRequest() throws Exception {
        RoomResponse a = create(host); clock.advance(11);
        assertEquals("NOT_IN_ROOM", assertThrows(ChatException.class, () -> send(host, a, "Hết kết nối")).code());
    }
    private static final class TestClock extends Clock {
        private Instant instant = Instant.parse("2026-10-14T10:00:00Z");
        void advance(long seconds) { instant = instant.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
