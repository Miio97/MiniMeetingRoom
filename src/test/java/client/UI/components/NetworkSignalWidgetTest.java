package client.UI.components;

import client.media.NetworkStatsCalculator.CalculatedSample;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import support.FxTestRuntime;

import static org.junit.jupiter.api.Assertions.*;

class NetworkSignalWidgetTest {

    @BeforeAll
    static void initFx() throws Exception {
        FxTestRuntime.start();
    }

    @Test
    void initialStateDisplaysNoDataAndGrayBars() throws Exception {
        FxTestRuntime.call(() -> {
            NetworkSignalWidget widget = new NetworkSignalWidget();
            assertNull(widget.currentSample());
            assertNull(widget.currentQuality());
            assertEquals("Chưa có dữ liệu", widget.qualityBadgeLabel().getText());
            assertEquals("--", widget.rttValueLabel().getText());
            assertEquals("--", widget.lossValueLabel().getText());
            assertEquals("--", widget.jitterValueLabel().getText());
            assertEquals("--", widget.bitrateValueLabel().getText());
            assertEquals("--", widget.fpsValueLabel().getText());
            assertEquals("--", widget.resolutionValueLabel().getText());

            assertEquals(NetworkSignalWidget.COLOR_NO_DATA, widget.bar1().getFill());
            assertEquals(NetworkSignalWidget.COLOR_NO_DATA, widget.bar2().getFill());
            assertEquals(NetworkSignalWidget.COLOR_NO_DATA, widget.bar3().getFill());

            assertTrue(widget.tooltip().getText().contains("Chưa có dữ liệu"));
            assertTrue(widget.tooltip().getText().contains("Độ trễ (RTT, ms): --"));
            return null;
        });
    }

    @Test
    void goodQualityTurnsAllBarsGreenAndFormatsMetrics() throws Exception {
        FxTestRuntime.call(() -> {
            NetworkSignalWidget widget = new NetworkSignalWidget();
            CalculatedSample sample = new CalculatedSample(45.2, 8.5, 0.4, 1800, 30, 1280, 720);
            widget.update(sample);

            assertEquals("TOT", widget.currentQuality());
            assertEquals("Tốt", widget.qualityBadgeLabel().getText());
            assertEquals("45.2 ms", widget.rttValueLabel().getText());
            assertEquals("0.4%", widget.lossValueLabel().getText());
            assertEquals("8.5 ms", widget.jitterValueLabel().getText());
            assertEquals("1800 kbps", widget.bitrateValueLabel().getText());
            assertEquals("30", widget.fpsValueLabel().getText());
            assertEquals("1280x720", widget.resolutionValueLabel().getText());

            assertEquals(NetworkSignalWidget.COLOR_GOOD, widget.bar1().getFill());
            assertEquals(NetworkSignalWidget.COLOR_GOOD, widget.bar2().getFill());
            assertEquals(NetworkSignalWidget.COLOR_GOOD, widget.bar3().getFill());

            assertTrue(widget.tooltip().getText().contains("Tốt"));
            assertTrue(widget.tooltip().getText().contains("45.2 ms"));
            return null;
        });
    }

    @Test
    void mediumQualityTurnsTwoBarsYellowOneMuted() throws Exception {
        FxTestRuntime.call(() -> {
            NetworkSignalWidget widget = new NetworkSignalWidget();
            CalculatedSample sample = new CalculatedSample(120.0, 15.0, 0.5, 1200, 24, 1280, 720);
            widget.update(sample);

            assertEquals("TRUNG_BINH", widget.currentQuality());
            assertEquals("Trung bình", widget.qualityBadgeLabel().getText());
            assertEquals(NetworkSignalWidget.COLOR_MEDIUM, widget.bar1().getFill());
            assertEquals(NetworkSignalWidget.COLOR_MEDIUM, widget.bar2().getFill());
            assertEquals(NetworkSignalWidget.COLOR_MUTED, widget.bar3().getFill());
            return null;
        });
    }

    @Test
    void poorQualityTurnsOneBarRedTwoMuted() throws Exception {
        FxTestRuntime.call(() -> {
            NetworkSignalWidget widget = new NetworkSignalWidget();
            CalculatedSample sample = new CalculatedSample(200.0, 15.0, 0.5, 800, 15, 640, 480);
            widget.update(sample);

            assertEquals("KEM", widget.currentQuality());
            assertEquals("Kém", widget.qualityBadgeLabel().getText());
            assertEquals(NetworkSignalWidget.COLOR_POOR, widget.bar1().getFill());
            assertEquals(NetworkSignalWidget.COLOR_MUTED, widget.bar2().getFill());
            assertEquals(NetworkSignalWidget.COLOR_MUTED, widget.bar3().getFill());
            return null;
        });
    }

    @Test
    void partialNullMetricsDisplayDashes() throws Exception {
        FxTestRuntime.call(() -> {
            NetworkSignalWidget widget = new NetworkSignalWidget();
            CalculatedSample sample = new CalculatedSample(65.0, null, null, null, null, null, null);
            widget.update(sample);

            assertEquals("TOT", widget.currentQuality());
            assertEquals("Tốt", widget.qualityBadgeLabel().getText());
            assertEquals("65.0 ms", widget.rttValueLabel().getText());
            assertEquals("--", widget.lossValueLabel().getText());
            assertEquals("--", widget.jitterValueLabel().getText());
            assertEquals("--", widget.bitrateValueLabel().getText());
            assertEquals("--", widget.fpsValueLabel().getText());
            assertEquals("--", widget.resolutionValueLabel().getText());
            return null;
        });
    }

