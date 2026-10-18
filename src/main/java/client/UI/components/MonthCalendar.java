package client.UI.components;

import client.UI.ThemeManager;
import client.UI.VietnamTime;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.stage.Popup;
import javafx.stage.Window;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Lịch tháng độc lập tự viết bằng JavaFX GridPane.
 * Tuần bắt đầu từ thứ Hai (T2 ... CN), tiêu đề "tháng MM yyyy",
 * nút ‹ › chuyển tháng, hiển thị mờ các ngày ngoài tháng,
 * nổi bật hôm nay, nổi bật ngày được chọn, và chấm nhỏ cho ngày có lịch.
 */
public class MonthCalendar extends VBox {
    private final ObjectProperty<YearMonth> currentYearMonth = new SimpleObjectProperty<>();
    private final ObjectProperty<LocalDate> selectedDate = new SimpleObjectProperty<>();
    private final Set<LocalDate> datesWithEvents = new HashSet<>();
    private final Label titleLabel = new Label();
    private final GridPane daysGrid = new GridPane();
    private final Button prevButton;
    private final Button nextButton;
    private Consumer<LocalDate> onDateSelected;
    private LocalDate minimumDate;

    public MonthCalendar() {
        this(VietnamTime.today(), null);
    }

    public MonthCalendar(LocalDate initialDate, Consumer<LocalDate> onDateSelected) {
        super(10);
        this.onDateSelected = onDateSelected;
        getStyleClass().add("month-calendar");
        setAlignment(Pos.TOP_CENTER);
        setMaxWidth(Region.USE_PREF_SIZE);

        LocalDate start = initialDate != null ? initialDate : VietnamTime.today();
        this.selectedDate.set(start);
        this.currentYearMonth.set(YearMonth.from(start));

        // Header
        prevButton = new Button("‹");
        prevButton.setId("calendar-prev-month");
        prevButton.getStyleClass().addAll("calendar-nav-btn");
        prevButton.setOnAction(e -> previousMonth());

        nextButton = new Button("›");
        nextButton.setId("calendar-next-month");
        nextButton.getStyleClass().addAll("calendar-nav-btn");
        nextButton.setOnAction(e -> nextMonth());

        titleLabel.setId("calendar-month-title");
        titleLabel.getStyleClass().add("calendar-month-title");
        Region spacer1 = new Region();
        Region spacer2 = new Region();
        HBox.setHgrow(spacer1, Priority.ALWAYS);
        HBox.setHgrow(spacer2, Priority.ALWAYS);

        HBox header = new HBox(8, prevButton, spacer1, titleLabel, spacer2, nextButton);
        header.setAlignment(Pos.CENTER);
        header.getStyleClass().add("calendar-header");

        // Weekday header: T2 T3 T4 T5 T6 T7 CN
        GridPane weekHeader = new GridPane();
        weekHeader.setAlignment(Pos.CENTER);
        weekHeader.setHgap(4);
        String[] days = {"T2", "T3", "T4", "T5", "T6", "T7", "CN"};
        for (int i = 0; i < 7; i++) {
            Label dayLabel = new Label(days[i]);
            dayLabel.getStyleClass().add("calendar-weekday");
            dayLabel.setAlignment(Pos.CENTER);
            dayLabel.setMinWidth(36);
            dayLabel.setMaxWidth(36);
            weekHeader.add(dayLabel, i, 0);
        }

        daysGrid.setAlignment(Pos.CENTER);
        daysGrid.setHgap(4);
        daysGrid.setVgap(4);
        daysGrid.setId("calendar-days-grid");

        currentYearMonth.addListener((obs, old, ym) -> render());
        selectedDate.addListener((obs, old, date) -> render());

        getChildren().addAll(header, weekHeader, daysGrid);
        render();
    }

    public ObjectProperty<LocalDate> selectedDateProperty() {
        return selectedDate;
    }

    public LocalDate getSelectedDate() {
        return selectedDate.get();
    }

    public void setSelectedDate(LocalDate date) {
        selectedDate.set(date);
        if (date != null) {
            currentYearMonth.set(YearMonth.from(date));
        }
    }

    public ObjectProperty<YearMonth> yearMonthProperty() {
        return currentYearMonth;
    }

    public YearMonth getYearMonth() {
        return currentYearMonth.get();
    }

    public void setYearMonth(YearMonth yearMonth) {
        currentYearMonth.set(yearMonth);
    }

    public void setOnDateSelected(Consumer<LocalDate> onDateSelected) {
        this.onDateSelected = onDateSelected;
    }

    public void setDatesWithEvents(Set<LocalDate> dates) {
        datesWithEvents.clear();
        if (dates != null) {
            datesWithEvents.addAll(dates);
        }
        render();
    }

    public void setMinimumDate(LocalDate minimumDate) {
        this.minimumDate = minimumDate;
        render();
    }

    public void previousMonth() {
        if (minimumDate != null && !currentYearMonth.get().isAfter(YearMonth.from(minimumDate))) return;
        currentYearMonth.set(currentYearMonth.get().minusMonths(1));
    }

    public void nextMonth() {
        currentYearMonth.set(currentYearMonth.get().plusMonths(1));
    }

