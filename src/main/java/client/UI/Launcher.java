package client.UI;

/** Điểm vào độc lập, không kế thừa Application để khởi động JavaFX từ classpath. */
public final class Launcher {
    private Launcher() { }
    public static void main(String[] args) { javafx.application.Application.launch(MainApp.class, args); }
}
