package client.UI.components;

import client.UI.SceneManager;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Side;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Thanh điều hướng chung cho các màn hình không gian làm việc. */
public final class Sidebar extends VBox {
    public Sidebar(SceneManager manager, String selected) {
        super(8);
        getStyleClass().add("sidebar");
        setPadding(new Insets(28, 12, 20, 12));
        setPrefWidth(260); setMinWidth(260); setMaxWidth(260);
        StackPane logo = new StackPane(Icons.icon("video", 22));
        logo.getStyleClass().add("brand-logo"); logo.setMinSize(38, 38); logo.setMaxSize(38, 38);
        HBox brand = Ui.row(10, logo, Ui.label("Mini Meeting", "brand-name-small"));
        brand.setPadding(new Insets(0, 8, 12, 8));
        Label section = Ui.label("KHÔNG GIAN LÀM VIỆC", "eyebrow");
        section.setPadding(new Insets(0, 8, 8, 8));
        getChildren().addAll(brand, section,
            nav("Sảnh chính", "home", selected.equals("lobby"), manager::lobby),
            nav("Lịch sử", "history", selected.equals("history"), manager::history),
            nav("Cài đặt", "settings", selected.equals("settings"), manager::settings), Ui.spacer());
        Label name = Ui.label(manager.user(), "user-name"); name.setWrapText(false); name.setMinWidth(0); name.setMaxWidth(Double.MAX_VALUE);
        VBox text = Ui.column(2, name, Ui.label(manager.username(), "user-caption"));
        text.setMinWidth(0); HBox.setHgrow(text, Priority.ALWAYS);
        HBox profile = Ui.row(8, Ui.avatar(manager.user(), 36), text);
        profile.setMinWidth(0); HBox.setHgrow(profile, Priority.ALWAYS); profile.getStyleClass().add("profile-hit");
        ContextMenu menu = new ContextMenu();
        MenuItem account = new MenuItem("Hồ sơ của tôi", Icons.icon("user", 18));
        account.setOnAction(e -> manager.settingsAccount());
        MenuItem logout = new MenuItem("Đăng xuất", Icons.icon("logout", 16));
        logout.disableProperty().bind(manager.logoutPendingProperty());
        logout.textProperty().bind(Bindings.when(manager.logoutPendingProperty()).then("Đang đăng xuất…").otherwise("Đăng xuất"));
        logout.setOnAction(e -> confirmLogout(manager)); menu.getItems().addAll(account, logout);
        menu.getStyleClass().add("profile-menu");
        profile.setOnMouseClicked(e -> menu.show(profile, Side.TOP, 0, -8));
        IconButton exit = new IconButton("close", "Đăng xuất");
        exit.setGraphic(Icons.icon("logout", 16)); exit.getStyleClass().add("logout-button");
        exit.disableProperty().bind(manager.logoutPendingProperty());
        Tooltip logoutHint = new Tooltip();
        logoutHint.textProperty().bind(Bindings.when(manager.logoutPendingProperty()).then("Đang đăng xuất…").otherwise("Đăng xuất"));
        exit.setTooltip(logoutHint);
        exit.setOnAction(e -> confirmLogout(manager));
        HBox user = Ui.row(6, profile, exit); user.getStyleClass().add("sidebar-user");
        user.setMinHeight(56); user.setMaxHeight(56); getChildren().add(user);
    }
    private Button nav(String title, String icon, boolean active, Runnable action) {
        Button button = Ui.button(title, "workspace-nav", action);
        button.setGraphic(Icons.icon(icon, 18)); button.setGraphicTextGap(12);
        button.setMaxWidth(Double.MAX_VALUE); button.setMinHeight(44); button.setMaxHeight(44);
        if (active) button.getStyleClass().add("workspace-nav-active");
        return button;
    }
    private void confirmLogout(SceneManager manager) {
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.initOwner(manager.stage()); dialog.setTitle("Đăng xuất"); dialog.setHeaderText(null);
        dialog.setContentText("Bạn có chắc muốn đăng xuất?");
        ButtonType cancel = new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType logout = new ButtonType("Đăng xuất", ButtonBar.ButtonData.OK_DONE);
        dialog.getButtonTypes().setAll(cancel, logout); Ui.style(dialog);
        ((ButtonBar) dialog.getDialogPane().lookup(".button-bar")).setButtonOrder("C+O");
        dialog.getDialogPane().lookupButton(logout).getStyleClass().add("danger");
        dialog.showAndWait().filter(logout::equals).ifPresent(result -> manager.logout());
    }
}
