package client.auth;

import common.auth.AuthRequest;
import common.auth.AuthResponse;
import common.diagnostics.Diagnostics;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import server.AuthServer;
import support.RoomTestFixture;

import static org.junit.jupiter.api.Assertions.*;

/** Production framing and TLS for OTP commands; no real mailbox or database is contacted. */
@Timeout(25)
class EmailOtpTransportTest {
    @TempDir static Path temporary;
    private static RoomTestFixture.Tls tls;
    private static final String CHALLENGE = "OTP_private_registration_flow_12345678901234567890".substring(0, 43);

    @BeforeAll
    @Timeout(60)
    static void certificates() throws Exception {
        tls = RoomTestFixture.certificates(temporary);
    }

    @Test
    void registrationAndRecoveryCommandsCarryOnlyTheirRequiredFieldsOverTls() throws Exception {
        List<AuthRequest> received = new CopyOnWriteArrayList<>();
        try (AuthServer server = server(request -> {
            received.add(request);
            return "REGISTER".equals(request.action())
                    ? new AuthResponse(true, "OTP_REQUIRED", "Hãy xác minh email bằng OTP.", null, null, null,
                            null, List.of(), CHALLENGE)
                    : AuthResponse.ok("Đã xử lý yêu cầu.", null, null, null);
        })) {
            AuthClient client = client(server, tls.client());
            AuthResponse registration = client.register("KhoaOtp", "Trần Khoa", "otp@example.invalid", "Initial-password-9!");
            assertTrue(registration.success());
            assertEquals("OTP_REQUIRED", registration.code());
            assertNull(registration.token(), "Requesting an OTP must not create a login session");
            assertEquals(CHALLENGE, registration.registrationChallenge());
            assertFalse(registration.toString().contains(CHALLENGE));
            assertTrue(client.verifyRegistration("otp@example.invalid", "482061", registration.registrationChallenge()).success());
            assertTrue(client.resendRegistrationOtp("otp@example.invalid", registration.registrationChallenge()).success());
            assertTrue(client.requestPasswordReset("otp@example.invalid").success());
            assertTrue(client.resetPassword("otp@example.invalid", "975304", "Replacement-password-7!").success());

            assertEquals(List.of(
                    new AuthRequest("REGISTER", "KhoaOtp", "Initial-password-9!", "Trần Khoa", "otp@example.invalid", null, null, null),
                    new AuthRequest("VERIFY_REGISTRATION", null, null, null, "otp@example.invalid", null, null, "482061", CHALLENGE),
                    new AuthRequest("RESEND_REGISTRATION_OTP", null, null, null, "otp@example.invalid", null, null, null, CHALLENGE),
                    new AuthRequest("REQUEST_PASSWORD_RESET", null, null, null, "otp@example.invalid", null, null, null),
                    new AuthRequest("RESET_PASSWORD", null, "Replacement-password-7!", null, "otp@example.invalid", null, null, "975304")
            ), received);
        }
    }

