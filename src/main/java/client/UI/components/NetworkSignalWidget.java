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

import java.util.Locale;
import java.util.Objects;

/**
 * Compact 3-bar network signal widget for the meeting bottom toolbar.
 * Displays connection quality (Green = TOT, Yellow = TRUNG_BINH, Red = KEM, Gray = no data)
 * using local client-only presentation thresholds.
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
    private final Label sourceLabel = Ui.label("", "caption");
    private final Label rttValue = Ui.label("--", "strong");
    private final Label lossValue = Ui.label("--", "strong");
    private final Label jitterValue = Ui.label("--", "strong");
    private final Label bitrateValue = Ui.label("--", "strong");
    private final Label fpsValue = Ui.label("--", "strong");
    private final Label resolutionValue = Ui.label("--", "strong");

    private NetworkStatsCalculator.CalculatedSample currentSample;
    private String currentQuality;
    private String appliedStyleQuality = "__UNSET__";
    private boolean isFallback;

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
        sourceLabel.setId("popover-source");
        sourceLabel.setVisible(false);
        sourceLabel.setManaged(false);

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

        popoverContent.getChildren().addAll(header, sourceLabel, new Separator(), grid);
        popover.getContent().add(popoverContent);
    }

    public void update(NetworkStatsCalculator.CalculatedSample sample) {
        update(sample, null);
    }

    public void update(NetworkStatsCalculator.CalculatedSample sample, client.network.NetworkMonitor.Snapshot fallback) {
        this.currentSample = sample;
        if (sample != null && !sample.isEmpty()) {
            this.isFallback = false;
            this.currentQuality = sample.quality()==null ? NetworkStatsCalculator.calculateQuality(sample.rttMs(), sample.jitterMs(), sample.lossPct()) : switch(sample.quality()) {
                case EXCELLENT, GOOD -> "TOT"; case FAIR -> "TRUNG_BINH"; case POOR, CRITICAL -> "KEM"; case UNKNOWN -> null;
            };
            updateBarsAndQuality(currentQuality);
            updateValues(sample);
            updateTooltipAndText();
        } else if (fallback != null && fallback.rttMillis() != null) {
            this.isFallback = true;
            this.currentQuality = NetworkStatsCalculator.calculateQuality(fallback.rttMillis(), fallback.jitterMillis(), null);
            updateBarsAndQuality(currentQuality);
            updateFallbackValues(fallback);
            updateTooltipAndText();
        } else {
            this.isFallback = false;
            this.currentQuality = null;
            updateBarsAndQuality(null);
            updateValues(null);
            updateTooltipAndText();
        }
    }

    private void updateBarsAndQuality(String quality) {
        if (Objects.equals(quality, appliedStyleQuality)) return;
        appliedStyleQuality = quality;
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

    private static void setLabelText(Label label, String text) {
        if (!Objects.equals(label.getText(), text)) label.setText(text);
    }

    private void updateValues(NetworkStatsCalculator.CalculatedSample s) {
        setLabelText(rttValue, s == null || s.rttMs() == null ? "--" : String.format(Locale.ROOT, "%.1f ms", s.rttMs()));
        setLabelText(lossValue, s == null || s.lossPct() == null ? "--" : String.format(Locale.ROOT, "%.1f%%", s.lossPct()));
        setLabelText(jitterValue, s == null || s.jitterMs() == null ? "--" : String.format(Locale.ROOT, "%.1f ms", s.jitterMs()));
        String br = s == null || s.bitrateKbps() == null ? "--" : s.bitrateKbps() + " kbps";
        if (s != null && s.receiveBitrateKbps() != null) br = "↑ " + (s.bitrateKbps() == null ? "--" : s.bitrateKbps()) + " · ↓ " + s.receiveBitrateKbps() + " kbps";
        setLabelText(bitrateValue, br);
        setLabelText(fpsValue, s == null || s.fps() == null ? "--" : String.valueOf(s.fps()));
        setLabelText(resolutionValue, s == null || s.width() == null || s.height() == null ? "--" : s.width() + "x" + s.height());
        if (s != null && !s.isEmpty()) {
            setLabelText(sourceLabel, "Kênh truyền: WebRTC Media");
            if (!sourceLabel.isVisible()) { sourceLabel.setVisible(true); sourceLabel.setManaged(true); }
        } else {
            setLabelText(sourceLabel, "");
            if (sourceLabel.isVisible()) { sourceLabel.setVisible(false); sourceLabel.setManaged(false); }
        }
    }

    private void updateFallbackValues(client.network.NetworkMonitor.Snapshot fb) {
        setLabelText(rttValue, fb == null || fb.rttMillis() == null ? "--" : String.format(Locale.ROOT, "%.1f ms", fb.rttMillis()));
        setLabelText(lossValue, "--");
        setLabelText(jitterValue, fb == null || fb.jitterMillis() == null ? "--" : String.format(Locale.ROOT, "%.1f ms", fb.jitterMillis()));
        setLabelText(bitrateValue, "--");
        setLabelText(fpsValue, "--");
        setLabelText(resolutionValue, "--");
        setLabelText(sourceLabel, "Kênh truyền: Máy chủ (Camera tắt)");
        if (!sourceLabel.isVisible()) { sourceLabel.setVisible(true); sourceLabel.setManaged(true); }
    }

    private void updateTooltipAndText() {
        String tip = "Chất lượng kết nối: " + qualityBadge.getText()
                + (isFallback ? " (Kênh máy chủ)" : "") + "\n"
                + "Độ trễ (RTT, ms): " + rttValue.getText() + "\n"
                + "Mất gói (%): " + lossValue.getText() + "\n"
                + "Jitter (ms): " + jitterValue.getText() + "\n"
                + "Bitrate (kbps): " + bitrateValue.getText() + "\n"
                + "FPS: " + fpsValue.getText() + "\n"
                + "Độ phân giải: " + resolutionValue.getText();
        if (!Objects.equals(tooltip.getText(), tip)) {
            tooltip.setText(tip);
            setAccessibleText(tip);
        }
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
            double screenRight = window.getX() + window.getWidth();
            if (x + w > screenRight - 12) {
                x = screenRight - w - 12;
            }
            if (x < window.getX() + 12) {
                x = window.getX() + 12;
            }

            double y;
            if (bounds.getMinY() - h - 6 < window.getY() + 10) {
                // Near the top of the window, show below the widget
                y = bounds.getMaxY() + 6;
            } else {
                // Near the bottom, show above the widget
                y = bounds.getMinY() - h - 6;
            }
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

    public boolean isFallback() { return isFallback; }
    public Label sourceLabel() { return sourceLabel; }
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
        update(null, null);
    }
}
