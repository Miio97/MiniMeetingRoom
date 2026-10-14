package common.chat;

/** Duc: sender identity, message ID and time are assigned by the server. */
public record ChatMessage(long id, long roomId, long userId, String name, String text, String sentAt) {
    @Override public String toString() { return "ChatMessage[id=" + id + "]"; }
}
