package common.meeting;

public record RoomJoinReply(String selfParticipantId, RoomSnapshot room) {}
