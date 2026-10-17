package client.UI.components;

import client.media.NetworkStatsCalculator;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Popup;
import javafx.stage.Window;
import javafx.util.Duration;
import server.meeting.NetworkStatsService;

import java.util.Locale;

/**
 * Compact 3-bar network signal widget for the meeting bottom toolbar.
 * Displays connection quality (Green = TOT, Yellow = TRUNG_BINH, Red = KEM, Gray = no data)
 * using thresholds from NetworkStatsService.
 * Displays a popover and tooltip with real-time metrics (RTT, packet loss, jitter, bitrate, FPS, resolution).
 */
public final class NetworkSignalWidget extends Button {
    public static final Color COLOR_GOOD = Color.web("#22c55e");
    public static final Color COLOR_MEDIUM = Color.web("#eab308");
    public static final Color COLOR_POOR = Color.web("#ef4444");
    public static final Color COLOR_MUTED = Color.web("#6b7280", 0.35);
    public static final Color COLOR_NO_DATA = Color.web("#6b7280", 0.5);

    private final Rectangle bar1;
    private final Rectangle bar2;
    private final Rectangle bar3;
    private final Tooltip tooltip;
    private final Popup popover = new Popup();
    private final VBox popoverContent = new VBox(8);

    private final Label qualityBadge = Ui.label("Chưa có dữ liệu", "caption");
    private final Label rttValue = Ui.label("--", "strong");
    private final Label lossValue = Ui.label("--", "strong");
    private final Label jitterValue = Ui.label("--", "strong");
    private final Label bitrateValue = Ui.label("--", "strong");
    private final Label fpsValue = Ui.label("--", "strong");
    private final Label resolutionValue = Ui.label("--", "strong");

    private NetworkStatsCalculator.CalculatedSample currentSample;
    private String currentQuality;

    public NetworkSignalWidget() {
        setId("network-signal");
        getStyleClass().addAll("network-signal-button", "network-signal-icon");

        bar1 = createBar(3.5, 5);
        bar2 = createBar(3.5, 9);
        bar3 = createBar(3.5, 14);

        HBox bars = new HBox(2.5, bar1, bar2, bar3);
        bars.setAlignment(Pos.BOTTOM_CENTER);
        bars.setPrefHeight(16);
        bars.setMaxHeight(16);
        setGraphic(bars);

        tooltip = new Tooltip();
        tooltip.setShowDelay(Duration.millis(120));
        setTooltip(tooltip);

        buildPopover();

        setOnAction(e -> togglePopover());

        update(null);
    }

    private static Rectangle createBar(double width, double height) {
        Rectangle r = new Rectangle(width, height);
        r.setArcWidth(2);
        r.setArcHeight(2);
        return r;
    }

    private void buildPopover() {
        popover.setAutoHide(true);
        popover.setHideOnEscape(true);

        popoverContent.setId("network-stats-popover");
        popoverContent.getStyleClass().addAll("card", "network-popover");

        qualityBadge.setId("popover-quality");
        HBox header = Ui.row(8, Ui.label("Thông số mạng", "strong"), Ui.spacer(), qualityBadge);

        rttValue.setId("popover-rtt");
        lossValue.setId("popover-loss");
        jitterValue.setId("popover-jitter");
        bitrateValue.setId("popover-bitrate");
        fpsValue.setId("popover-fps");
        resolutionValue.setId("popover-resolution");

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(6);
        grid.addRow(0, Ui.label("Độ trễ (RTT, ms):", "caption"), rttValue);
        grid.addRow(1, Ui.label("Mất gói (%):", "caption"), lossValue);
        grid.addRow(2, Ui.label("Jitter (ms):", "caption"), jitterValue);
        grid.addRow(3, Ui.label("Bitrate (kbps):", "caption"), bitrateValue);
        grid.addRow(4, Ui.label("FPS:", "caption"), fpsValue);
        grid.addRow(5, Ui.label("Độ phân giải:", "caption"), resolutionValue);

        popoverContent.getChildren().addAll(header, new Separator(), grid);
        popover.getContent().add(popoverContent);
    }

    public void update(NetworkStatsCalculator.CalculatedSample sample) {
        this.currentSample = sample;
        this.currentQuality = sample != null
                ? NetworkStatsService.calculateQuality(sample.rttMs(), sample.jitterMs(), sample.lossPct())
                : null;

        updateBarsAndQuality(currentQuality);
        updateValues(sample);
        updateTooltipAndText();
    }

