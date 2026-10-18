package common.auth;

import common.room.RoomInfo;
import common.schedule.ScheduleInfo;
import java.util.List;

public record AuthResponse(boolean success, String code, String message,
                           AuthUser user, String token, String expiresAt,
                           RoomInfo room, List<RoomInfo> rooms,
                           ScheduleInfo schedule, List<ScheduleInfo> schedules, Boolean hasMoreRooms, String registrationChallenge) {
    public AuthResponse(boolean success, String code, String message,
                        AuthUser user, String token, String expiresAt, RoomInfo room, List<RoomInfo> rooms,
                        ScheduleInfo schedule, List<ScheduleInfo> schedules, Boolean hasMoreRooms) {
        this(success, code, message, user, token, expiresAt, room, rooms, schedule, schedules, hasMoreRooms, null);
    }

    public AuthResponse(boolean success, String code, String message,
                        AuthUser user, String token, String expiresAt, RoomInfo room, List<RoomInfo> rooms,
                        String registrationChallenge) {
        this(success, code, message, user, token, expiresAt, room, rooms, null, List.of(), null, registrationChallenge);
    }

    public AuthResponse(boolean success, String code, String message,
                        AuthUser user, String token, String expiresAt,
                        RoomInfo room, List<RoomInfo> rooms, ScheduleInfo schedule, List<ScheduleInfo> schedules) {
        this(success, code, message, user, token, expiresAt, room, rooms, schedule, schedules, null);
    }

    public static AuthResponse roomsPage(List<RoomInfo> rooms, boolean hasMore) {
        return new AuthResponse(true, "OK", "Đã tải danh sách phòng.", null, null, null,
                null, rooms, null, List.of(), hasMore);
    }

    public AuthResponse {
        rooms = rooms == null ? List.of() : List.copyOf(rooms);
        schedules = schedules == null ? List.of() : List.copyOf(schedules);
    }

    public AuthResponse(boolean success, String code, String message,
                        AuthUser user, String token, String expiresAt, RoomInfo room, List<RoomInfo> rooms) {
        this(success, code, message, user, token, expiresAt, room, rooms, null, List.of());
    }

    public AuthResponse(boolean success, String code, String message,
                        AuthUser user, String token, String expiresAt) {
        this(success, code, message, user, token, expiresAt, null, List.of(), null, List.of());
    }

    public static AuthResponse ok(String message, AuthUser user, String token, String expiresAt) {
        return new AuthResponse(true, "OK", message, user, token, expiresAt);
    }

    public static AuthResponse error(String code, String message) {
        return new AuthResponse(false, code, message, null, null, null, null, List.of(), null, List.of());
    }

    public static AuthResponse roomOk(String message, RoomInfo room) {
        return new AuthResponse(true, "OK", message, null, null, null, room, List.of(), null, List.of());
    }

    public static AuthResponse roomsOk(String message, List<RoomInfo> rooms) {
        return new AuthResponse(true, "OK", message, null, null, null, null, rooms, null, List.of());
    }

    public static AuthResponse scheduleOk(String message, ScheduleInfo schedule) {
        return new AuthResponse(true, "OK", message, null, null, null, null, List.of(), schedule, List.of());
    }

    public static AuthResponse schedulesOk(String message, List<ScheduleInfo> schedules) {
        return new AuthResponse(true, "OK", message, null, null, null, null, List.of(), null, schedules);
    }

    @Override public String toString() {
        return "AuthResponse[success=" + success + ", code=" + code + "]";
    }
}
