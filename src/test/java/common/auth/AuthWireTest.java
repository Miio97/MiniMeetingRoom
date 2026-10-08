package common.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuthWireTest {
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
    @ValueSource(ints = {-1, 0, 16385, Integer.MAX_VALUE})
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
