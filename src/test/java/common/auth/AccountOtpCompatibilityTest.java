package common.auth;

import java.io.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The two branches originally used the same constructor position for different secrets. */
class AccountOtpCompatibilityTest {
    @Test void legacyConstructorsKeepOtpAndAuthenticatedNewPasswordSeparate() throws Exception {
        var reset = new AuthRequest("RESET_PASSWORD", null, "Reset-password-1!", null,
                "member@example.invalid", null, null, "418250");
        assertEquals("418250", reset.otp()); assertNull(reset.newPassword());
        var change = new AuthRequest("CHANGE_PASSWORD", null, "Current-password-1!", null,
                null, "current-session", null, "New-password-2!");
        assertEquals("New-password-2!", change.newPassword()); assertNull(change.otp());
        var verify = new AuthRequest("VERIFY_REGISTRATION", null, null, null,
                "member@example.invalid", null, null, "418250", "registration-flow");
        assertEquals("418250", verify.otp()); assertEquals("registration-flow", verify.registrationChallenge());
        assertNull(verify.newPassword());
        for (var request : new AuthRequest[]{reset, change, verify}) {
            var bytes = new ByteArrayOutputStream();
            AuthWire.write(new DataOutputStream(bytes), request);
            assertEquals(request, AuthWire.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), AuthRequest.class));
        }
        assertFalse(change.toString().contains(change.newPassword()));
        assertFalse(verify.toString().contains(verify.otp()));
        assertFalse(verify.toString().contains(verify.registrationChallenge()));
    }
}
