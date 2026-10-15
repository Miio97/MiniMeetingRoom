package client.UI;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("jvm-default-time-zone")
class VietnamTimeTest {
    @Test void serverInstantsAndExplicitOffsetsRenderAsTheSameVietnamTime() {
        for (String timestamp : new String[]{"2026-10-15T18:05:00Z", "2026-10-16T01:05:00+07:00",
                "2026-10-15T11:05:00-07:00", " 2026-10-15T18:05:00.123456Z "}) {
            assertEquals("16/10/2026 01:05", VietnamTime.formatDateTime(timestamp));
            assertEquals("16/10 01:05", VietnamTime.formatFileTime(timestamp));
        }
        assertEquals("01:05", VietnamTime.formatTime(Instant.parse("2026-10-15T18:05:00Z")));
    }

    @Test void conversionRollsOverDatesMonthsAndYears() {
        assertEquals("01/01/2027 00:00", VietnamTime.formatDateTime("2026-12-31T17:00:00Z"));
        assertEquals("01/11/2026 00:01", VietnamTime.formatDateTime("2026-10-31T17:01:00Z"));
        assertEquals("29/02/2028 00:30", VietnamTime.formatDateTime("2028-02-28T17:30:00Z"));
    }

    @Test void machineTimezoneAndLocaleDoNotChangeVietnamPresentationOrCalendarDay() {
        TimeZone originalZone = TimeZone.getDefault();
        Locale originalLocale = Locale.getDefault();
        Instant instant = Instant.parse("2026-10-15T18:05:00Z");
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            for (String zone : new String[]{"UTC", "America/Los_Angeles", "Asia/Tokyo"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertEquals("16/10/2026 01:05", VietnamTime.formatDateTime(instant.toString()));
                assertEquals("16/10 01:05", VietnamTime.formatFileTime(instant.toString()));
                assertEquals("01:05", VietnamTime.formatTime(instant));
                assertEquals(LocalDate.of(2026, 10, 16), VietnamTime.today(Clock.fixed(instant, ZoneId.of(zone))));
            }
            assertEquals(ZoneOffset.ofHours(7), VietnamTime.ZONE.getRules().getOffset(instant));
        } finally { TimeZone.setDefault(originalZone); Locale.setDefault(originalLocale); }
    }

    @Test void missingOrMalformedTimestampsHaveReadableFallbacks() {
        for (String timestamp : new String[]{null, "", "  ", "not-a-date", "2026-10-15 18:05:00"}) {
            assertEquals("Chưa rõ thời gian", VietnamTime.formatDateTime(timestamp));
            assertEquals("Chưa rõ thời gian", VietnamTime.formatFileTime(timestamp));
        }
    }
}
