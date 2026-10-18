package common.schedule;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;

public final class ScheduleValidation {
    public static final int MAX_TITLE_LENGTH = 100;
    public static final int MAX_DESCRIPTION_LENGTH = 1000;
    public static final int MAX_USER_SCHEDULES = 200;
    public static final int MAX_LIST_RANGE_DAYS = 62;
    public static final int MIN_ADVANCE_MINUTES = 15;

    public static final Set<String> REPEAT_RULES = Set.of(
            "KHONG", "KHONG_LAP", "HANG_NGAY", "HANG_TUAN", "HANG_THANG"
    );

    private ScheduleValidation() { }

    public static String titleError(String title) {
        if (title == null || title.isBlank()) {
            return "Vui lòng nhập tiêu đề lịch hẹn.";
        }
        if (title.strip().length() > MAX_TITLE_LENGTH) {
            return "Tiêu đề không được vượt quá " + MAX_TITLE_LENGTH + " ký tự.";
        }
        return null;
    }

    public static String descriptionError(String description) {
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            return "Mô tả không được vượt quá " + MAX_DESCRIPTION_LENGTH + " ký tự.";
        }
        return null;
    }

    public static String timeRangeError(String startsAt, String endsAt) {
        if (startsAt == null || startsAt.isBlank()) {
            return "Thiếu thời gian bắt đầu.";
        }
        if (endsAt == null || endsAt.isBlank()) {
            return "Thiếu thời gian kết thúc.";
        }
        Instant start, end;
        try {
            start = Instant.parse(startsAt.strip());
        } catch (DateTimeParseException e) {
            return "Thời gian bắt đầu không đúng định dạng ISO-8601.";
        }
        try {
            end = Instant.parse(endsAt.strip());
        } catch (DateTimeParseException e) {
            return "Thời gian kết thúc không đúng định dạng ISO-8601.";
        }
        if (!end.isAfter(start)) {
            return "Thời gian kết thúc phải diễn ra sau thời gian bắt đầu.";
        }
        return null;
    }

    public static String advanceTimeError(Instant startsAt, Instant now) {
        if (startsAt == null || now == null) return null;
        if (startsAt.isBefore(now.plusSeconds(MIN_ADVANCE_MINUTES * 60L))) {
            return "Thời gian bắt đầu lịch hẹn phải cách thời điểm hiện tại ít nhất 15 phút.";
        }
        return null;
    }

    public static String repeatRuleOrFallback(String repeatRule) {
        if (repeatRule == null || "KHONG_LAP".equalsIgnoreCase(repeatRule) || !REPEAT_RULES.contains(repeatRule)) {
            return "KHONG";
        }
        return repeatRule;
    }
}
