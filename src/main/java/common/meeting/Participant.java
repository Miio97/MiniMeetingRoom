package common.meeting;

public record Participant(
        long userId, String name, boolean host, boolean microphone, boolean camera) {}
