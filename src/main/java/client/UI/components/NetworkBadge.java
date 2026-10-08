package client.UI.components;

import javafx.scene.control.Button;

public final class NetworkBadge extends Button {
    public NetworkBadge(Runnable action) { getStyleClass().add("network-badge"); quality(45); setOnAction(e -> action.run()); }
    public void quality(double latency) {
        String status = latency < 80 ? "good" : latency < 160 ? "fair" : "poor";
        getStyleClass().removeAll("good", "fair", "poor"); getStyleClass().add(status);
        setText("▥  " + (latency < 80 ? "Tốt" : latency < 160 ? "Khá" : "Yếu")); setAccessibleText("Chất lượng mạng " + getText() + ", mở thống kê");
    }
}
