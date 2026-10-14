package common.meeting;
public record CreateRoomParams(String name, String pin, int capacity, boolean locked) {
    @Override public String toString() { return "CreateRoomParams[capacity=" + capacity + ", locked=" + locked + "]"; }
}
