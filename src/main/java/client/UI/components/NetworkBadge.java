package client.UI.components;

import client.network.NetworkMonitor;

import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;

public final class NetworkBadge extends Button {
    public NetworkBadge(Runnable action) {
        getStyleClass().add("network-badge");
        setId("network-badge");
        setMinWidth(142);
        setPrefWidth(142);
        setMaxWidth(142);
        setTooltip(new Tooltip("Thống kê kết nối máy chủ"));
        quality(NetworkMonitor.Quality.WAITING);
        setOnAction(e -> action.run());
    }

    public static String label(NetworkMonitor.Quality quality) {
        return switch (quality) {
            case WAITING -> "Đang đo";
            case GOOD -> "Tốt";
            case FAIR -> "Khá";
            case POOR -> "Yếu";
            case DISCONNECTED -> "Mất kết nối";
            case STOPPED -> "Đã dừng";
        };
    }

    public void quality(NetworkMonitor.Quality quality) {
        String status =
                switch (quality) {
                    case GOOD -> "good";
                    case FAIR -> "fair";
                    case POOR, DISCONNECTED -> "poor";
                    case WAITING, STOPPED -> "waiting";
                };
        getStyleClass().removeAll("good", "fair", "poor", "waiting");
        getStyleClass().add(status);
        setText("▥  " + label(quality));
        setAccessibleText("Chất lượng kết nối " + label(quality) + ", mở thống kê");
    }
}
