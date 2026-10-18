package common.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import common.room.RoomInfo;
import common.room.RoomParticipant;
import common.room.RoomRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuthWireTest {
    @Test void verificationStateComesFromSnapshotAndDefaultsToFalseForLegacyResponses() throws IOException {
        AuthUser legacy = AuthWire.read(input(frame(
                "{\"id\":7,\"username\":\"user\",\"displayName\":\"Name\",\"email\":\"user@example.com\"}"
                        .getBytes(StandardCharsets.UTF_8))), AuthUser.class);
        assertFalse(legacy.emailVerified());
        AuthUser verified = new AuthUser(7, "user", "Name", "user@example.com", true);
        assertTrue(AuthWire.read(input(encode(verified)), AuthUser.class).emailVerified());
        assertFalse(new AuthUser(7, "user", "Name", null, true).emailVerified());
    }
    @Test void passwordChangeRoundTripsWithoutExposingSecretsInToString() throws IOException {
        AuthRequest request = new AuthRequest("CHANGE_PASSWORD", null, "Old-password-123!", null, null,
                "private-token", null, "New-password-456!");
        assertEquals(request, AuthWire.read(input(encode(request)), AuthRequest.class));
        assertEquals("AuthRequest[action=CHANGE_PASSWORD]", request.toString());
    }
    @Test
    void preservesVietnameseAndUsesUtf8ByteLength() throws IOException {
        AuthRequest request = new AuthRequest("REGISTER", "khoa", "bí_mật_🔐",
                "Trần Văn Khoa", "khoa@example.com", null);
        byte[] frame = encode(request);
        try (DataInputStream input = input(frame)) {
            assertEquals(frame.length - Integer.BYTES, input.readInt());
        }
        assertEquals(request, AuthWire.read(input(frame), AuthRequest.class));

        AuthResponse response = AuthResponse.ok("Đăng nhập thành công",
                new AuthUser(7, "khoa", "Trần Văn Khoa", "khoa@example.com"),
                "session-token", "2026-10-08T22:00:00Z");
        assertEquals(response, AuthWire.read(input(encode(response)), AuthResponse.class));
    }

    @Test
    void readsOnlyOneFrameAtATime() throws IOException {
        AuthRequest first = new AuthRequest("LOGIN", "khoa", "password-one", null, null, null);
        AuthRequest second = new AuthRequest("LOGOUT", null, null, null, null, "token-two");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        AuthWire.write(output, first);
        AuthWire.write(output, second);
        try (DataInputStream input = input(bytes.toByteArray())) {
            assertEquals(first, AuthWire.read(input, AuthRequest.class));
            assertEquals(second, AuthWire.read(input, AuthRequest.class));
            assertEquals(-1, input.read());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, AuthWire.MAX_FRAME_BYTES + 1, Integer.MAX_VALUE})
    void rejectsInvalidFrameLengthsBeforeReadingPayload(int length) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeInt(length);
        assertThrows(IOException.class, () -> AuthWire.read(input(bytes.toByteArray()), AuthRequest.class));
    }

    @Test
    void rejectsTruncatedHeaderAndPayload() throws IOException {
        assertThrows(IOException.class, () -> AuthWire.read(input(new byte[]{0, 0}), AuthRequest.class));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(100);
        output.write("{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> AuthWire.read(input(bytes.toByteArray()), AuthRequest.class));
    }

    @Test
    void rejectsMalformedUtf8InsteadOfSilentlyReplacingIt() throws IOException {
        assertThrows(IOException.class,
                () -> AuthWire.read(input(frame(new byte[]{(byte) 0xc3, 0x28})), AuthRequest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "true", "\"LOGIN\"", "{", "{action:'LOGIN'}",
            "{\"action\":\"LOGIN\"} {}", "/* comment */ {\"action\":\"LOGIN\"}"})
    void rejectsInvalidJsonOrUnexpectedDocumentShape(String json) {
        assertThrows(IOException.class, () -> AuthWire.read(
                input(frame(json.getBytes(StandardCharsets.UTF_8))), AuthRequest.class));
    }

    @Test
    void rejectsOversizedOutgoingFrameWithoutWritingAnything() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AuthRequest request = new AuthRequest("LOGIN", "khoa", "🔐".repeat(AuthWire.MAX_FRAME_BYTES),
                null, null, null);
        assertThrows(IOException.class, () -> AuthWire.write(new DataOutputStream(bytes), request));
        assertEquals(0, bytes.size());
    }

    @Test
    void diagnosticStringsDoNotRevealCredentials() {
        String password = "private-password-123";
        String token = "private-session-token-456";
        AuthRequest request = new AuthRequest("LOGIN", "khoa", password, null, null, token);
        AuthResponse response = AuthResponse.ok("success", null, token, "tomorrow");
        assertFalse(request.toString().contains(password));
        assertFalse(request.toString().contains(token));
        assertFalse(response.toString().contains(token));
        assertTrue(request.toString().contains("LOGIN"));
        RoomRequest room = new RoomRequest("Meeting", "954321", null, 10);
        assertFalse(room.toString().contains("954321"));
        assertFalse(new AuthRequest("CREATE_ROOM", null, null, null, null, token, room).toString().contains("954321"));
    }

    @Test
    void preservesRoomRequestsAndFullParticipantSnapshotWithinFrameBound() throws IOException {
        AuthRequest request = new AuthRequest("CREATE_ROOM", null, null, null, null, "session-token",
                new RoomRequest("Họp nhóm Java", "123456", null, 250));
        assertEquals(request, AuthWire.read(input(encode(request)), AuthRequest.class));
        List<RoomParticipant> members = IntStream.rangeClosed(1, 250)
                .mapToObj(id -> new RoomParticipant(id, "Đ".repeat(100), id == 1 ? "CHU_PHONG" : "THANH_VIEN"))
                .toList();
        AuthResponse response = AuthResponse.roomOk("Đã tham gia", new RoomInfo(8, "Họp nhóm Java",
                "MMR-0123ABCD", 1, "2026-10-14T10:00:00Z", "DANG_HOAT_DONG", false, 250, 250, members));
        byte[] bytes = encode(response);
        assertTrue(bytes.length > 16 * 1024, "A full room no longer fits the original authentication-only frame size");
        assertTrue(bytes.length - Integer.BYTES <= AuthWire.MAX_FRAME_BYTES);
        assertEquals(response, AuthWire.read(input(bytes), AuthResponse.class));
    }

    @Test void schedulePagingAndOtpFieldsCoexistOnTheSharedWire() throws IOException {
        var schedule = new common.schedule.ScheduleRequest("Planning", "Notes",
                "2026-10-19T01:00:00Z", "2026-10-19T02:00:00Z", "KHONG", "MMR-0123ABCD");
        var scheduled = new AuthRequest("CREATE_SCHEDULE", null, null, null, null, "session", null, schedule);
        var registration = new AuthRequest("VERIFY_REGISTRATION", null, null, null,
                "user@example.com", null, null, "123456", "challenge");
        var changed = new AuthRequest("CHANGE_PASSWORD", null, "old", null, null,
                "session", null, null, null, "new");
        var page = new AuthRequest("LIST_ROOMS", null, null, null, null, "session", RoomRequest.page(20, 10));
        for (var request : List.of(scheduled, registration, changed, page)) {
            assertEquals(request, AuthWire.read(input(encode(request)), AuthRequest.class));
        }
        assertNull(scheduled.otp());
        assertNull(scheduled.registrationChallenge());
        assertNull(registration.schedule());
        assertEquals("new", changed.newPassword());
        assertNull(changed.otp());
        var info = new common.schedule.ScheduleInfo(1, 7, "Planning", "Notes",
                schedule.startsAt(), schedule.endsAt(), "KHONG", null, null, "2026-10-18T00:00:00Z");
        for (var response : List.of(AuthResponse.scheduleOk("created", info),
                AuthResponse.schedulesOk("listed", List.of(info)), AuthResponse.roomsPage(List.of(), true),
                new AuthResponse(true, "OTP_REQUIRED", "sent", null, null, null, null, List.of(), "challenge"))) {
            assertEquals(response, AuthWire.read(input(encode(response)), AuthResponse.class));
        }
    }

    private static byte[] encode(Object message) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AuthWire.write(new DataOutputStream(bytes), message);
        return bytes.toByteArray();
    }

    private static byte[] frame(byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(payload.length);
        output.write(payload);
        return bytes.toByteArray();
    }

    private static DataInputStream input(byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }
}
