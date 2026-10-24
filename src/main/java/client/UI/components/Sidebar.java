package client.UI.components;

import client.UI.SceneManager;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

/** Thanh điều hướng chung có thể thu gọn / mở rộng. */
public final class Sidebar extends VBox {
    private static final double EXPANDED_WIDTH = 260.0;
    private static final double COLLAPSED_WIDTH = 72.0;

    private boolean collapsed = false;
    private final SceneManager manager;
    private final Label brandLabel;
    private final Label sectionLabel;
    private final VBox profileText;
    private final IconButton collapseButton;
    private final GridPane brandRow = new GridPane();
    private final GridPane user = new GridPane();
    private final HBox profile;
    private final Button exit;
    private Timeline widthAnimation;
    private final java.util.List<Button> navButtons = new java.util.ArrayList<>();

    public Sidebar(SceneManager manager, String selected) {
        super(10);
        this.manager = manager;
        getStyleClass().add("sidebar");
        setPadding(new Insets(24, 12, 20, 12));
        setPrefWidth(EXPANDED_WIDTH);
        setMinWidth(EXPANDED_WIDTH);
        setMaxWidth(EXPANDED_WIDTH);

        // Header: Logo + Brand + Nút toggle thu gọn
        StackPane logo = new StackPane(Icons.icon("video", 22));
        logo.getStyleClass().add("brand-logo");
        logo.setMinSize(38, 38);
        logo.setMaxSize(38, 38);

        brandLabel = Ui.label("Mini Meeting", "brand-name-small");
        brandLabel.setMinWidth(0);

        collapseButton = new IconButton("chevron-left", "Thu gọn thanh điều hướng");
        collapseButton.setId("sidebar-collapse-button");
        collapseButton.setStyle(
                "-fx-background-color: transparent; -fx-border-width: 0; -fx-background-radius: 8;"
                        + " -fx-cursor: hand;");
        collapseButton.setMinSize(28, 28);
        collapseButton.setMaxSize(28, 28);
        collapseButton.setPadding(new Insets(2));
        collapseButton.setOnAction(e -> toggleCollapse());
        updateCollapseControl();

        brandRow.setHgap(8);
        brandRow.setVgap(8);
        brandRow.add(logo, 0, 0);
        brandRow.add(brandLabel, 1, 0);
        brandRow.add(collapseButton, 2, 0);
        GridPane.setHgrow(brandLabel, Priority.ALWAYS);
        GridPane.setHalignment(logo, HPos.CENTER);
        GridPane.setHalignment(collapseButton, HPos.CENTER);
        brandRow.setAlignment(Pos.CENTER_LEFT);
        brandRow.setPadding(new Insets(0, 4, 10, 4));

        sectionLabel = Ui.label("KHÔNG GIAN LÀM VIỆC", "eyebrow");
        sectionLabel.setPadding(new Insets(0, 8, 4, 8));

        // Nút điều hướng (Sảnh chính và Cài đặt)
        Button lobbyNav = nav("Sảnh chính", "home", selected.equals("lobby"), manager::lobby);
        Button settingsNav =
                nav("Cài đặt", "settings", selected.equals("settings"), manager::settings);

        getChildren().addAll(brandRow, sectionLabel, lobbyNav, settingsNav, Ui.spacer());

        // Footer: Profile người dùng + Nút đăng xuất
        Label name = Ui.label(manager.user(), "user-name");
        name.setWrapText(false);
        name.setMinWidth(0);

        profileText = Ui.column(2, name, Ui.label(manager.username(), "user-caption"));
        profileText.setMinWidth(0);
        HBox.setHgrow(profileText, Priority.ALWAYS);

        profile = Ui.row(8, Ui.avatar(manager.user(), 36), profileText);
        profile.setAlignment(Pos.CENTER_LEFT);
        profile.setMinWidth(0);
        GridPane.setHgrow(profile, Priority.ALWAYS);
        Tooltip.install(
                profile.getChildren().getFirst(),
                new Tooltip(manager.user() + " (" + manager.username() + ")"));

        exit = Ui.button("", "logout-button", () -> confirmLogout(manager));
        exit.setAccessibleText("Đăng xuất");
        exit.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        StackPane logoutIcon = Icons.icon("logout", 22);
        logoutIcon.setScaleX(2.0 / 3);
        logoutIcon.setScaleY(2.0 / 3);
        exit.setGraphic(logoutIcon);
        exit.disableProperty().bind(manager.logoutPendingProperty());
        Tooltip logoutHint = new Tooltip();
        logoutHint
                .textProperty()
                .bind(
                        Bindings.when(manager.logoutPendingProperty())
                                .then("Đang đăng xuất…")
                                .otherwise("Đăng xuất"));
        exit.setTooltip(logoutHint);

        user.setHgap(6);
        user.setVgap(6);
        user.add(profile, 0, 0);
        user.add(exit, 1, 0);
        GridPane.setHalignment(exit, HPos.CENTER);
        user.setAlignment(Pos.CENTER_LEFT);
        user.getStyleClass().add("sidebar-user");
        user.setMinHeight(54);
        user.setMaxHeight(54);
        getChildren().add(user);
        setCollapsed(manager.sidebarCollapsed(), false);
    }

    private Button nav(String title, String icon, boolean active, Runnable action) {
        Button button = Ui.button(title, "workspace-nav", action);
        button.getStyleClass()
                .setAll(
                        active
                                ? new String[] {"button", "workspace-nav", "workspace-nav-active"}
                                : new String[] {"button", "workspace-nav"});
        button.setGraphic(Icons.icon(icon, 18));
        button.setGraphicTextGap(12);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setMinHeight(44);
        button.setMaxHeight(44);
        button.setTooltip(new Tooltip(title));
        navButtons.add(button);
        return button;
    }

