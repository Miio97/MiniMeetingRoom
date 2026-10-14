package common.chat;

import java.io.IOException;

public final class ChatException extends IOException {
    private final String code;
    public ChatException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
    public boolean admissionEnded() {
        return switch (code) {
            case "SESSION_INVALID", "NOT_IN_ROOM", "ROOM_CLOSED", "ROOM_NOT_FOUND" -> true;
            default -> false;
        };
    }
}
