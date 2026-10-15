package common.meeting;
public record MemberAuthorization(String roomId, String participantId, long generation, RoomRole role,
        MediaState mediaState) { }