    private void toggleCollapse() {
        setCollapsed(!collapsed);
    }

    public void setCollapsed(boolean collapse) {
        setCollapsed(collapse, true);
    }

    private void setCollapsed(boolean collapse, boolean animate) {
        if (collapsed == collapse) return;
        this.collapsed = collapse;
        manager.sidebarCollapsed(collapse);
        if (widthAnimation != null) widthAnimation.stop();
        double targetWidth = collapse ? COLLAPSED_WIDTH : EXPANDED_WIDTH;

        if (animate) {
            widthAnimation =
                    new Timeline(
                            new KeyFrame(
                                    Duration.millis(180),
                                    new KeyValue(
                                            prefWidthProperty(),
                                            targetWidth,
                                            Interpolator.EASE_BOTH),
                                    new KeyValue(
                                            minWidthProperty(),
                                            targetWidth,
                                            Interpolator.EASE_BOTH),
                                    new KeyValue(
                                            maxWidthProperty(),
                                            targetWidth,
                                            Interpolator.EASE_BOTH)));
            widthAnimation.setOnFinished(e -> updateCollapsedLayout(collapse));
            widthAnimation.play();
        } else {
            setPrefWidth(targetWidth);
            setMinWidth(targetWidth);
            setMaxWidth(targetWidth);
            updateCollapsedLayout(collapse);
        }
        updateCollapseControl();

        if (collapse) {
            setAlignment(Pos.TOP_CENTER);
            brandLabel.setVisible(false);
            brandLabel.setManaged(false);
            sectionLabel.setVisible(false);
            sectionLabel.setManaged(false);
            profileText.setVisible(false);
            profileText.setManaged(false);
            brandRow.setHgap(0);
            brandRow.setPadding(new Insets(0, 0, 10, 0));
            brandRow.setAlignment(Pos.TOP_CENTER);
            GridPane.setConstraints(collapseButton, 0, 1);
            user.setHgap(0);
            user.setAlignment(Pos.CENTER);
            user.setPadding(new Insets(1, 0, 1, 0));
            user.setStyle("-fx-background-color: transparent;");
            user.setMinHeight(76);
            user.setMaxHeight(76);
            profile.setAlignment(Pos.CENTER);
            GridPane.setConstraints(exit, 0, 1);
            for (Button btn : navButtons) {
                btn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                btn.setMinWidth(44);
                btn.setPrefWidth(44);
                btn.setMaxWidth(44);
                btn.setAlignment(Pos.CENTER);
                btn.setStyle(
                        "-fx-padding: 0; -fx-alignment: center; -fx-border-width: 0;"
                                + " -fx-background-radius: 10; -fx-border-radius: 10;");
            }
        }
    }

    private void updateCollapsedLayout(boolean collapse) {
        if (!collapse) {
            setAlignment(Pos.TOP_LEFT);
            brandRow.setHgap(8);
            brandRow.setPadding(new Insets(0, 4, 10, 4));
            brandRow.setAlignment(Pos.CENTER_LEFT);
            GridPane.setConstraints(collapseButton, 2, 0);
            user.setHgap(6);
            user.setAlignment(Pos.CENTER_LEFT);
            user.setPadding(new Insets(8, 10, 8, 10));
            user.setStyle("");
            user.setMinHeight(54);
            user.setMaxHeight(54);
            profile.setAlignment(Pos.CENTER_LEFT);
            GridPane.setConstraints(exit, 1, 0);
            brandLabel.setVisible(true);
            brandLabel.setManaged(true);
            sectionLabel.setVisible(true);
            sectionLabel.setManaged(true);
            profileText.setVisible(true);
            profileText.setManaged(true);
            for (Button btn : navButtons) {
                btn.setContentDisplay(ContentDisplay.LEFT);
                btn.setMinWidth(0);
                btn.setPrefWidth(USE_COMPUTED_SIZE);
                btn.setMaxWidth(Double.MAX_VALUE);
                btn.setAlignment(Pos.CENTER_LEFT);
                btn.setStyle("");
            }
        }
    }

    private void updateCollapseControl() {
        collapseButton.setIcon(collapsed ? "chevron-right" : "chevron-left");
        collapseButton.getGraphic().lookup(".vector-icon").setStyle("-fx-stroke: -text-muted;");
        String hint = collapsed ? "Mở rộng thanh điều hướng" : "Thu gọn thanh điều hướng";
        collapseButton.getTooltip().setText(hint);
        collapseButton.setAccessibleText(hint);
    }

    private void confirmLogout(SceneManager manager) {
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.initOwner(manager.stage());
        dialog.setTitle("Đăng xuất");
        dialog.setHeaderText(null);
        dialog.setContentText("Bạn có chắc muốn đăng xuất?");
        ButtonType cancel = new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType logout = new ButtonType("Đăng xuất", ButtonBar.ButtonData.OK_DONE);
        dialog.getButtonTypes().setAll(cancel, logout);
        Ui.style(dialog);
        ((ButtonBar) dialog.getDialogPane().lookup(".button-bar")).setButtonOrder("C+O");
        dialog.getDialogPane().lookupButton(logout).getStyleClass().add("danger");
        dialog.showAndWait().filter(logout::equals).ifPresent(result -> manager.logout());
    }
}
