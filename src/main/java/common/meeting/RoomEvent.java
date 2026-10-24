package common.meeting;

/** Recipient IDs route an event to a connection; never log the body or display names. */
public record RoomEvent(
        String recipientConnectionId, String type, String roomId, long revision, Object body) {}
