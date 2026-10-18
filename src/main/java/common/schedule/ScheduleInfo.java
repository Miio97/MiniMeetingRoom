package common.schedule;

/** Server-authoritative schedule data; content is private to creator. */
public record ScheduleInfo(
        long id,
        long creatorId,
        String title,
        String description,
        String startsAt,
        String endsAt,
        String repeatRule,
        Long roomId,
        String roomCode,
        String createdAt) {

    @Override
    public String toString() {
        return "ScheduleInfo[id=" + id + ", startsAt=" + startsAt + ", endsAt=" + endsAt + "]";
    }
}
