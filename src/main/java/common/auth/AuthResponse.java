package common.auth;

public record AuthResponse(boolean success, String code, String message,
                           AuthUser user, String token, String expiresAt) {
    public static AuthResponse ok(String message, AuthUser user, String token, String expiresAt) {
        return new AuthResponse(true, "OK", message, user, token, expiresAt);
    }

    public static AuthResponse error(String code, String message) {
        return new AuthResponse(false, code, message, null, null, null);
    }

    @Override public String toString() {
        return "AuthResponse[success=" + success + ", code=" + code + "]";
    }
}
