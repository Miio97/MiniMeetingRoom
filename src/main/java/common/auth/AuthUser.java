package common.auth;

/** Public account data. Password hashes never leave the server. */
public record AuthUser(long id, String username, String displayName, String email, boolean emailVerified) {
    /** Existing servers/accounts have no verified email until the OTP flow supplies this state. */
    public AuthUser(long id, String username, String displayName, String email) {
        this(id, username, displayName, email, false);
    }

    public AuthUser {
        emailVerified = emailVerified && email != null && !email.isBlank();
    }
}
