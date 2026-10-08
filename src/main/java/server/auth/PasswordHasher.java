package server.auth;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Versioned salted password hashes, sized for Nguoi_Dung.Mat_Khau_Bam. */
public final class PasswordHasher {
    private static final String PREFIX = "pbkdf2-sha256";
    private static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private final SecureRandom random = new SecureRandom();

    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return PREFIX + "$" + ITERATIONS + "$" + encode(salt) + "$"
                + encode(derive(password, salt, ITERATIONS));
    }

    public boolean verify(String password, String encoded) {
        if (password == null || encoded == null) {
            return false;
        }
        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4 || !PREFIX.equals(parts[0])) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 210_000 || iterations > 2_000_000) {
                return false;
            }
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
            if (salt.length < SALT_BYTES || salt.length > 64 || expected.length != KEY_BITS / 8) {
                return false;
            }
            return MessageDigest.isEqual(expected, derive(password, salt, iterations));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec key = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(key).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Password hashing is unavailable", exception);
        } finally {
            key.clearPassword();
        }
    }
}
