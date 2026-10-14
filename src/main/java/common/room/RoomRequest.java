package common.room;

/** Parameters for room commands. Identity and permissions come from the session token. */
public record RoomRequest(String name, String pin, String identifier, int capacity) {
    @Override public String toString() { return "RoomRequest[capacity=" + capacity + "]"; }
}
