package client.UI;

import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

/** Window coordinates are JavaFX logical pixels; visual bounds exclude the taskbar. */
final class WindowPlacement {
    private WindowPlacement() { }

    static Rectangle2D centered(Rectangle2D area,double width,double height) {
        double w=Math.min(width,Math.max(1,area.getWidth()-32));
        double h=Math.min(height,Math.max(1,area.getHeight()-32));
        return new Rectangle2D(area.getMinX()+(area.getWidth()-w)/2,
                area.getMinY()+(area.getHeight()-h)/2,w,h);
    }

    static Rectangle2D visualArea(Stage stage) {
        if(!Double.isFinite(stage.getX()) || !Double.isFinite(stage.getY())) return Screen.getPrimary().getVisualBounds();
        double w=Double.isFinite(stage.getWidth())?Math.max(1,stage.getWidth()):1;
        double h=Double.isFinite(stage.getHeight())?Math.max(1,stage.getHeight()):1;
        return Screen.getScreensForRectangle(stage.getX(),stage.getY(),w,h).stream()
                .max(java.util.Comparator.comparingDouble(screen -> {
                    var b=screen.getBounds();
                    return Math.max(0,Math.min(stage.getX()+w,b.getMaxX())-Math.max(stage.getX(),b.getMinX()))
                            *Math.max(0,Math.min(stage.getY()+h,b.getMaxY())-Math.max(stage.getY(),b.getMinY()));
                })).orElse(Screen.getPrimary()).getVisualBounds();
    }
}
