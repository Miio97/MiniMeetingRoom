package client.UI.model;

/** Presentation models for room labels and participant tiles. Server DTOs remain authoritative. */
public final class MeetingUiModels {
    public static final int MAX_ROOM_CAPACITY = 50;

    private MeetingUiModels() {}

    public record Room(
            String name, String code, int people, String time, String pin, int capacity) {
        public Room(String name, String code, int people, String time, String pin) {
            this(name, code, people, time, pin, MAX_ROOM_CAPACITY);
        }

        public Room {
            if (capacity < 2 || capacity > MAX_ROOM_CAPACITY || people < 1 || people > capacity) {
                throw new IllegalArgumentException("Sức chứa phòng không hợp lệ");
            }
        }
    }

    public record Participant(
            String name, boolean host, boolean muted, boolean camera, String color) {}

    /** Empty navigation state; it never represents a persisted room. */
    public static Room emptyRoom() {
        return new Room("Phòng họp", "", 1, "", "", 20);
    }
}
