package client.UI.dialogs;

import client.UI.SceneManager;
import client.UI.VietnamTime;
import client.UI.components.MonthCalendar;
import client.UI.components.Ui;
import common.room.RoomInfo;
import common.schedule.ScheduleInfo;
import common.schedule.ScheduleRequest;
import common.schedule.ScheduleValidation;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Hộp thoại tạo lịch hẹn (kiểu Google Calendar).
 * Cho phép nhập tiêu đề, ngày/giờ bắt đầu - kết thúc theo giờ Việt Nam, lặp lại,
 * mô tả và tùy chọn gắn phòng từ phòng gần đây hoặc nhập mã phòng.
 */
public final class CreateScheduleDialog extends Dialog<ScheduleInfo> {
    private LocalDate startDate;
    private LocalDate endDate;
    private static final ZoneId SCHEDULE_ZONE = VietnamTime.ZONE;
    private boolean pending;
    private final Clock clock;
    private boolean updatingTimes;
    private final ComboBox<String> startTimeCombo = new ComboBox<>();
    private final ComboBox<String> endTimeCombo = new ComboBox<>();
    private final Button startDateBtn = new Button();
    private final Button endDateBtn = new Button();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    public CreateScheduleDialog(SceneManager manager, LocalDate initialDate) {
        this(manager, initialDate, Clock.systemUTC());
    }

