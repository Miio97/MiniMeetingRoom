package client.UI.screens;

import client.UI.SceneManager;
import client.UI.components.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

public final class HistoryScreen extends BorderPane {
    private record Meeting(String name, String code, LocalDateTime date, int minutes, int people, boolean host) { }
    public HistoryScreen(SceneManager manager) {
        setLeft(new Sidebar(manager, "history"));
        VBox main = new VBox(22); main.setPadding(new Insets(32));
        TextField search = new TextField(); search.setPromptText("Tìm theo tên hoặc mã phòng"); HBox.setHgrow(search, Priority.ALWAYS);
        ComboBox<String> filter = Ui.devices(List.of("Tất cả", "Tuần này", "Tháng này")); filter.setMinWidth(150);
        main.getChildren().addAll(Ui.label("Lịch sử cuộc họp", "title"), Ui.label("Nhìn lại các cuộc trò chuyện và ý tưởng đã chia sẻ.", "muted"), Ui.row(12, search, filter));
        VBox list = new VBox(12); main.getChildren().add(list);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        String[] names = {"Design sync · Nhóm sản phẩm", "Cà phê & ý tưởng", "Sprint planning", "Nhóm học Java", "Review giao diện", "Chia sẻ kiến thức", "Weekly team sync", "Lên kế hoạch dự án", "Demo sản phẩm"};
        List<Meeting> meetings = new ArrayList<>();
        int[] offsets = {0, 1, 2, 3, 6, 9, 15, 25, 38};
        for (int i = 0; i < names.length; i++) meetings.add(new Meeting(names[i], "MMR-" + (2048 + i * 317), today.minusDays(offsets[i]).atTime(9 + i % 5, 30), 25 + i * 7, 2 + i % 9, i % 3 == 0));
        Runnable update = () -> {
            list.getChildren().clear(); String query = search.getText().strip().toLowerCase(Locale.ROOT);
            LocalDate week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            for (Meeting meeting : meetings) {
                if (!(meeting.name() + " " + meeting.code()).toLowerCase(Locale.ROOT).contains(query)) continue;
                if ("Tuần này".equals(filter.getValue()) && meeting.date().toLocalDate().isBefore(week)) continue;
                if ("Tháng này".equals(filter.getValue()) && !YearMonth.from(meeting.date()).equals(YearMonth.from(today))) continue;
                Label role = Ui.label(meeting.host() ? "Host" : "Thành viên", "role-chip");
                VBox text = Ui.column(6, Ui.label(meeting.name(), "room-title"), Ui.label(meeting.code() + "  ·  " + meeting.date().format(DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm")) + "  ·  " + meeting.minutes() + " phút", "room-meta"));
                text.setMinWidth(0); HBox.setHgrow(text, Priority.ALWAYS);
                Button detail = Ui.button("Chi tiết", "secondary", () -> showDetails(manager, meeting.name(), meeting.code(), meeting.people(), meeting.minutes())); detail.setMinWidth(85);
                HBox row = Ui.row(14, Ui.avatar(meeting.name(), 40), text, role, detail); row.getStyleClass().add("room-row"); list.getChildren().add(row);
            }
            if (list.getChildren().isEmpty()) {
                VBox empty = Ui.column(16, Icons.icon("search", 36), Ui.label("Không tìm thấy cuộc họp", "subtitle"), Ui.label("Thử tên, mã phòng hoặc khoảng thời gian khác.", "muted"));
                empty.setAlignment(Pos.CENTER); empty.setPadding(new Insets(60)); list.getChildren().add(empty);
            }
        };
        search.textProperty().addListener((obs, old, value) -> update.run()); filter.valueProperty().addListener((obs, old, value) -> update.run()); update.run(); setCenter(Ui.scroll(main));
    }
    public static void showDetails(SceneManager manager, String name, String code, int people, int minutes) {
        Dialog<Void> dialog = new Dialog<>(); dialog.initOwner(manager.stage()); dialog.setTitle("Chi tiết cuộc họp"); dialog.setHeaderText(name);
        VBox content = Ui.column(14, Ui.label(code + "  ·  " + people + "/10 người  ·  " + minutes + " phút", "room-meta"), Ui.label("Trò chuyện trong cuộc họp", "strong"),
            Ui.label("09:30 · Hoàng Nam: Chào mọi người, cùng bắt đầu nhé!", "muted"),
            Ui.label("09:31 · Thảo Linh: Mình đã gửi bản thiết kế mới.", "muted"),
            Ui.label("09:32 · Bạn: Cảm ơn, chúng ta cùng xem qua nhé.", "muted"));
        content.setPrefWidth(480); dialog.getDialogPane().setContent(content); dialog.getDialogPane().getButtonTypes().add(new ButtonType("Đóng", ButtonBar.ButtonData.CANCEL_CLOSE)); Ui.style(dialog); dialog.showAndWait();
    }
}
