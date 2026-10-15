package common.meeting;
import java.util.List;
public record RoomSnapshot(String roomId, String joinCode, String name, RoomStatus status, boolean locked,
        int capacity, String hostParticipantId, long revision, List<ParticipantView> participants,
        common.room.RoomInfo collaborationRoom) {
    public RoomSnapshot { participants = List.copyOf(participants); }
    public RoomSnapshot(String roomId, String joinCode, String name, RoomStatus status, boolean locked,
            int capacity, String hostParticipantId, long revision, List<ParticipantView> participants) {
        this(roomId, joinCode, name, status, locked, capacity, hostParticipantId, revision, participants, null);
    }
}
