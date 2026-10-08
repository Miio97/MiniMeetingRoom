package server.auth;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {
    private final PasswordHasher passwords = new PasswordHasher();

    @Test
    void saltedHashesVerifyExactUnicodePasswordsAndFitDatabaseColumn() {
        String password = "  Mật-khẩu-123!  ";
        String first = passwords.hash(password);
        String second = passwords.hash(password);
        assertNotEquals(first, second);
        assertTrue(first.startsWith("pbkdf2-sha256$600000$"));
        assertTrue(first.length() <= 255);
        assertTrue(passwords.verify(password, first));
        assertFalse(passwords.verify(password.strip(), first));
        assertFalse(passwords.verify("wrong-password", first));
    }

    @Test
    void malformedOrUnboundedStoredHashesNeverAuthenticate() {
        String[] invalid = {null, "", "plaintext-password", "pbkdf2-sha256$no-number$x$x",
            "pbkdf2-sha256$999999999$x$x", "pbkdf2-sha256$600000$invalid!$invalid!",
            "pbkdf2-sha256$600000$YQ$Yg", "other$600000$YQ$Yg"};
        for (String encoded : invalid) {
            assertFalse(passwords.verify("valid-password", encoded));
        }
        assertFalse(passwords.verify(null, "encoded"));
    }
}