    public CreateScheduleDialog(SceneManager manager, LocalDate initialDate, Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        initOwner(manager.stage());
        setTitle("Tạo lịch hẹn");
        Ui.style(this);

        startDate = initialDate != null ? initialDate : VietnamTime.today();
        endDate = startDate;

        // Round a future instant so crossing midnight also advances the date correctly.
        ZonedDateTime earliest = ZonedDateTime.now(clock.withZone(SCHEDULE_ZONE)).plusMinutes(ScheduleValidation.MIN_ADVANCE_MINUTES);
        ZonedDateTime slot = earliest.withSecond(0).withNano(0)
                .plusMinutes(15 - earliest.getMinute() % 15);
        if (!startDate.isAfter(LocalDate.now(clock.withZone(SCHEDULE_ZONE)))) startDate = slot.toLocalDate();
        endDate = startDate;
        LocalTime startTime = slot.toLocalTime();
        LocalTime endTime = startTime.plusHours(1);
        if (!endTime.isAfter(startTime)) endDate = startDate.plusDays(1);

        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");

        // Header: Tiêu đề hộp thoại + nút đóng ✕
        Label headerTitle = Ui.label("Tạo lịch hẹn mới", "subtitle");
        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().add("text-button");
        closeBtn.setStyle("-fx-font-size: 16px; -fx-padding: 4 8; -fx-cursor: hand;");
        closeBtn.setOnAction(e -> close());
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(headerTitle, headerSpacer, closeBtn);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        // Ô nhập tiêu đề lớn
        TextField titleField = new TextField();
        titleField.setPromptText("Thêm tiêu đề");
        titleField.setId("schedule-title");
        titleField.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-padding: 10 14;");

        // Hàng thời gian: [ngày bắt đầu] [giờ bắt đầu] "tới" [giờ kết thúc] [ngày kết thúc] 
        startDateBtn.setText(VietnamTime.formatScheduleDate(startDate));
        startDateBtn.setId("schedule-start-date-btn");
        startDateBtn.getStyleClass().add("secondary");

        endDateBtn.setText(VietnamTime.formatScheduleDate(endDate));
        endDateBtn.setId("schedule-end-date-btn");
        endDateBtn.getStyleClass().add("secondary");

        List<String> timeChoices = generateTimeChoices();
        startTimeCombo.setId("schedule-start-time");
        startTimeCombo.getItems().addAll(timeChoices);
        startTimeCombo.setValue(timeFmt.format(startTime));

        endTimeCombo.setId("schedule-end-time");
        endTimeCombo.getItems().addAll(timeChoices);
        endTimeCombo.setValue(timeFmt.format(endTime));

        Label toLabel = Ui.label("tới", "muted");

        // Popup chọn ngày bắt đầu / kết thúc bằng MonthCalendar
        startDateBtn.setOnAction(e -> {
            MonthCalendar.showPopup(startDateBtn, startDate, null, minimumStartDate(), picked -> {
                startDate = picked;
                startDateBtn.setText(VietnamTime.formatScheduleDate(startDate));
                if (endDate.isBefore(startDate)) {
                    endDate = startDate;
                    endDateBtn.setText(VietnamTime.formatScheduleDate(endDate));
                }
                refreshTimeChoices();
            });
        });

        endDateBtn.setOnAction(e -> {
            MonthCalendar.showPopup(endDateBtn, endDate, null, startDate.isAfter(minimumStartDate()) ? startDate : minimumStartDate(), picked -> {
                endDate = picked;
                endDateBtn.setText(VietnamTime.formatScheduleDate(endDate));
                refreshTimeChoices();
            });
        });

        HBox timeRow = new HBox(8, startDateBtn, startTimeCombo, toLabel, endTimeCombo, endDateBtn);
        timeRow.setAlignment(Pos.CENTER_LEFT);

        startTimeCombo.valueProperty().addListener((obs, old, value) -> {
            if (!updatingTimes && value != null) refreshTimeChoices();
        });
        startTimeCombo.setOnShowing(event -> refreshTimeChoices());
        endTimeCombo.setOnShowing(event -> refreshTimeChoices());
        refreshTimeChoices();

        ComboBox<String> repeatCombo = new ComboBox<>();
        repeatCombo.setId("schedule-repeat");
        repeatCombo.getItems().addAll("Không lặp lại", "Hằng ngày", "Hằng tuần", "Hằng tháng");
        repeatCombo.setValue("Không lặp lại");

        HBox optionsRow = new HBox(16, repeatCombo);
        optionsRow.setAlignment(Pos.CENTER_LEFT);

        // Ô nhập mô tả
        TextArea descriptionArea = new TextArea();
        descriptionArea.setPromptText("Thêm nội dung mô tả (tối đa 1000 ký tự)");
        descriptionArea.setId("schedule-description");
        descriptionArea.setPrefRowCount(3);
        descriptionArea.setWrapText(true);

        // Gắn phòng họp
        ComboBox<String> roomCombo = new ComboBox<>();
        roomCombo.setId("schedule-room-combo");
        roomCombo.setMaxWidth(Double.MAX_VALUE);
        roomCombo.getItems().add("Không gắn phòng họp");
        List<RoomInfo> recentRooms = manager.recentRooms() != null ? manager.recentRooms() : List.of();
        for (RoomInfo r : recentRooms) {
            roomCombo.getItems().add(r.name() + " (" + r.code() + ")");
        }
        roomCombo.getItems().add("Nhập mã phòng khác…");
        roomCombo.setValue("Không gắn phòng họp");

        TextField customRoomCodeField = new TextField();
        customRoomCodeField.setId("schedule-custom-room-code");
        customRoomCodeField.setPromptText("Ví dụ: MMR-XXXXXXXX");
        customRoomCodeField.setVisible(false);
        customRoomCodeField.setManaged(false);

        roomCombo.valueProperty().addListener((obs, old, val) -> {
            boolean custom = "Nhập mã phòng khác…".equals(val);
            customRoomCodeField.setVisible(custom);
            customRoomCodeField.setManaged(custom);
        });

        VBox roomFieldBox = Ui.column(6, Ui.label("Gắn phòng họp (không lưu PIN)", "caption"), roomCombo, customRoomCodeField);

        Label error = Ui.label("", "error-text");
        error.setWrapText(true);
        error.setId("schedule-error");

        VBox content = Ui.column(16,
                headerRow,
                titleField,
                timeRow,
                optionsRow,
                Ui.field("Mô tả", descriptionArea),
                roomFieldBox,
                error
        );
        content.setPrefWidth(540);
        content.setPadding(new Insets(12, 8, 8, 8));
        getDialogPane().setContent(content);

        ButtonType saveType = new ButtonType("Lưu", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, saveType);
        Button cancel = (Button) getDialogPane().lookupButton(ButtonType.CANCEL);
        cancel.setText("Hủy");
        Button submit = (Button) getDialogPane().lookupButton(saveType);
        submit.getStyleClass().add("primary");
        submit.setId("schedule-submit");

        setResultConverter(type -> null);
        setOnCloseRequest(event -> { if (pending) event.consume(); });

        submit.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            if (pending) return;

            String title = titleField.getText();
            String titleValidation = ScheduleValidation.titleError(title);
            if (titleValidation != null) {
                error.setText(titleValidation);
                return;
            }

            String desc = descriptionArea.getText();
            String descValidation = ScheduleValidation.descriptionError(desc);
            if (descValidation != null) {
                error.setText(descValidation);
                return;
            }

            Instant startInstant;
            Instant endInstant;
            try {
                LocalTime sTime = LocalTime.parse(startTimeCombo.getValue(), timeFmt);
                LocalTime eTime = LocalTime.parse(endTimeCombo.getValue(), timeFmt);
                startInstant = startDate.atTime(sTime).atZone(SCHEDULE_ZONE).toInstant();
                endInstant = endDate.atTime(eTime).atZone(SCHEDULE_ZONE).toInstant();
            } catch (Exception ex) {
                error.setText("Thời gian đã chọn không hợp lệ.");
                return;
            }

            if (!endInstant.isAfter(startInstant)) {
                error.setText("Thời gian kết thúc phải diễn ra sau thời gian bắt đầu.");
                return;
            }

            String advanceError = ScheduleValidation.advanceTimeError(startInstant, clock.instant());
            if (advanceError != null) {
                error.setText(advanceError);
                return;
            }

            // Map lặp lại
            String repeatSelection = repeatCombo.getValue();
            String repeatRule = switch (repeatSelection != null ? repeatSelection : "") {
                case "Hằng ngày" -> "HANG_NGAY";
                case "Hằng tuần" -> "HANG_TUAN";
                case "Hằng tháng" -> "HANG_THANG";
                default -> "KHONG";
            };

            // Map phòng gắn kèm (KHÔNG lưu hay gửi PIN)
            String attachedRoomCode = null;
            String roomSelection = roomCombo.getValue();
            if ("Nhập mã phòng khác…".equals(roomSelection)) {
                attachedRoomCode = customRoomCodeField.getText() != null ? customRoomCodeField.getText().strip() : null;
            } else if (roomSelection != null && !roomSelection.equals("Không gắn phòng họp")) {
                for (RoomInfo r : recentRooms) {
                    if (roomSelection.contains(r.code())) {
                        attachedRoomCode = r.code();
                        break;
                    }
                }
            }

            ScheduleRequest request = new ScheduleRequest(
                    title.strip(),
                    desc != null && !desc.isBlank() ? desc.strip() : null,
                    startInstant.toString(),
                    endInstant.toString(),
                    repeatRule,
                    attachedRoomCode
            );

            pending = true;
            content.setDisable(true);
            submit.setDisable(true);
            cancel.setDisable(true);
            submit.setText("Đang lưu…");
            error.setText("");

            String token = manager.sessionToken();
            manager.runAuthRequest(() -> manager.authClient().createSchedule(token, request), response -> {
                pending = false;
                content.setDisable(false);
                submit.setDisable(false);
                cancel.setDisable(false);
                submit.setText("Lưu");
                if (manager.sessionExpired(response)) return;
                if (!response.success()) {
                    error.setText(response.message());
                    return;
                }
                setResult(response.schedule());
                close();
            }, cause -> {
                pending = false;
                content.setDisable(false);
                submit.setDisable(false);
                cancel.setDisable(false);
                submit.setText("Lưu");
                error.setText(SceneManager.connectionError(cause));
            });
        });
    }

    private LocalDate minimumStartDate() {
        ZonedDateTime earliest = clock.instant().plusSeconds(ScheduleValidation.MIN_ADVANCE_MINUTES * 60L)
                .atZone(SCHEDULE_ZONE);
        LocalDate minimum = earliest.toLocalDate();
        return minimum;
    }

    private void refreshTimeChoices() {
        if (updatingTimes) return;
        updatingTimes = true;
        try {
            LocalDate minimum = minimumStartDate();
            if (startDate.isBefore(minimum)) startDate = minimum;
            Instant earliest = clock.instant().plusSeconds(ScheduleValidation.MIN_ADVANCE_MINUTES * 60L);
            List<String> starts = availableStartTimes(startDate, SCHEDULE_ZONE, earliest);
            // Late at night the next available quarter hour can be tomorrow.
            if (starts.isEmpty()) {
                startDate = startDate.plusDays(1);
                starts = availableStartTimes(startDate, SCHEDULE_ZONE, earliest);
            }
            setChoices(startTimeCombo, starts);
            if (endDate.isBefore(startDate)) endDate = startDate;
            Instant start = startDate.atTime(LocalTime.parse(startTimeCombo.getValue(), TIME_FORMAT))
                    .atZone(SCHEDULE_ZONE).toInstant();
            List<String> ends = availableEndTimes(endDate, SCHEDULE_ZONE, start);
            if (ends.isEmpty()) {
                endDate = endDate.plusDays(1);
                ends = availableEndTimes(endDate, SCHEDULE_ZONE, start);
            }
            setChoices(endTimeCombo, ends);
            startDateBtn.setText(VietnamTime.formatScheduleDate(startDate));
            endDateBtn.setText(VietnamTime.formatScheduleDate(endDate));
        } finally {
            updatingTimes = false;
        }
    }

    private static void setChoices(ComboBox<String> combo, List<String> choices) {
        String selected = combo.getValue();
        combo.getItems().setAll(choices);
        combo.setValue(choices.contains(selected) ? selected : choices.getFirst());
    }

    private static List<String> availableStartTimes(LocalDate date, ZoneId zone, Instant earliest) {
        return generateTimeChoices().stream().filter(value -> !date.atTime(LocalTime.parse(value, TIME_FORMAT))
                .atZone(zone).toInstant().isBefore(earliest)).toList();
    }

    private static List<String> availableEndTimes(LocalDate date, ZoneId zone, Instant start) {
        return generateTimeChoices().stream().filter(value -> date.atTime(LocalTime.parse(value, TIME_FORMAT))
                .atZone(zone).toInstant().isAfter(start)).toList();
    }

    private static List<String> generateTimeChoices() {
        List<String> list = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            for (int m = 0; m < 60; m += 15) {
                list.add(String.format(Locale.ROOT, "%02d:%02d", h, m));
            }
        }
        return list;
    }
}