    private void updateBarsAndQuality(String quality) {
        qualityBadge.getStyleClass().removeAll("stat-good", "stat-fair", "stat-poor", "caption");
        getStyleClass().removeAll("quality-good", "quality-fair", "quality-poor", "quality-none");

        if ("TOT".equals(quality)) {
            bar1.setFill(COLOR_GOOD);
            bar2.setFill(COLOR_GOOD);
            bar3.setFill(COLOR_GOOD);
            qualityBadge.setText("Tốt");
            qualityBadge.getStyleClass().add("stat-good");
            getStyleClass().add("quality-good");
        } else if ("TRUNG_BINH".equals(quality)) {
            bar1.setFill(COLOR_MEDIUM);
            bar2.setFill(COLOR_MEDIUM);
            bar3.setFill(COLOR_MUTED);
            qualityBadge.setText("Trung bình");
            qualityBadge.getStyleClass().add("stat-fair");
            getStyleClass().add("quality-fair");
        } else if ("KEM".equals(quality)) {
            bar1.setFill(COLOR_POOR);
            bar2.setFill(COLOR_MUTED);
            bar3.setFill(COLOR_MUTED);
            qualityBadge.setText("Kém");
            qualityBadge.getStyleClass().add("stat-poor");
            getStyleClass().add("quality-poor");
        } else {
            bar1.setFill(COLOR_NO_DATA);
            bar2.setFill(COLOR_NO_DATA);
            bar3.setFill(COLOR_NO_DATA);
            qualityBadge.setText("Chưa có dữ liệu");
            qualityBadge.getStyleClass().add("caption");
            getStyleClass().add("quality-none");
        }
    }

    private void updateValues(NetworkStatsCalculator.CalculatedSample s) {
        rttValue.setText(s == null || s.rttMs() == null ? "--" : String.format(Locale.ROOT, "%.1f ms", s.rttMs()));
        lossValue.setText(s == null || s.lossPct() == null ? "--" : String.format(Locale.ROOT, "%.1f%%", s.lossPct()));
        jitterValue.setText(s == null || s.jitterMs() == null ? "--" : String.format(Locale.ROOT, "%.1f ms", s.jitterMs()));
        bitrateValue.setText(s == null || s.bitrateKbps() == null ? "--" : s.bitrateKbps() + " kbps");
        fpsValue.setText(s == null || s.fps() == null ? "--" : String.valueOf(s.fps()));
        resolutionValue.setText(s == null || s.width() == null || s.height() == null ? "--" : s.width() + "x" + s.height());
    }

    private void updateTooltipAndText() {
        String tip = "Chất lượng kết nối: " + qualityBadge.getText() + "\n"
                + "Độ trễ (RTT, ms): " + rttValue.getText() + "\n"
                + "Mất gói (%): " + lossValue.getText() + "\n"
                + "Jitter (ms): " + jitterValue.getText() + "\n"
                + "Bitrate (kbps): " + bitrateValue.getText() + "\n"
                + "FPS: " + fpsValue.getText() + "\n"
                + "Độ phân giải: " + resolutionValue.getText();
        tooltip.setText(tip);
        setAccessibleText(tip);
    }

    public void togglePopover() {
        if (popover.isShowing()) {
            popover.hide();
        } else {
            showPopover();
        }
    }

    public void showPopover() {
        if (getScene() == null || getScene().getWindow() == null) return;
        Window window = getScene().getWindow();
        popoverContent.applyCss();
        popoverContent.layout();
        Bounds bounds = localToScreen(getBoundsInLocal());
        if (bounds != null && window.isShowing()) {
            double w = popoverContent.prefWidth(-1);
            double h = popoverContent.prefHeight(-1);
            double x = bounds.getMinX() + (bounds.getWidth() - w) / 2.0;
            double y = bounds.getMinY() - h - 6;
            popover.show(window, Math.max(10, x), Math.max(10, y));
        } else if (window.isShowing()) {
            popover.show(window);
        }
    }

    public void hidePopover() {
        if (popover.isShowing()) popover.hide();
    }

    public boolean isPopoverShowing() {
        return popover.isShowing();
    }

    public String currentQuality() { return currentQuality; }
    public NetworkStatsCalculator.CalculatedSample currentSample() { return currentSample; }
    public Rectangle bar1() { return bar1; }
    public Rectangle bar2() { return bar2; }
    public Rectangle bar3() { return bar3; }
    public Popup popover() { return popover; }
    public Tooltip tooltip() { return tooltip; }
    public Label rttValueLabel() { return rttValue; }
    public Label lossValueLabel() { return lossValue; }
    public Label jitterValueLabel() { return jitterValue; }
    public Label bitrateValueLabel() { return bitrateValue; }
    public Label fpsValueLabel() { return fpsValue; }
    public Label resolutionValueLabel() { return resolutionValue; }
    public Label qualityBadgeLabel() { return qualityBadge; }

    public void dispose() {
        hidePopover();
    }
}
