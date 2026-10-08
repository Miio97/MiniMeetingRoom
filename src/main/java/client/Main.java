package client;

import client.UI.MainApp;
import javafx.application.Application;

/** Điểm khởi chạy giao diện client của Mini Meeting Room. */
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        Application.launch(MainApp.class, args);
    }
}
