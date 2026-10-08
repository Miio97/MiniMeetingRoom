package client.UI.components;

import client.UI.mock.MockData;
import client.UI.ThemeManager;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import java.util.List;

/** Các thành phần nhỏ dùng chung, giữ spacing và typography nhất quán. */
public final class Ui {
    private Ui() { }
    public static Label label(String text, String style) { Label l = new Label(text); l.getStyleClass().add(style); l.setWrapText(true); return l; }
    public static Button button(String text, String style, Runnable action) {
        Button b = new Button(text); b.getStyleClass().add(style); b.setOnAction(e -> action.run()); return b;
    }
    public static Region spacer() { Region s = new Region(); HBox.setHgrow(s, Priority.ALWAYS); VBox.setVgrow(s, Priority.ALWAYS); return s; }
    public static HBox row(double spacing, Node... nodes) { HBox box = new HBox(spacing, nodes); box.setAlignment(Pos.CENTER_LEFT); return box; }
    public static VBox column(double spacing, Node... nodes) { return new VBox(spacing, nodes); }
    public static StackPane avatar(String name, double size) {
        Circle circle = new Circle(size / 2); circle.setStyle("-fx-fill: " + MockData.avatarGradient(name));
        Label letters = label(MockData.initials(name), "avatar-text"); letters.setStyle("-fx-font-size: " + Math.max(12, size * .32) + "px");
        StackPane avatar = new StackPane(circle, letters); avatar.setMinSize(size, size); avatar.setMaxSize(size, size); return avatar;
    }
    public static ComboBox<String> devices(List<String> items) {
        ComboBox<String> box = new ComboBox<>(); box.getItems().setAll(items); box.getSelectionModel().selectFirst(); box.setMaxWidth(Double.MAX_VALUE); return box;
    }
    public static VBox field(String title, Node control) { return column(6, label(title, "field-label"), control); }
    public static ScrollPane scroll(Node node) {
        ScrollPane pane = new ScrollPane(node); pane.setFitToWidth(true); pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); return pane;
    }
    public static void info(javafx.stage.Window owner, String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION); alert.initOwner(owner); alert.setTitle(title); alert.setHeaderText(title); alert.setContentText(message);
        style(alert); alert.showAndWait();
    }
    public static void style(Dialog<?> dialog) {
        dialog.getDialogPane().getStylesheets().add(Ui.class.getResource("/styles/app.css").toExternalForm());
        ThemeManager.attach(dialog.getDialogPane());
    }
}
