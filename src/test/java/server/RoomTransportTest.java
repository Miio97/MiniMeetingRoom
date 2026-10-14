package server;

import client.auth.AuthClient;
import common.auth.AuthRequest;
import common.auth.AuthResponse;
import common.auth.AuthWire;
import common.room.RoomInfo;
import common.room.RoomRequest;
import com.google.gson.Gson;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import server.auth.PasswordHasher;
import support.RoomTestFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Two independent clients use production auth sessions and rooms through real TLS connections. */
@Timeout(30)
class RoomTransportTest {
    @TempDir static Path temporary;
    private static RoomTestFixture.Tls tls;

    @BeforeAll @Timeout(60)
    static void certificates() throws Exception { tls = RoomTestFixture.certificates(temporary); }

    @Test
    void twoClientsCreateAndJoinByIdAndCodeWithServerGeneratedMetadata() throws Exception {
        try (RoomTestFixture fixture = new RoomTestFixture(tls)) {
            AuthClient hostClient = fixture.client(); AuthClient guestClient = fixture.client();
            AuthResponse host = fixture.account("HostUser", "Chủ phòng");
            AuthResponse guest = fixture.account("GuestUser", "Người tham gia");
            Instant before = Instant.now().minusSeconds(1);
            // Even unrelated auth fields from a modified client cannot choose the room's host.
            AuthResponse created = raw(fixture, new AuthRequest("CREATE_ROOM", "GuestUser", null,
                    "Forged host", null, host.token(), new RoomRequest("  Họp nhóm kỹ thuật  ", "482931", null, 3)));
            assertTrue(created.success(), created::message);
            RoomInfo room = created.room();
            assertTrue(room.id() > 0); assertTrue(room.code().matches("MMR-[A-Z0-9]+"));
            assertEquals(host.user().id(), room.hostId()); assertEquals("Họp nhóm kỹ thuật", room.name());
            assertFalse(Instant.parse(room.createdAt()).isBefore(before));
            assertEquals("DANG_HOAT_DONG", room.status()); assertFalse(room.locked());
            assertEquals(1, room.people()); assertEquals(3, room.capacity());
            assertEquals(host.user().id(), room.participants().getFirst().userId());
            assertEquals("CHU_PHONG", room.participants().getFirst().role());

            AuthResponse joinedById = guestClient.joinRoom(guest.token(), Long.toString(room.id()), "482931");
            assertTrue(joinedById.success(), joinedById::message); assertEquals(2, joinedById.room().people());
            assertTrue(guestClient.leaveRoom(guest.token(), room.id()).success());
            AuthResponse joinedByCode = guestClient.joinRoom(guest.token(), room.code().toLowerCase(), "482931");
            assertTrue(joinedByCode.success(), joinedByCode::message);
            // Repeating a successful join must not add another active membership.
            assertTrue(guestClient.joinRoom(guest.token(), room.code(), "482931").success());
            assertEquals(2, fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));

            AuthResponse snapshot = hostClient.getRoom(host.token(), room.id());
            assertTrue(snapshot.success()); assertEquals(2, snapshot.room().people());
            assertEquals(List.of("Chủ phòng", "Người tham gia"), snapshot.room().participants().stream()
                    .map(participant -> participant.displayName()).toList());
            assertEquals("THANH_VIEN", snapshot.room().participants().getLast().role());
            AuthResponse listed = guestClient.listRooms(guest.token());
            assertTrue(listed.success()); assertEquals(1, listed.rooms().size());
            assertEquals(room.id(), listed.rooms().getFirst().id());
            assertTrue(listed.rooms().getFirst().participants().isEmpty());
            for (AuthResponse response : List.of(created, joinedById, joinedByCode, snapshot, listed)) {
                assertPublicRoomResponse(response);
            }
            try (Connection connection = fixture.connection(); Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("SELECT Ma_PIN_Bam FROM Phong_Hop")) {
                assertTrue(result.next()); String stored = result.getString(1);
                assertNotEquals("482931", stored); assertTrue(new PasswordHasher().verify("482931", stored));
            }
        }
    }

    @Test
    void serverRejectsWrongPinMissingClosedLockedAndFullRoomsWithoutAddingMembers() throws Exception {
        try (RoomTestFixture fixture = new RoomTestFixture(tls)) {
            AuthClient client = fixture.client();
            AuthResponse host = fixture.account("HostUser", "Chủ phòng");
            AuthResponse guest = fixture.account("GuestUser", "Khách");
            AuthResponse extra = fixture.account("ExtraUser", "Khách thứ ba");
            RoomInfo room = client.createRoom(host.token(), "Phòng giới hạn", "7281", 2).room();
            error("INVALID_ROOM_PIN", client.joinRoom(guest.token(), room.code(), "9999"));
            error("ROOM_NOT_FOUND", client.joinRoom(guest.token(), "999999999", "7281"));
            error("ROOM_ACCESS_DENIED", client.getRoom(guest.token(), room.id()));
            assertEquals(1, fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong"));
            fixture.execute("UPDATE Phong_Hop SET Da_Khoa = TRUE");
            error("ROOM_LOCKED", client.joinRoom(guest.token(), room.code(), "7281"));
            fixture.execute("UPDATE Phong_Hop SET Da_Khoa = FALSE, Trang_Thai = 'DA_DONG'");
            error("ROOM_CLOSED", client.joinRoom(guest.token(), room.code(), "7281"));
            fixture.execute("UPDATE Phong_Hop SET Trang_Thai = 'DANG_HOAT_DONG'");
            assertTrue(client.joinRoom(guest.token(), room.code(), "7281").success());
            error("ROOM_FULL", client.joinRoom(extra.token(), room.code(), "7281"));
            assertEquals(2, fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
            assertEquals(2, client.getRoom(host.token(), room.id()).room().people());
        }
    }

    @Test
    void invalidExpiredAndRevokedSessionsCannotUseRoomCommands() throws Exception {
        try (RoomTestFixture fixture = new RoomTestFixture(tls)) {
            AuthClient client = fixture.client();
            AuthResponse host = fixture.account("HostUser", "Chủ phòng");
            AuthResponse guest = fixture.account("GuestUser", "Khách");
            RoomInfo room = client.createRoom(host.token(), "Phòng cần phiên", "3291", 10).room();
            assertTrue(client.logout(guest.token()).success());
            error("SESSION_EXPIRED", client.joinRoom(guest.token(), room.code(), "3291"));
            error("SESSION_EXPIRED", client.createRoom("invented-session-token", "Giả mạo", "3291", 10));
            fixture.execute("UPDATE Phien_Dang_Nhap SET Het_Han_Luc = TIMESTAMP '2000-01-01 00:00:00'");
            error("SESSION_EXPIRED", client.createRoom(host.token(), "Hết hạn", "3291", 10));
            error("SESSION_EXPIRED", client.joinRoom(host.token(), room.code(), "3291"));
            error("SESSION_EXPIRED", client.listRooms(host.token()));
            error("SESSION_EXPIRED", client.getRoom(host.token(), room.id()));
            error("SESSION_EXPIRED", client.leaveRoom(host.token(), room.id()));
            assertEquals(1, fixture.count("SELECT COUNT(*) FROM Phong_Hop"));
            assertEquals(1, fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
        }
    }

    @Test
    void finalLeaveClosesRoomAndRejectsLaterJoins() throws Exception {
        try (RoomTestFixture fixture = new RoomTestFixture(tls)) {
            AuthClient client = fixture.client();
            AuthResponse host = fixture.account("HostUser", "Chủ phòng");
            AuthResponse guest = fixture.account("GuestUser", "Khách");
            RoomInfo room = client.createRoom(host.token(), "Phòng đóng khi trống", "6183", 10).room();
            assertTrue(client.joinRoom(guest.token(), room.code(), "6183").success());
            assertTrue(client.leaveRoom(guest.token(), room.id()).success());
            assertEquals(1, client.getRoom(host.token(), room.id()).room().people());
            AuthResponse left = client.leaveRoom(host.token(), room.id());
            assertTrue(left.success()); assertEquals("DA_DONG", left.room().status()); assertEquals(0, left.room().people());
            assertTrue(client.leaveRoom(host.token(), room.id()).success(), "Repeated leave must be harmless");
            error("ROOM_CLOSED", client.joinRoom(guest.token(), room.code(), "6183"));
            assertEquals(1, fixture.count("SELECT COUNT(*) FROM Phong_Hop WHERE Ngay_Dong IS NOT NULL"));
            assertEquals(0, fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
        }
    }

    private static AuthResponse raw(RoomTestFixture fixture, AuthRequest request) throws Exception {
        try (SSLSocket socket = (SSLSocket) tls.client().getSocketFactory().createSocket("localhost", fixture.server.port())) {
            socket.setSoTimeout(5000); socket.startHandshake();
            AuthWire.write(new DataOutputStream(socket.getOutputStream()), request);
            return AuthWire.read(new DataInputStream(socket.getInputStream()), AuthResponse.class);
        }
    }

    private static void error(String code, AuthResponse response) {
        assertFalse(response.success(), response::message); assertEquals(code, response.code()); assertNull(response.room());
        assertPublicRoomResponse(response);
    }

    private static void assertPublicRoomResponse(AuthResponse response) {
        assertNull(response.user()); assertNull(response.token()); assertNull(response.expiresAt());
        String json = new Gson().toJson(response);
        for (String secret : List.of("\"pin\"", "\"password\"", "Ma_PIN_Bam", "Mat_Khau_Bam", "pbkdf2-sha256")) {
            assertFalse(json.contains(secret), "Private room credentials must not appear in a response");
        }
    }
}
