package server;

import common.auth.AuthRequest;
import common.auth.AuthResponse;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import server.auth.AuthService;
import server.rooms.RoomService;

/** Authentication and room commands share the existing TLS listener and configuration. */
public final class ApplicationService {
    private final AuthService authentication;
    private final RoomService rooms;

    public ApplicationService() { this(new AuthService(), new RoomService()); }

    public ApplicationService(AuthService authentication, RoomService rooms) {
        this.authentication = Objects.requireNonNull(authentication);
        this.rooms = Objects.requireNonNull(rooms);
    }

    public AuthResponse handle(AuthRequest request) throws SQLException {
        if (request == null || request.action() == null) return authentication.handle(request);
        return switch (request.action().toUpperCase(Locale.ROOT)) {
            case "CREATE_ROOM", "JOIN_ROOM", "LIST_ROOMS", "GET_ROOM", "LEAVE_ROOM" -> rooms.handle(request);
            default -> authentication.handle(request);
        };
    }
}
