package common.signaling;

import com.google.gson.JsonObject;

/** Control envelope. Never log the body: it may contain login tokens, PIN, SDP or ICE. */
public record SignalMessage(int version, String type, String requestId, String serverEpoch,
        String connectionId, long generation, String roomId, String participantId,
        Long revision, JsonObject body) {
    public static final int VERSION = 1;

    public SignalMessage {
        if (version != VERSION || type == null || !type.matches("[A-Z_]{2,48}") || generation < 0
                || !safeId(requestId) || !safeId(serverEpoch) || !safeId(connectionId)
                || !safeId(roomId) || !safeId(participantId)) {
            throw new IllegalArgumentException("INVALID_ENVELOPE");
        }
        body = body == null ? new JsonObject() : body;
    }

    private static boolean safeId(String value) {
        return value == null || value.matches("[A-Za-z0-9_-]{1,96}");
    }

    @Override public String toString() { return "SignalMessage[type=" + type + ", body=REDACTED]"; }
}
