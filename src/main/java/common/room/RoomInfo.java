package common.room;

import java.util.List;

/** Server-authoritative room data; PINs and PIN hashes are never included. */
public record RoomInfo(long id, String name, String code, long hostId, String createdAt,
                       String status, boolean locked, int capacity, int people,
                       List<RoomParticipant> participants) {
    public RoomInfo {
        participants = participants == null ? List.of() : List.copyOf(participants);
    }
}