    public Label getTitleLabel() {
        return titleLabel;
    }

    public Button getPrevButton() {
        return prevButton;
    }

    public Button getNextButton() {
        return nextButton;
    }

    private void render() {
        YearMonth ym = currentYearMonth.get();
        if (ym == null) return;

        prevButton.setDisable(minimumDate != null && !ym.isAfter(YearMonth.from(minimumDate)));
        titleLabel.setText("tháng " + ym.getMonthValue() + " " + ym.getYear());
        daysGrid.getChildren().clear();

        LocalDate today = VietnamTime.today();
        LocalDate selected = selectedDate.get();

        LocalDate firstOfMonth = ym.atDay(1);
        int daysInMonth = ym.lengthOfMonth();

        // Week starts Monday (DayOfWeek.MONDAY = 1 ... SUNDAY = 7)
        int startCol = firstOfMonth.getDayOfWeek().getValue() - 1; // 0 for Monday, 3 for Thursday

        // Fill previous month trailing days
        YearMonth prevYm = ym.minusMonths(1);
        int daysInPrevMonth = prevYm.lengthOfMonth();
        for (int c = 0; c < startCol; c++) {
            int dayNum = daysInPrevMonth - startCol + 1 + c;
            LocalDate date = prevYm.atDay(dayNum);
            daysGrid.add(createDayCell(date, false, today, selected), c, 0);
        }

        // Fill current month days
        int row = 0;
        int col = startCol;
        for (int day = 1; day <= daysInMonth; day++) {
            LocalDate date = ym.atDay(day);
            daysGrid.add(createDayCell(date, true, today, selected), col, row);
            col++;
            if (col == 7) {
                col = 0;
                row++;
            }
        }

        // Fill next month leading days to complete the row
        if (col > 0) {
            YearMonth nextYm = ym.plusMonths(1);
            int nextDay = 1;
            while (col < 7) {
                LocalDate date = nextYm.atDay(nextDay++);
                daysGrid.add(createDayCell(date, false, today, selected), col, row);
                col++;
            }
        }
    }

    private Node createDayCell(LocalDate date, boolean isCurrentMonth, LocalDate today, LocalDate selected) {
        Button cell = new Button(String.valueOf(date.getDayOfMonth()));
        cell.setId("calendar-day-" + date);
        cell.getStyleClass().add("calendar-day");
        cell.setMinWidth(36);
        cell.setMinHeight(36);
        cell.setMaxWidth(36);
        cell.setMaxHeight(36);
        cell.setAlignment(Pos.CENTER);
        cell.setDisable(minimumDate != null && date.isBefore(minimumDate));

        if (!isCurrentMonth) {
            cell.getStyleClass().add("calendar-day-other-month");
        }
        if (date.equals(today)) {
            cell.getStyleClass().add("calendar-day-today");
        }
        if (date.equals(selected)) {
            cell.getStyleClass().add("calendar-day-selected");
        }

        boolean hasEvents = datesWithEvents.contains(date);
        if (hasEvents) {
            Circle dot = new Circle(2.5);
            dot.getStyleClass().add("calendar-event-dot");
            // If selected, dot contrasts nicely
            VBox box = new VBox(2, new Label(String.valueOf(date.getDayOfMonth())), dot);
            box.setAlignment(Pos.CENTER);
            box.setMouseTransparent(true);
            cell.setText("");
            cell.setGraphic(box);
        }

        cell.setOnAction(e -> {
            selectedDate.set(date);
            if (!date.getMonth().equals(currentYearMonth.get().getMonth())) {
                currentYearMonth.set(YearMonth.from(date));
            }
            if (onDateSelected != null) {
                onDateSelected.accept(date);
            }
        });

        return cell;
    }

    /**
     * Mở popup chứa MonthCalendar gắn vào node anchor.
     */
    public static Popup showPopup(Node anchor, LocalDate initialDate, Set<LocalDate> events, Consumer<LocalDate> onSelect) {
        return showPopup(anchor, initialDate, events, null, onSelect);
    }

    public static Popup showPopup(Node anchor, LocalDate initialDate, Set<LocalDate> events,
                                  LocalDate minimumDate, Consumer<LocalDate> onSelect) {
        Popup popup = new Popup();
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);

        MonthCalendar calendar = new MonthCalendar(initialDate, date -> {
            if (onSelect != null) onSelect.accept(date);
            popup.hide();
        });
        calendar.setMinimumDate(minimumDate);
        if (events != null) calendar.setDatesWithEvents(events);

        VBox container = new VBox(calendar);
        container.getStyleClass().addAll("calendar-popup-container", "card");
        popup.getContent().add(container);

        Window window = anchor.getScene() != null ? anchor.getScene().getWindow() : null;
        if (anchor.getScene() != null) {
            container.getStylesheets().addAll(anchor.getScene().getStylesheets());
        }
        ThemeManager.attach(container);

        javafx.geometry.Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (bounds != null) {
            popup.show(window, bounds.getMinX(), bounds.getMaxY() + 6);
        } else if (window != null) {
            popup.show(window);
        }
        return popup;
    }
}
