package common.meeting;

public record RoomRequest(
        String action,
        String token,
        long roomId,
        String ticket,
        String name,
        String code,
        String pin,
        int capacity,
        long target,
        Boolean locked,
        Boolean approval,
        Boolean microphone,
        Boolean camera) {
    public static RoomRequest create(String token, String name, String pin, int capacity) {
        return new RoomRequest(
                "ROOM_CREATE",
                token,
                0,
                null,
                name,
                null,
                pin,
                capacity,
                0,
                null,
                null,
                null,
                null);
    }

    public static RoomRequest join(String token, String code, String pin) {
        return new RoomRequest(
                "ROOM_JOIN", token, 0, null, null, code, pin, 0, 0, null, null, null, null);
    }

    public static RoomRequest command(
            String action, String token, long room, String ticket, long target) {
        return new RoomRequest(
                action, token, room, ticket, null, null, null, 0, target, null, null, null, null);
    }

    public static RoomRequest settings(
            String token, long room, String ticket, Boolean locked, Boolean approval) {
        return new RoomRequest(
                "ROOM_SETTINGS",
                token,
                room,
                ticket,
                null,
                null,
                null,
                0,
                0,
                locked,
                approval,
                null,
                null);
    }

    public static RoomRequest media(
            String token, long room, String ticket, Boolean mic, Boolean camera) {
        return new RoomRequest(
                "ROOM_MEDIA", token, room, ticket, null, null, null, 0, 0, null, null, mic, camera);
    }

    @Override
    public String toString() {
        return "RoomRequest[action=" + action + "]";
    }
}
