package common.auth;

import common.room.RoomRequest;

/** One authentication command, carried inside a TLS connection. */
public record AuthRequest(String action, String username, String password,
                          String displayName, String email, String token, RoomRequest room) {
    public AuthRequest(String action, String username, String password,
                       String displayName, String email, String token) {
        this(action, username, password, displayName, email, token, null);
    }
    // Avoid accidental credential disclosure from logs or debugger expressions.
    @Override public String toString() { return "AuthRequest[action=" + action + "]"; }
}
