package common.chat;

/** Duc: room chat uses the authenticated session and the current admission ticket. */
public record ChatRequest(String action, String token, long roomId, String ticket, String text) {
    public static ChatRequest listen(String token, long room, String ticket) {
        return new ChatRequest("CHAT_LISTEN", token, room, ticket, null);
    }

    public static ChatRequest send(String token, long room, String ticket, String text) {
        return new ChatRequest("CHAT_SEND", token, room, ticket, text);
    }

    @Override
    public String toString() {
        return "ChatRequest[action=" + action + "]";
    }
}