    @Test
    void popoverToggleAndDispose() throws Exception {
        FxTestRuntime.call(() -> {
            NetworkSignalWidget widget = new NetworkSignalWidget();
            Stage stage = new Stage();
            javafx.scene.Scene scene = new javafx.scene.Scene(new javafx.scene.layout.StackPane(widget));
            stage.setScene(scene);
            stage.show();

            assertFalse(widget.isPopoverShowing());
            widget.togglePopover();
            assertTrue(widget.isPopoverShowing());

            widget.togglePopover();
            assertFalse(widget.isPopoverShowing());

            widget.togglePopover();
            assertTrue(widget.isPopoverShowing());
            widget.dispose();
            assertFalse(widget.isPopoverShowing());
            assertNull(widget.currentSample());
            assertNull(widget.currentQuality());
            assertEquals("Chưa có dữ liệu", widget.qualityBadgeLabel().getText());

            stage.close();
            return null;
        });
    }

    @Test
    void fallbackToNetworkMonitorWhenSampleIsNull() throws Exception {
        FxTestRuntime.call(() -> {
            NetworkSignalWidget widget = new NetworkSignalWidget();
            client.network.NetworkMonitor.Snapshot snapshot = new client.network.NetworkMonitor.Snapshot(
                    client.network.NetworkMonitor.Quality.GOOD,
                    42.0, 5.0, 1000.0, 1000.0, 500, 500, 10, 0, 0.0, 10.0, java.util.List.of()
            );

            widget.update(null, snapshot);

            assertTrue(widget.isFallback());
            assertEquals("TOT", widget.currentQuality());
            assertEquals("Tốt", widget.qualityBadgeLabel().getText());
            assertEquals("42.0 ms", widget.rttValueLabel().getText());
            assertEquals("5.0 ms", widget.jitterValueLabel().getText());
            assertEquals("--", widget.lossValueLabel().getText());
            assertEquals("--", widget.bitrateValueLabel().getText());
            assertEquals("--", widget.fpsValueLabel().getText());
            assertEquals("--", widget.resolutionValueLabel().getText());

            assertEquals(NetworkSignalWidget.COLOR_GOOD, widget.bar1().getFill());
            assertEquals(NetworkSignalWidget.COLOR_GOOD, widget.bar2().getFill());
            assertEquals(NetworkSignalWidget.COLOR_GOOD, widget.bar3().getFill());

            assertTrue(widget.sourceLabel().getText().contains("Máy chủ (Camera tắt)"));
            assertTrue(widget.tooltip().getText().contains("Kênh máy chủ"));
            return null;
        });
    }

    @Test
    void fallbackTransitionsBackToWebRtcWhenSampleBecomesAvailable() throws Exception {
        FxTestRuntime.call(() -> {
            NetworkSignalWidget widget = new NetworkSignalWidget();
            client.network.NetworkMonitor.Snapshot snapshot = new client.network.NetworkMonitor.Snapshot(
                    client.network.NetworkMonitor.Quality.GOOD,
                    42.0, 5.0, 1000.0, 1000.0, 500, 500, 10, 0, 0.0, 10.0, java.util.List.of()
            );

            widget.update(null, snapshot);
            assertTrue(widget.isFallback());
            assertEquals("42.0 ms", widget.rttValueLabel().getText());

            CalculatedSample sample = new CalculatedSample(25.0, 3.0, 0.1, 1500, 30, 1280, 720);
            widget.update(sample, snapshot);

            assertFalse(widget.isFallback());
            assertEquals("25.0 ms", widget.rttValueLabel().getText());
            assertEquals("1500 kbps", widget.bitrateValueLabel().getText());
            assertEquals("30", widget.fpsValueLabel().getText());
            assertTrue(widget.sourceLabel().getText().contains("WebRTC Media"));
            assertFalse(widget.tooltip().getText().contains("Kênh máy chủ"));
            return null;
        });
    }

    @Test
    void emptySampleFallsBackToNetworkMonitor() throws Exception {
        FxTestRuntime.call(() -> {
            NetworkSignalWidget widget = new NetworkSignalWidget();
            client.network.NetworkMonitor.Snapshot snapshot = new client.network.NetworkMonitor.Snapshot(
                    client.network.NetworkMonitor.Quality.GOOD,
                    55.5, 6.2, 1000.0, 1000.0, 500, 500, 10, 0, 0.0, 10.0, java.util.List.of()
            );
            CalculatedSample emptySample = new CalculatedSample(null, null, null, null, null, null, null);

            widget.update(emptySample, snapshot);

            assertTrue(widget.isFallback());
            assertEquals("TOT", widget.currentQuality());
            assertEquals("55.5 ms", widget.rttValueLabel().getText());
            assertEquals("6.2 ms", widget.jitterValueLabel().getText());
            assertTrue(widget.sourceLabel().getText().contains("Máy chủ"));
            return null;
        });
    }
}
