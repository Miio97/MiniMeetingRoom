package client.UI;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Converts server timestamps to Vietnam time without changing stored instants or session deadlines.
 */
public final class VietnamTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Locale VIETNAMESE = Locale.forLanguageTag("vi-VN");
    private static final DateTimeFormatter DATE_TIME = formatter("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter FILE_TIME = formatter("dd/MM HH:mm");
    private static final DateTimeFormatter TIME = formatter("HH:mm");
    private static final String UNKNOWN = "Chưa rõ thời gian";

    private VietnamTime() {}

    public static LocalDate today() {
        return today(Clock.systemUTC());
    }

    public static LocalDate today(Clock clock) {
        return LocalDate.ofInstant(clock.instant(), ZONE);
    }

    public static String formatDateTime(String timestamp) {
        return format(timestamp, DATE_TIME);
    }

    public static String formatFileTime(String timestamp) {
        return format(timestamp, FILE_TIME);
    }

    public static String formatTime(Instant instant) {
        return TIME.format(instant);
    }

    public static String formatLobbyDate(LocalDate date) {
        if (date == null) date = today();
        String[] days = {
            "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ Nhật"
        };
        int dayIndex = date.getDayOfWeek().getValue() - 1;
        return days[dayIndex] + ", " + date.getDayOfMonth() + " thg " + date.getMonthValue();
    }

    public static String timeNow() {
        return formatTime(Instant.now());
    }

    private static DateTimeFormatter formatter(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, VIETNAMESE).withZone(ZONE);
    }

    private static String format(String timestamp, DateTimeFormatter formatter) {
        if (timestamp == null || timestamp.isBlank()) return UNKNOWN;
        try {
            return formatter.format(Instant.parse(timestamp.strip()));
        } catch (DateTimeParseException invalid) {
            return UNKNOWN;
        }
    }
}
