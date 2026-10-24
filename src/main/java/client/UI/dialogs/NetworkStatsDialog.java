package client.UI.dialogs;

import client.UI.components.NetworkBadge;
import client.UI.components.Ui;
import client.network.NetworkMonitor;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class NetworkStatsDialog extends Dialog<Void> {
    private final NetworkMonitor monitor;
    private final Timeline ticker;
    private final Label latency = value("latency"),
            jitter = value("jitter"),
            bitrate = value("bitrate");
    private final Label status = Ui.label("", "strong"),
            traffic = Ui.label("", "caption"),
            errors = Ui.label("", "caption");

    public NetworkStatsDialog(Window owner, NetworkMonitor monitor) {
        this.monitor = Objects.requireNonNull(monitor);
        initOwner(owner);
        setTitle("Thống kê mạng");
        setHeaderText("Chất lượng kết nối");
        Ui.style(this);
        Label loss = value("loss"), fps = value("fps"), resolution = value("resolution");
        for (Label missing : List.of(loss, fps, resolution))
            missing.setStyle("-fx-font-size: 22px; -fx-text-fill: -text-muted;");
        tip(
                latency,
                "Thời gian gửi yêu cầu phòng đến khi nhận đủ phản hồi, sau bắt tay TLS; gồm thời"
                        + " gian xử lý máy chủ.");
        tip(
                jitter,
                "Trung bình độ chênh lệch giữa hai thời gian phản hồi liên tiếp trong 30 giây."
                        + " Không phải jitter RTP.");
        tip(
                loss,
                "Không khả dụng: socket TCP/TLS hiện tại không cung cấp bộ đếm mất gói RTP hoặc TCP"
                        + " truyền lại.");
        tip(
                bitrate,
                "Lưu lượng JSON gửi + nhận của phòng và chat, gồm 4 byte độ dài mỗi thông điệp;"
                        + " trung bình 5–6 giây. Không gồm header TCP/IP, TLS hay truyền lại; không"
                        + " phải tốc độ đường truyền tối đa.");
        tip(fps, "Chưa có luồng video thực tế để đo FPS.");
        tip(resolution, "Chưa có luồng video thực tế để đo độ phân giải.");
        GridPane metrics = new GridPane();
        metrics.setHgap(16);
        metrics.setVgap(20);
        for (int i = 0; i < 3; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / 3);
            metrics.getColumnConstraints().add(column);
        }
        Label[] values = {latency, jitter, loss, bitrate, fps, resolution};
        String[] names = {
            "PHẢN HỒI (RTT)",
            "DAO ĐỘNG RTT",
            "PACKET LOSS",
            "BITRATE ỨNG DỤNG",
            "FPS",
            "ĐỘ PHÂN GIẢI"
        };
        for (int i = 0; i < values.length; i++) {
            Label name = Ui.label(names[i], "eyebrow");
            name.setWrapText(true);
            VBox cell = Ui.column(6, name, values[i]);
            cell.setMinWidth(0);
            metrics.add(cell, i % 3, i / 3);
        }
        status.setId("network-status");
        traffic.setId("network-traffic");
        errors.setId("network-errors");
        traffic.setWrapText(true);
        errors.setWrapText(true);
        Label unavailable =
                Ui.label(
                        "Packet loss: không khả dụng qua TCP. Chưa có luồng video để đo FPS và độ"
                                + " phân giải.",
                        "caption");
        unavailable.setWrapText(true);
        VBox content =
                Ui.column(
                        16,
                        metrics,
                        Ui.row(
                                12,
                                Ui.label("Phản hồi máy chủ · 30 giây gần nhất", "strong"),
                                Ui.spacer(),
                                status),
                        Ui.column(6, traffic, errors, unavailable));
        content.setPrefWidth(490);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ((Button) getDialogPane().lookupButton(ButtonType.CLOSE)).setText("Đóng");
        ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> refresh()));
        ticker.setCycleCount(Animation.INDEFINITE);
        setOnShown(
                e -> {
                    refresh();
                    ticker.play();
                });
        setOnHidden(e -> dispose());
        refresh();
    }

    private static Label value(String id) {
        Label value = Ui.label("--", "stat-value");
        value.setId("network-" + id);
        value.setStyle("-fx-font-size: 22px;");
        value.setMinWidth(0);
        value.setMaxWidth(Double.MAX_VALUE);
        return value;
    }

    private static void tip(Label value, String text) {
        value.setTooltip(new Tooltip(text));
    }

    public void refresh() {
        NetworkMonitor.Snapshot sample = monitor.snapshot();
        latency.setText(
                sample.rttMillis() == null
                        ? "--"
                        : String.format(Locale.ROOT, "%.1f ms", sample.rttMillis()));
        jitter.setText(
                sample.jitterMillis() == null
                        ? "--"
                        : String.format(Locale.ROOT, "%.1f ms", sample.jitterMillis()));
        bitrate.setText(rate(sample.sendBitsPerSecond() + sample.receiveBitsPerSecond()));
        color(latency, sample.rttMillis(), 80, 160);
        color(jitter, sample.jitterMillis(), 20, 40);
        status.setText(NetworkBadge.label(sample.quality()));
        status.getStyleClass().removeAll("stat-good", "stat-fair", "stat-poor");
        switch (sample.quality()) {
            case GOOD -> status.getStyleClass().add("stat-good");
            case FAIR -> status.getStyleClass().add("stat-fair");
            case POOR, DISCONNECTED -> status.getStyleClass().add("stat-poor");
            default -> {}
        }
        traffic.setText(
                "Gửi: "
                        + rate(sample.sendBitsPerSecond())
                        + " · Nhận: "
                        + rate(sample.receiveBitsPerSecond())
                        + " · Tổng: "
                        + bytes(sample.bytesSent() + sample.bytesReceived()));
        errors.setText(
                sample.requests() == 0
                        ? "Chưa có yêu cầu hoàn tất."
                        : String.format(
                                Locale.ROOT,
                                "Yêu cầu lỗi: %d/%d (%.1f%%) · 30 giây gần nhất",
                                sample.failures(),
                                sample.requests(),
                                sample.failurePercent()));
    }

    private static String rate(double bits) {
        if (bits >= 1_000_000) return String.format(Locale.ROOT, "%.1f Mbps", bits / 1_000_000);
        if (bits >= 1000) return String.format(Locale.ROOT, "%.1f kbps", bits / 1000);
        return String.format(Locale.ROOT, "%.0f bps", bits);
    }

    private static String bytes(long bytes) {
        return bytes < 1024 ? bytes + " B" : String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
    }

    private static void color(Label label, Double value, double good, double fair) {
        label.getStyleClass().removeAll("stat-good", "stat-fair", "stat-poor");
        if (value != null)
            label.getStyleClass()
                    .add(value < good ? "stat-good" : value < fair ? "stat-fair" : "stat-poor");
    }

    public void dispose() {
        ticker.stop();
    }
}
