package client.UI.mock;

import java.util.List;

/** Dữ liệu minh họa; không đọc database, camera, mạng hay hệ thống tệp. */
public final class MockData {
    public static final int MAX_ROOM_CAPACITY = 250;
    public record Room(String name, String code, int people, String time, String pin, int capacity) {
        public Room(String name, String code, int people, String time, String pin) { this(name, code, people, time, pin, 50); }
        public Room {
            if (capacity < 2 || capacity > MAX_ROOM_CAPACITY || people < 1 || people > capacity) throw new IllegalArgumentException("Sức chứa phòng không hợp lệ");
        }
    }
    public record Participant(String name, boolean host, boolean muted, boolean camera, String color) { }
    public record Message(String name, String text, String time, boolean mine) { }
    public record SharedFile(String name, String size, String sender) { }
    public record Network(double latency, double jitter, double loss, double bitrate, int fps, String resolution) { }
    private MockData() { }
    public static List<Room> rooms() { return List.of(
        new Room("Design sync · Nhóm sản phẩm", "MMR-2048", 6, "Hôm nay, 09:30", "1234", 10),
        new Room("Cà phê & ý tưởng", "MMR-1086", 3, "Hôm qua, 15:00", "1234", 10),
        new Room("Sprint planning", "MMR-3920", 8, "05/10, 10:00", "1234", 10),
        new Room("Nhóm học Java", "MMR-7712", 2, "04/10, 20:30", "1234", 10)); }
    public static List<Participant> participants(String user) { return List.of(
        new Participant(user, true, false, true, "#424080"),
        new Participant("Hoàng Nam", false, false, true, "#22545e"),
        new Participant("Thảo Linh", false, true, true, "#6a405b"),
        new Participant("Đức Huy", false, false, true, "#3d526f"),
        new Participant("Ngọc Mai", false, false, false, "#64503b"),
        new Participant("Tuấn Kiệt", false, false, true, "#365c50")); }
    public static List<Message> messages() { return List.of(
        new Message("Hoàng Nam", "Chào mọi người! Mình đã sẵn sàng rồi 👋", "09:30", false),
        new Message("Thảo Linh", "Mình vừa gửi bản thiết kế mới ở tab Tệp nhé.", "09:31", false),
        new Message("Bạn", "Cảm ơn Linh, cùng xem qua nhé!", "09:32", true)); }
    public static List<SharedFile> files() { return List.of(
        new SharedFile("UI-review.pdf", "2.4 MB", "Thảo Linh"),
        new SharedFile("Sprint-notes.docx", "148 KB", "Hoàng Nam"),
        new SharedFile("Moodboard.png", "1.8 MB", "Ngọc Mai")); }
    public static List<String> cameras() { return List.of("Camera tích hợp (demo)", "USB Webcam (demo)"); }
    public static List<String> microphones() { return List.of("Microphone mặc định (demo)", "Headset Microphone (demo)"); }
    public static List<String> speakers() { return List.of("Loa máy tính (demo)", "Tai nghe (demo)"); }
    public static Network network() { return new Network(45, 8, 0.8, 1.8, 30, "720p"); }
    public static String initials(String name) {
        if (name.equals("Nguyễn Minh Hiếu")) return "NH";
        if (name.isBlank()) return "?";
        String[] parts = name.strip().split("\\s+");
        return parts.length == 1 ? parts[0].substring(0, 1).toUpperCase() :
            (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)).toUpperCase();
    }
    public static String avatarColor(String name) {
        String[] colors = {"#6C5CE7", "#268a80", "#9b587d", "#517da6", "#957346", "#568567"};
        return colors[Math.floorMod(name.hashCode(), colors.length)];
    }
    public static String avatarGradient(String name) {
        String[] colors = {"#6C5CE7, #8E7CFF", "#00B894, #00CEC9", "#FF9F43, #FF7675", "#FD79A8, #E84393", "#0984E3, #74B9FF"};
        return "linear-gradient(to bottom right, " + colors[Math.floorMod(name.hashCode(), colors.length)] + ")";
    }
    public static String duration(long seconds) { return String.format("%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60); }
}
