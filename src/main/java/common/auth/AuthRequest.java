package common.auth;

/** One authentication command, carried inside a TLS connection. */
public record AuthRequest(String action, String username, String password,
                          String displayName, String email, String token) {
    // Avoid accidental credential disclosure from logs or debugger expressions.
    @Override public String toString() { return "AuthRequest[action=" + action + "]"; }
}
