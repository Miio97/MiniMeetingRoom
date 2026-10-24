package common.meeting;

public record RoomPreview(
        String roomId,
        String joinCode,
        String name,
        RoomStatus status,
        boolean locked,
        int capacity,
        int participantCount) {}
