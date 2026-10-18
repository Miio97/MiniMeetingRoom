package server;

import common.auth.AuthRequest;
import common.auth.AuthResponse;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import server.auth.AuthService;
import server.rooms.RoomService;
import server.schedule.ScheduleService;

/** Authentication, room, and schedule commands share the existing TLS listener and configuration. */
public final class ApplicationService {
    private final AuthService authentication;
    private final RoomService rooms;
    private final ScheduleService schedules;

    public ApplicationService() { this(new AuthService(), new RoomService(), new ScheduleService()); }

    public ApplicationService(AuthService authentication, RoomService rooms) {
        this(authentication, rooms, new ScheduleService());
    }

    public ApplicationService(AuthService authentication, RoomService rooms, ScheduleService schedules) {
        this.authentication = Objects.requireNonNull(authentication);
        this.rooms = Objects.requireNonNull(rooms);
        this.schedules = Objects.requireNonNull(schedules);
    }

    public AuthResponse handle(AuthRequest request) throws SQLException {
        if (request == null || request.action() == null) return authentication.handle(request);
        return switch (request.action().toUpperCase(Locale.ROOT)) {
            case "CREATE_ROOM", "REOPEN_ROOM", "JOIN_ROOM", "LIST_ROOMS", "GET_ROOM", "LEAVE_ROOM" -> rooms.handle(request);
            case "CREATE_SCHEDULE", "LIST_SCHEDULES", "DELETE_SCHEDULE" -> schedules.handle(request);
            default -> authentication.handle(request);
        };
    }
}
