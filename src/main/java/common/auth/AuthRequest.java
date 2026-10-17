package common.auth;

import common.room.RoomRequest;

/** One authentication command, carried inside a TLS connection. */
public record AuthRequest(String action, String username, String password,
                          String displayName, String email, String token, RoomRequest room, String otp,
                          String registrationChallenge) {
    public AuthRequest(String action, String username, String password,
                       String displayName, String email, String token, RoomRequest room, String otp) {
        this(action, username, password, displayName, email, token, room, otp, null);
    }
    public AuthRequest(String action, String username, String password,
                       String displayName, String email, String token, RoomRequest room) {
        this(action, username, password, displayName, email, token, room, null);
    }
    public AuthRequest(String action, String username, String password,
                       String displayName, String email, String token) {
        this(action, username, password, displayName, email, token, null, null);
    }
    // Avoid accidental credential disclosure from logs or debugger expressions.
    @Override public String toString() { return "AuthRequest[action=" + action + "]"; }
}
