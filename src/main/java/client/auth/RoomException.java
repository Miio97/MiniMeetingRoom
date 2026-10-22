package client.auth;

import java.io.IOException;

/** A server rejection is distinct from a temporary transport failure. */
public final class RoomException extends IOException {
    private final String code;

    public RoomException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }

    public boolean admissionEnded() {
        return switch (code) {
            case "SESSION_INVALID", "NOT_IN_ROOM", "ROOM_CLOSED", "ROOM_NOT_FOUND", "DEVICE_REPLACED" -> true;
            default -> false;
        };
    }
}
