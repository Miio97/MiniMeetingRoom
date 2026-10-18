package client.UI;

import client.UI.components.MonthCalendar;
import client.UI.components.Ui;
import client.UI.dialogs.CreateScheduleDialog;
import client.UI.screens.LobbyScreen;
import client.auth.AuthClient;
import client.filetransfer.FileTransferClient;
import common.auth.AuthResponse;
import common.auth.AuthUser;
import common.schedule.ScheduleInfo;
import common.schedule.ScheduleRequest;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import support.FxTestRuntime;
import support.RoomTestFixture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
class ScheduleFlowTest {
    @TempDir static Path temporary;
    private static RoomTestFixture.Tls tls;
    private RoomTestFixture fixture;
    private SceneManager manager;
    private Stage stage;
    private AuthResponse user;
    private final List<String> serverActions = new CopyOnWriteArrayList<>();

    @BeforeAll
    @Timeout(60)
    static void initRuntime() throws Exception {
        tls = RoomTestFixture.certificates(temporary);
        FxTestRuntime.start();
    }

    @BeforeEach
    void setupServer() throws Exception {
        fixture = new RoomTestFixture(tls, request -> serverActions.add(request.action()));
        user = fixture.account("TestScheduleUser", "Người dùng lịch");
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (manager != null) {
                FxTestRuntime.call(() -> {
                    manager.shutdown();
                    if (stage != null) stage.hide();
                    return null;
                });
            }
        } finally {
            if (fixture != null) fixture.close();
        }
    }

    @Test
    void monthCalendarOctober2026StartsOnThursdayAndWeekStartsMonday() throws Exception {
        FxTestRuntime.call(() -> {
            boolean original = ThemeManager.isLight();
            try {
                LocalDate oct2026 = LocalDate.of(2026, 10, 1);
                AtomicReference<LocalDate> selectedDate = new AtomicReference<>();
                MonthCalendar calendar = new MonthCalendar(oct2026, selectedDate::set);
                calendar.setDatesWithEvents(Set.of(LocalDate.of(2026, 10, 17)));

                Scene scene = new Scene(calendar, 340, 360);
                scene.getStylesheets().add(Ui.class.getResource("/styles/app.css").toExternalForm());
                css(calendar);

                // Verify Title
                Label titleLabel = (Label) calendar.lookup("#calendar-month-title");
                assertNotNull(titleLabel);
                assertEquals("tháng 10 2026", titleLabel.getText());

                // Find the GridPane inside MonthCalendar
                GridPane grid = (GridPane) calendar.lookup("#calendar-days-grid");
                assertNotNull(grid);

                // Verify Weekday Header Row: T2, T3, T4, T5, T6, T7, CN
                List<String> headers = calendar.lookupAll(".calendar-weekday").stream()
                        .filter(Label.class::isInstance).map(Label.class::cast)
                        .map(Label::getText).toList();
                assertEquals(List.of("T2", "T3", "T4", "T5", "T6", "T7", "CN"), headers);

                // 2026-10-01 is a Thursday -> must be on col 3, row 0 in daysGrid
                Node day1Node = grid.getChildren().stream()
                        .filter(n -> (GridPane.getRowIndex(n) == null ? 0 : GridPane.getRowIndex(n)) == 0
                                && (GridPane.getColumnIndex(n) == null ? 0 : GridPane.getColumnIndex(n)) == 3)
                        .findFirst().orElseThrow();
                assertTrue(day1Node instanceof Button);
                Button day1Btn = (Button) day1Node;
                assertEquals("1", day1Btn.getText());
                assertFalse(day1Btn.getStyleClass().contains("calendar-day-other-month"));

                // Date with event dot: Oct 17, 2026
                Node day17Node = calendar.lookup("#calendar-day-2026-10-17");
                assertNotNull(day17Node);
                assertTrue(day17Node instanceof Button);
                Button day17Btn = (Button) day17Node;
                assertEquals(2, GridPane.getRowIndex(day17Node) == null ? 0 : GridPane.getRowIndex(day17Node));
                assertEquals(5, GridPane.getColumnIndex(day17Node) == null ? 0 : GridPane.getColumnIndex(day17Node));
                assertNotNull(day17Btn.lookup(".calendar-event-dot"), "Date with event must have a dot");

                // Test date click
                day17Btn.fire();
                assertEquals(LocalDate.of(2026, 10, 17), selectedDate.get());

                // Test Month Navigation (Next month -> Nov 2026)
                Button nextBtn = (Button) calendar.lookup("#calendar-next-month");
                nextBtn.fire();
                css(calendar);
                assertEquals("tháng 11 2026", titleLabel.getText());

                // Prev month twice -> Sep 2026
                Button prevBtn = (Button) calendar.lookup("#calendar-prev-month");
                prevBtn.fire(); // back to Oct 2026
                prevBtn.fire(); // back to Sep 2026
                css(calendar);
                assertEquals("tháng 9 2026", titleLabel.getText());

                // Snapshot MonthCalendar in Light and Dark
                ThemeManager.setLight(true);
                css(calendar);
                snapshot(calendar, "month-calendar-light");

                ThemeManager.setLight(false);
                css(calendar);
                snapshot(calendar, "month-calendar-dark");
            } finally {
                ThemeManager.setLight(original);
            }
            return null;
        });
    }

    @Test
    void createScheduleDialogValidatesLocallyAndSubmitsOverTls() throws Exception {
        setupHiddenClient();
        FxTestRuntime.call(() -> {
            boolean original = ThemeManager.isLight();
            try {
                LocalDate testDate = VietnamTime.today().plusDays(1);
                CreateScheduleDialog dialog = new CreateScheduleDialog(manager, testDate);
                DialogPane pane = dialog.getDialogPane();
                css(pane);

                assertNull(pane.lookup("#schedule-all-day"));
                assertNull(pane.lookup("#schedule-timezone-btn"));
                TextField titleField = (TextField) pane.lookup("#schedule-title");
                Label errorLabel = (Label) pane.lookup("#schedule-error");
                Button submitBtn = (Button) pane.lookup("#schedule-submit");

                // 1. Empty title validation
                titleField.setText("    ");
                submitBtn.fire();
                assertFalse(errorLabel.getText().isBlank(), "Error message must appear for empty title");
                assertTrue(errorLabel.getText().contains("tiêu đề"));
                assertFalse(serverActions.contains("CREATE_SCHEDULE"), "Validation failure must not call server");

                // 2. End time before start time validation
                titleField.setText("Họp nhóm đồ án MiniMeeting");
                ComboBox<String> startTimeCombo = (ComboBox<String>) pane.lookup("#schedule-start-time");
                ComboBox<String> endTimeCombo = (ComboBox<String>) pane.lookup("#schedule-end-time");
                startTimeCombo.setValue("16:00");
                endTimeCombo.setValue("14:00");
                submitBtn.fire();
                assertFalse(errorLabel.getText().isBlank(), "Error message must appear for end time < start time");
                assertTrue(errorLabel.getText().contains("sau"));
                assertFalse(serverActions.contains("CREATE_SCHEDULE"), "Validation failure must not call server");

                // 3. Valid submission
                endTimeCombo.setValue("17:00");
                TextArea descArea = (TextArea) pane.lookup("#schedule-description");
                descArea.setText("Nội dung họp rà soát mã nguồn đồ án");

                // Take snapshots before clicking submit
                ThemeManager.setLight(true);
                css(pane);
                snapshot(pane, "create-schedule-dialog-light");

                ThemeManager.setLight(false);
                css(pane);
                snapshot(pane, "create-schedule-dialog-dark");

                submitBtn.fire();
            } finally {
                ThemeManager.setLight(original);
            }
            return null;
        });

        // Wait for server to process and commit CREATE_SCHEDULE
        FxTestRuntime.await(() -> {
            try {
                return fixture.count("SELECT COUNT(*) FROM Lich_Hen WHERE Tieu_De = 'Họp nhóm đồ án MiniMeeting'") == 1;
            } catch (Exception e) {
                return false;
            }
        });
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM Lich_Hen WHERE Tieu_De = 'Họp nhóm đồ án MiniMeeting'"));
    }

    @Test
    void lobbyScreenShowsCorrectVietnamDateLabelListsAndDeletesSchedule() throws Exception {
        setupHiddenClient();

        // 1. Check Date label format in Lobby
        FxTestRuntime.call(() -> {
            Parent root = manager.stage().getScene().getRoot();
            css(root);

            Label dateLabel = (Label) root.lookup("#lobby-date-label");
            assertNotNull(dateLabel);
            String dateText = dateLabel.getText();
            assertFalse(dateText.isBlank());
            // Verify date format is like "Thứ Bảy, 17 thg 10" and NOT "Thứ , 17 thg 10"
            assertTrue(dateText.startsWith("Thứ ") || dateText.startsWith("Chủ Nhật"), "Must start with proper Vietnamese weekday name");
            assertFalse(dateText.startsWith("Thứ ,"), "Must not have missing weekday bug ('Thứ ,')");
            assertFalse(dateText.contains("Thứ ,"), "Must not contain 'Thứ ,'");

            // Empty state check
            Label statusLabel = (Label) root.lookup("#schedule-status");
            assertNotNull(statusLabel);
            assertEquals("Hôm nay bạn chưa có lịch hẹn", statusLabel.getText());

            // Open calendar popup via calendar icon button
            Button calendarBtn = (Button) root.lookup("#lobby-calendar-btn");
            assertNotNull(calendarBtn);

            // Add schedule button exists
            Button addBtn = (Button) root.lookup("#add-schedule-btn");
            assertNotNull(addBtn);

            return null;
        });

        // 2. Create a schedule for today via backend client
        LocalDate today = VietnamTime.today();
        Instant start = Instant.now().plusSeconds(1800);
        Instant end = start.plus(Duration.ofHours(1));
        ScheduleRequest req = new ScheduleRequest(
                "Lịch demo kiểm thử giao diện sảnh",
                "Mô tả lịch demo",
                start.toString(),
                end.toString(),
                "KHONG_LAP",
                null
        );
        AuthResponse created = fixture.client().createSchedule(user.token(), req);
        assertTrue(created.success());
        long scheduleId = created.schedule().id();

        // 3. Refresh lobby schedules to display the new schedule
        FxTestRuntime.call(() -> {
            LobbyScreen lobby = (LobbyScreen) manager.stage().getScene().getRoot();
            lobby.filterSchedulesForDate(LocalDate.ofInstant(start, VietnamTime.ZONE));
            return null;
        });

        // Wait for list schedules action and UI update
        FxTestRuntime.await(() -> {
            Parent root = manager.stage().getScene().getRoot();
            return root.lookup("#delete-schedule-" + scheduleId) != null;
        });

        // Verify schedule card contents and snapshots
        FxTestRuntime.call(() -> {
            boolean original = ThemeManager.isLight();
            try {
                Parent root = manager.stage().getScene().getRoot();
                css(root);

                Button deleteBtn = (Button) root.lookup("#delete-schedule-" + scheduleId);
                assertNotNull(deleteBtn, "Delete button for schedule must be present");

                // Snapshots of Lobby with schedule
                ThemeManager.setLight(true);
                css(root);
                snapshot(root, "lobby-schedule-light");

                ThemeManager.setLight(false);
                css(root);
                snapshot(root, "lobby-schedule-dark");

                // 4. Delete the schedule
                deleteBtn.fire();
            } finally {
                ThemeManager.setLight(original);
            }
            return null;
        });

        // Wait for DELETE_SCHEDULE server action
        FxTestRuntime.await(() -> serverActions.contains("DELETE_SCHEDULE"));

        // Wait for list to refresh and delete button to disappear
        FxTestRuntime.await(() -> {
            Parent root = manager.stage().getScene().getRoot();
            return root.lookup("#delete-schedule-" + scheduleId) == null;
        });

        // Verify empty state is restored
        FxTestRuntime.call(() -> {
            Parent root = manager.stage().getScene().getRoot();
            Label statusLabel = (Label) root.lookup("#schedule-status");
            assertNotNull(statusLabel);
            assertEquals("Hôm nay bạn chưa có lịch hẹn", statusLabel.getText());
            return null;
        });
    }

    @Test
    void recentRoomsLoadThreeThenTenPerClickAndHideMoreAtEnd() throws Exception {
        for (int i = 0; i < 27; i++) {
            fixture.execute("INSERT INTO Phong_Hop (Ma_Phong_Tham_Gia, Ten_Phong, Ma_Chu_Phong) VALUES ('MMR-UIROOM" + i
                    + "', 'Recent room " + i + "', " + user.user().id() + ")");
        }
        setupHiddenClient();
        FxTestRuntime.await(() -> manager.stage().getScene().getRoot().lookupAll(".room-row").size() == 3);
        for (int expected : new int[] {13, 23, 27}) {
            FxTestRuntime.call(() -> {
                Button more = (Button) manager.stage().getScene().getRoot().lookup("#more-recent-rooms");
                assertTrue(more.isVisible());
                if (expected == 23) {
                    ScrollPane scroll = (ScrollPane) ((LobbyScreen) manager.stage().getScene().getRoot()).getCenter();
                    scroll.setVvalue(0);
                    scroll.setVvalue(1);
                } else {
                    more.fire();
                }
                assertTrue(more.isDisabled());
                return null;
            });
            FxTestRuntime.await(() -> manager.stage().getScene().getRoot().lookupAll(".room-row").size() == expected);
        }
        FxTestRuntime.call(() -> {
            assertFalse(manager.stage().getScene().getRoot().lookup("#more-recent-rooms").isVisible());
            return null;
        });
    }

    @Test
    void calendarDisablesPastDatesButAllowsTodayAndFuture() throws Exception {
        FxTestRuntime.call(() -> {
            LocalDate today = VietnamTime.today();
            AtomicReference<LocalDate> picked = new AtomicReference<>();
            MonthCalendar calendar = new MonthCalendar(today, picked::set);
            calendar.setMinimumDate(today);
            new Scene(calendar);
            css(calendar);
            Button past = (Button) calendar.lookup("#calendar-day-" + today.minusDays(1));
            assertNotNull(past);
            assertTrue(past.isDisabled());
            past.fire();
            assertNull(picked.get());
            Button current = (Button) calendar.lookup("#calendar-day-" + today);
            assertFalse(current.isDisabled());
            current.fire();
            assertEquals(today, picked.get());
            assertTrue(calendar.getPrevButton().isDisabled());
            calendar.nextMonth();
            assertFalse(calendar.getPrevButton().isDisabled());
            assertFalse(((Button) calendar.lookup("#calendar-day-" + today.withDayOfMonth(1).plusMonths(1))).isDisabled());
            return null;
        });
    }

    @Test
    void timeMenusHideEarlierSlotsAndRefreshWhenOpenedAgain() throws Exception {
        setupHiddenClient();
        FxTestRuntime.call(() -> {
            var current = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-18T04:00:00Z"));
            Clock clock = new Clock() {
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
                public Instant instant() { return current.get(); }
            };
            CreateScheduleDialog dialog = new CreateScheduleDialog(manager, LocalDate.of(2026, 10, 18), clock);
            ComboBox<String> start = (ComboBox<String>) dialog.getDialogPane().lookup("#schedule-start-time");
            ComboBox<String> end = (ComboBox<String>) dialog.getDialogPane().lookup("#schedule-end-time");
            assertEquals("11:15", start.getItems().getFirst());
            assertFalse(start.getItems().contains("11:00"));
            start.setValue("12:00");
            assertEquals("12:15", end.getItems().getFirst());
            current.set(Instant.parse("2026-10-18T05:01:00Z"));
            start.getOnShowing().handle(new javafx.event.Event(ComboBoxBase.ON_SHOWING));
            assertEquals("12:30", start.getItems().getFirst());
            assertEquals("12:30", start.getValue());
            assertEquals("12:45", end.getItems().getFirst());
            CreateScheduleDialog future = new CreateScheduleDialog(manager, LocalDate.of(2026, 10, 19), clock);
            ComboBox<String> futureStart = (ComboBox<String>) future.getDialogPane().lookup("#schedule-start-time");
            assertEquals("00:00", futureStart.getItems().getFirst());
            current.set(Instant.parse("2026-10-18T16:50:00Z")); // 23:50 Vietnam
            start.getOnShowing().handle(new javafx.event.Event(ComboBoxBase.ON_SHOWING));
            assertEquals("00:15", start.getItems().getFirst());
            assertTrue(((Button) dialog.getDialogPane().lookup("#schedule-start-date-btn")).getText().contains("19"));
            return null;
        });
    }

    private void setupHiddenClient() throws Exception {
        FxTestRuntime.call(() -> {
            stage = new Stage();
            manager = new SceneManager(stage, fixture.client(), new FileTransferClient());
            manager.acceptSession(user);
            manager.lobby();
            Parent root = manager.stage().getScene().getRoot();
            css(root);
            assertFalse(stage.isShowing());
            return null;
        });
        FxTestRuntime.await(() -> serverActions.contains("LIST_ROOMS"));
    }

    private static void css(Parent root) {
        root.applyCss();
        root.layout();
    }

    private static void snapshot(Parent root, String name) throws Exception {
        css(root);
        var image = root.snapshot(null, null);
        var png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) {
            for (int x = 0; x < png.getWidth(); x++) {
                png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            }
        }
        Path path = Path.of("target", "qa", "themes", name + ".png");
        Files.createDirectories(path.getParent());
        ImageIO.write(png, "png", path.toFile());
    }
}
