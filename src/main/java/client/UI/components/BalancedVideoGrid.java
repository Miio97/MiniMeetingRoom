package client.UI.components;

import javafx.scene.Node;
import javafx.scene.layout.Pane;
import java.util.List;

/** Chọn số hàng theo diện tích tile 16:9; chia đều người và căn giữa từng hàng. */
public final class BalancedVideoGrid extends Pane {
    private static final double GAP = 14, RATIO = 16.0 / 9;
    public BalancedVideoGrid(List<VideoTile> tiles) {
        getChildren().setAll(tiles); setMinSize(0, 0);
    }
    @Override protected void layoutChildren() {
        int count = getChildren().size(); if (count == 0) return;
        double width = getWidth(), height = getHeight();
        int bestRows = 1; double bestWidth = 0;
        for (int rows = 1; rows <= count; rows++) {
            int columns = (count + rows - 1) / rows;
            double tileWidth = Math.min((width - GAP * (columns - 1)) / columns,
                (height - GAP * (rows - 1)) / rows * RATIO);
            if (tileWidth > bestWidth) { bestWidth = tileWidth; bestRows = rows; }
        }
        double tileWidth = Math.max(0, bestWidth), tileHeight = tileWidth / RATIO;
        double y = (height - bestRows * tileHeight - (bestRows - 1) * GAP) / 2;
        int index = 0;
        // Ví dụ 7 người / 3 hàng: 3–2–2, không để một ô lẻ nằm ở góc.
        for (int row = 0; row < bestRows; row++) {
            int columns = count / bestRows + (row < count % bestRows ? 1 : 0);
            double x = (width - columns * tileWidth - (columns - 1) * GAP) / 2;
            for (int column = 0; column < columns; column++) {
                Node tile = getChildren().get(index++);
                tile.resizeRelocate(x, y, tileWidth, tileHeight); x += tileWidth + GAP;
            }
            y += tileHeight + GAP;
        }
    }
}
