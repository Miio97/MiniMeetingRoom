package server.meeting;
/** Fixed code only. Never include PIN, tokens, SQL, or display names in exceptions. */
public final class RoomException extends RuntimeException {
    private final String code;
    public RoomException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
