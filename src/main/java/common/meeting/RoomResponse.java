package common.meeting;

import java.util.List;

public record RoomResponse(
        boolean success,
        String code,
        String message,
        RoomView room,
        String ticket,
        List<Participant> members,
        List<Participant> waiting,
        boolean pending) {
    public RoomResponse {
        members = members == null ? List.of() : List.copyOf(members);
        waiting = waiting == null ? List.of() : List.copyOf(waiting);
    }

    public static RoomResponse error(String code, String message) {
        return new RoomResponse(false, code, message, null, null, null, null, false);
    }

    @Override
    public String toString() {
        return "RoomResponse[success=" + success + ", code=" + code + "]";
    }
}
