package server;

/** Điểm khởi chạy chung cho auth, phòng, signaling, truyền tệp và media server. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        MeetingServerMain.main(args);
    }
}
