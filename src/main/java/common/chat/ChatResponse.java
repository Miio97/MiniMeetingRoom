package common.chat;

import java.util.List;

public record ChatResponse(boolean success, String code, String message, List<ChatMessage> messages) {
    public ChatResponse { messages = messages == null ? List.of() : List.copyOf(messages); }
    public static ChatResponse ok(List<ChatMessage> messages) { return new ChatResponse(true, "OK", "", messages); }
    public static ChatResponse error(String code, String message) { return new ChatResponse(false, code, message, List.of()); }
    @Override public String toString() { return "ChatResponse[success=" + success + ", code=" + code + "]"; }
}
