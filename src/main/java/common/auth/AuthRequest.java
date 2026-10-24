package common.auth;

import common.room.RoomRequest;

/** One authentication command, carried inside a TLS connection. */
public record AuthRequest(
        String action,
        String username,
        String password,
        String displayName,
        String email,
        String token,
        RoomRequest room,
        String otp,
        String registrationChallenge,
        String newPassword,
        String clientInfo) {
    public AuthRequest(
            String action,
            String username,
            String password,
            String displayName,
            String email,
            String token,
            RoomRequest room,
            String otp,
            String registrationChallenge,
            String newPassword) {
        this(
                action,
                username,
                password,
                displayName,
                email,
                token,
                room,
                otp,
                registrationChallenge,
                newPassword,
                null);
    }

    public static AuthRequest login(String username, String password, String clientInfo) {
        return new AuthRequest(
                "LOGIN", username, password, null, null, null, null, null, null, null, clientInfo);
    }

    public static AuthRequest resumeSession(String token, String clientInfo) {
        return new AuthRequest(
                "RESUME_SESSION",
                null,
                null,
                null,
                null,
                token,
                null,
                null,
                null,
                null,
                clientInfo);
    }

    public AuthRequest(
            String action,
            String username,
            String password,
            String displayName,
            String email,
            String token,
            RoomRequest room,
            String otp,
            String registrationChallenge) {
        this(
                action,
                username,
                password,
                displayName,
                email,
                token,
                room,
                otp,
                registrationChallenge,
                null);
    }

    /** Previous branches used an eighth String for OTP, or for the CHANGE_PASSWORD new password. */
    public AuthRequest(
            String action,
            String username,
            String password,
            String displayName,
            String email,
            String token,
            RoomRequest room,
            String codeOrNewPassword) {
        this(
                action,
                username,
                password,
                displayName,
                email,
                token,
                room,
                "CHANGE_PASSWORD".equalsIgnoreCase(action) ? null : codeOrNewPassword,
                null,
                "CHANGE_PASSWORD".equalsIgnoreCase(action) ? codeOrNewPassword : null);
    }

    public AuthRequest(
            String action,
            String username,
            String password,
            String displayName,
            String email,
            String token,
            RoomRequest room) {
        this(action, username, password, displayName, email, token, room, null, null, null);
    }

    public AuthRequest(
            String action,
            String username,
            String password,
            String displayName,
            String email,
            String token) {
        this(action, username, password, displayName, email, token, null, null, null, null);
    }

    // Avoid accidental credential disclosure from logs or debugger expressions.
    @Override
    public String toString() {
        return "AuthRequest[action=" + action + "]";
    }
}
