package common.auth;

import common.room.RoomInfo;
import java.util.List;

public record AuthResponse(boolean success, String code, String message,
                           AuthUser user, String token, String expiresAt, RoomInfo room, List<RoomInfo> rooms) {
    public AuthResponse {
        rooms = rooms == null ? List.of() : List.copyOf(rooms);
    }

    public AuthResponse(boolean success, String code, String message,
                        AuthUser user, String token, String expiresAt) {
        this(success, code, message, user, token, expiresAt, null, List.of());
    }
    public static AuthResponse ok(String message, AuthUser user, String token, String expiresAt) {
        return new AuthResponse(true, "OK", message, user, token, expiresAt);
    }

    public static AuthResponse error(String code, String message) {
        return new AuthResponse(false, code, message, null, null, null);
    }

    public static AuthResponse roomOk(String message, RoomInfo room) {
        return new AuthResponse(true, "OK", message, null, null, null, room, List.of());
    }

    public static AuthResponse roomsOk(String message, List<RoomInfo> rooms) {
        return new AuthResponse(true, "OK", message, null, null, null, null, rooms);
    }

    @Override public String toString() {
        return "AuthResponse[success=" + success + ", code=" + code + "]";
    }
}
