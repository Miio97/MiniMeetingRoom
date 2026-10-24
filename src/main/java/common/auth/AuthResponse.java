package common.auth;

import common.room.RoomInfo;

import java.util.List;

public record AuthResponse(
        boolean success,
        String code,
        String message,
        AuthUser user,
        String token,
        String expiresAt,
        RoomInfo room,
        List<RoomInfo> rooms,
        Boolean hasMoreRooms,
        String registrationChallenge) {
    public AuthResponse(
            boolean success,
            String code,
            String message,
            AuthUser user,
            String token,
            String expiresAt,
            RoomInfo room,
            List<RoomInfo> rooms,
            Boolean hasMoreRooms) {
        this(success, code, message, user, token, expiresAt, room, rooms, hasMoreRooms, null);
    }

    public AuthResponse(
            boolean success,
            String code,
            String message,
            AuthUser user,
            String token,
            String expiresAt,
            RoomInfo room,
            List<RoomInfo> rooms,
            String registrationChallenge) {
        this(
                success,
                code,
                message,
                user,
                token,
                expiresAt,
                room,
                rooms,
                null,
                registrationChallenge);
    }

    public static AuthResponse roomsPage(List<RoomInfo> rooms, boolean hasMore) {
        return new AuthResponse(
                true,
                "OK",
                "Đã tải danh sách phòng.",
                null,
                null,
                null,
                null,
                rooms,
                hasMore,
                null);
    }

    public AuthResponse {
        rooms = rooms == null ? List.of() : List.copyOf(rooms);
    }

    public AuthResponse(
            boolean success,
            String code,
            String message,
            AuthUser user,
            String token,
            String expiresAt,
            RoomInfo room,
            List<RoomInfo> rooms) {
        this(success, code, message, user, token, expiresAt, room, rooms, null, null);
    }

    public AuthResponse(
            boolean success,
            String code,
            String message,
            AuthUser user,
            String token,
            String expiresAt) {
        this(success, code, message, user, token, expiresAt, null, List.of(), null, null);
    }

    public static AuthResponse ok(String message, AuthUser user, String token, String expiresAt) {
        return new AuthResponse(true, "OK", message, user, token, expiresAt);
    }

    public static AuthResponse error(String code, String message) {
        return new AuthResponse(
                false, code, message, null, null, null, null, List.of(), null, null);
    }

    public static AuthResponse roomOk(String message, RoomInfo room) {
        return new AuthResponse(true, "OK", message, null, null, null, room, List.of(), null, null);
    }

    public static AuthResponse roomsOk(String message, List<RoomInfo> rooms) {
        return new AuthResponse(true, "OK", message, null, null, null, null, rooms, null, null);
    }

    @Override
    public String toString() {
        return "AuthResponse[success=" + success + ", code=" + code + "]";
    }
}
