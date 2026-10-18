package common.schedule;

/** Parameters for schedule commands. Identity is established via session token. */
public record ScheduleRequest(
        Long id,
        String title,
        String description,
        String startsAt,
        String endsAt,
        String repeatRule,
        Long roomId,
        String roomCode,
        String from,
        String to) {

    public ScheduleRequest(String title, String description, String startsAt, String endsAt,
                           String repeatRule, String roomCode) {
        this(null, title, description, startsAt, endsAt, repeatRule, null, roomCode, null, null);
    }

    public static ScheduleRequest list(String from, String to) {
        return new ScheduleRequest(null, null, null, null, null, null, null, null, from, to);
    }

    public static ScheduleRequest delete(long id) {
        return new ScheduleRequest(id, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public String toString() {
        return "ScheduleRequest[id=" + id + ", startsAt=" + startsAt + ", from=" + from + ", to=" + to + "]";
    }
}
