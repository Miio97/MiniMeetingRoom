package client.UI.dialogs;

import client.UI.components.Ui;
import client.UI.mock.MockData;
import javafx.animation.*;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import javafx.util.Duration;
import java.util.Locale;

public final class NetworkStatsDialog extends Dialog<Void> {
    private final Timeline ticker;
    private int seconds;
    public NetworkStatsDialog(Window owner) {
        initOwner(owner); setTitle("Thống kê mạng"); setHeaderText("Chất lượng kết nối · Mô phỏng"); Ui.style(this);
        MockData.Network initial = MockData.network();
        Label latency = Ui.label("45 ms", "stat-value"), jitter = Ui.label("8 ms", "stat-value"), loss = Ui.label("0.8%", "stat-value"), bitrate = Ui.label("1.8 Mbps", "stat-value"), fps = Ui.label("30", "stat-value"), resolution = Ui.label(initial.resolution(), "stat-value");
        GridPane metrics = new GridPane(); metrics.setHgap(24); metrics.setVgap(20);
        Label[] values = {latency, jitter, loss, bitrate, fps, resolution}; String[] names = {"LATENCY", "JITTER", "PACKET LOSS", "BITRATE", "FPS", "RESOLUTION"};
        for (int i = 0; i < values.length; i++) { VBox cell = Ui.column(6, Ui.label(names[i], "eyebrow"), values[i]); cell.setPrefWidth(140); metrics.add(cell, i % 3, i / 3); }
        NumberAxis x = new NumberAxis(-29, 0, 5); x.setLabel("Thời gian (giây)"); x.setAutoRanging(false); x.setForceZeroInRange(false);
        NumberAxis y = new NumberAxis(0, 100, 25); y.setLabel("ms");
        LineChart<Number, Number> chart = new LineChart<>(x, y); chart.setLegendVisible(false); chart.setCreateSymbols(false); chart.setAnimated(false); chart.setPrefSize(490, 230);
        XYChart.Series<Number, Number> series = new XYChart.Series<>(); for (int i = -29; i <= 0; i++) series.getData().add(new XYChart.Data<>(i, 45)); chart.getData().add(series);
        VBox content = Ui.column(20, metrics, Ui.label("Latency • 30 giây gần nhất", "strong"), chart, Ui.label("Số liệu giả lập, không đo lưu lượng mạng thực tế.", "caption")); getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE); ((Button) getDialogPane().lookupButton(ButtonType.CLOSE)).setText("Đóng");
        color(latency, initial.latency(), 80, 160); color(jitter, 8, 20, 40); color(loss, .8, 1, 3);
        // Giữ đúng 30 mẫu và hủy timer khi dialog đóng.
        ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            double l = 45 + Math.random() * 14 - 7, j = 8 + Math.random() * 4 - 2, p = .8 + Math.random() * .6 - .3;
            latency.setText(String.format(Locale.ROOT, "%.0f ms", l)); jitter.setText(String.format(Locale.ROOT, "%.1f ms", j)); loss.setText(String.format(Locale.ROOT, "%.1f%%", p)); bitrate.setText(String.format(Locale.ROOT, "%.1f Mbps", 1.8 + Math.random() * .2 - .1)); fps.setText(String.valueOf(29 + (int) (Math.random() * 2)));
            color(latency, l, 80, 160); color(jitter, j, 20, 40); color(loss, p, 1, 3);
            series.getData().removeFirst(); series.getData().add(new XYChart.Data<>(++seconds, l)); x.setLowerBound(seconds - 29); x.setUpperBound(seconds);
        })); ticker.setCycleCount(Animation.INDEFINITE);
        setOnShown(e -> ticker.play()); setOnHidden(e -> ticker.stop());
    }
    private static void color(Label l, double value, double good, double fair) {
        l.getStyleClass().removeAll("stat-good", "stat-fair", "stat-poor");
        l.getStyleClass().add(value < good ? "stat-good" : value < fair ? "stat-fair" : "stat-poor");
    }
}