    @Test
    void diagnosticsAndDtoStringsOmitEmailOtpAndPasswordsEvenWhenServerFails() throws Exception {
        String oldDirectory = System.getProperty("mmr.diagnostics.dir");
        String oldEnabled = System.getProperty("mmr.diagnostics.enabled");
        Diagnostics.current().close();
        System.setProperty("mmr.diagnostics.dir", temporary.resolve("otp-diagnostics").toString());
        System.setProperty("mmr.diagnostics.enabled", "true");
        Diagnostics diagnostics = Diagnostics.start("otp-transport-test");
        Path log = diagnostics.logPath();
        String email = "email-private-otp@example.invalid";
        String otp = "otp-private-593481";
        String password = "Password-private-73!";
        try {
            assertNotNull(log);
            try (AuthServer server = server(request -> {
                if ("RESET_PASSWORD".equals(request.action())) {
                    throw new SQLException("smtp-private " + email + " " + otp + " " + password + " " + CHALLENGE, "08001");
                }
                return AuthResponse.error("OTP_INVALID", "Mã xác minh không hợp lệ.");
            })) {
                AuthClient client = client(server, tls.client());
                assertEquals("OTP_INVALID", client.verifyRegistration(email, otp, CHALLENGE).code());
                AuthResponse failed = client.resetPassword(email, otp, password);
                assertEquals("DATABASE_UNAVAILABLE", failed.code());
                for (String secret : List.of(email, otp, password, CHALLENGE, "smtp-private")) {
                    assertFalse(failed.message().contains(secret));
                    assertFalse(failed.toString().contains(secret));
                    assertFalse(new AuthRequest("VERIFY_REGISTRATION", null, password, null, email, null, null, otp, CHALLENGE)
                            .toString().contains(secret));
                    assertFalse(new AuthResponse(true, "OTP_REQUIRED", "Đã gửi mã.", null, null, null,
                            null, List.of(), CHALLENGE).toString().contains(secret));
                }
            }
        } finally {
            diagnostics.close();
            restoreProperty("mmr.diagnostics.dir", oldDirectory);
            restoreProperty("mmr.diagnostics.enabled", oldEnabled);
        }
        String output = Files.readString(log);
        for (String secret : List.of(email, otp, password, CHALLENGE, "smtp-private")) assertFalse(output.contains(secret));
        List<JsonObject> events = output.lines().map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
        assertTrue(events.stream().anyMatch(event -> "auth_client_request".equals(event.get("event").getAsString())
                && "VERIFY_REGISTRATION".equals(field(event, "action")) && "OTP_INVALID".equals(field(event, "response_code"))));
        assertTrue(events.stream().anyMatch(event -> "server.connection.completed".equals(event.get("event").getAsString())
                && "RESET_PASSWORD".equals(field(event, "action")) && "DATABASE_UNAVAILABLE".equals(field(event, "response_code"))));
    }

    @Test
    void passwordResetDoesNotSendCredentialsToAnUntrustedServer() throws Exception {
        AtomicInteger handled = new AtomicInteger();
        SSLContext untrusted = SSLContext.getInstance("TLS");
        untrusted.init(null, null, null);
        try (AuthServer server = server(request -> {
            handled.incrementAndGet();
            return AuthResponse.ok("Unexpected", null, null, null);
        })) {
            IOException failure = assertThrows(IOException.class, () -> client(server, untrusted)
                    .resetPassword("private@example.invalid", "614208", "Private-password-29!"));
            assertTrue(hasCause(failure, SSLHandshakeException.class));
            assertEquals(0, handled.get());
            assertTrue(client(server, tls.client()).requestPasswordReset("private@example.invalid").success());
            assertEquals(1, handled.get(), "A rejected TLS connection must not prevent subsequent trusted requests");
        }
    }

    @Test
    void malformedOtpResponsesAreRejectedAndTheNextRequestStillWorks() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (AuthServer server = server(request -> switch (calls.getAndIncrement()) {
            case 0 -> new AuthResponse(false, null, "Missing code", null, null, null);
            case 1 -> new AuthResponse(false, "OTP_INVALID", null, null, null, null);
            default -> AuthResponse.error("OTP_EXPIRED", "Mã xác minh đã hết hạn.");
        })) {
            AuthClient client = client(server, tls.client());
            assertThrows(IOException.class, () -> client.verifyRegistration("otp@example.invalid", "593481", CHALLENGE));
            assertThrows(IOException.class, () -> client.resetPassword("otp@example.invalid", "593481", "Replacement-7!"));
            AuthResponse expired = client.verifyRegistration("otp@example.invalid", "593481", CHALLENGE);
            assertFalse(expired.success());
            assertEquals("OTP_EXPIRED", expired.code());
            assertEquals(3, calls.get());
        }
    }

    private static String field(JsonObject event, String name) {
        JsonObject fields = event.getAsJsonObject("fields");
        return fields != null && fields.has(name) ? fields.get(name).getAsString() : null;
    }

    private static AuthServer server(AuthServer.Handler handler) throws IOException {
        AuthServer server = new AuthServer(tls.server(), new InetSocketAddress("127.0.0.1", 0), handler);
        server.start();
        return server;
    }

    private static AuthClient client(AuthServer server, SSLContext clientTls) {
        return new AuthClient("localhost", server.port(), clientTls, 5000);
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    private static void restoreProperty(String name, String previous) {
        if (previous == null) System.clearProperty(name);
        else System.setProperty(name, previous);
    }
}
