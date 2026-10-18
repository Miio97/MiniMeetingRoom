package server.schedule;

import client.auth.AuthClient;
import common.auth.AuthResponse;
import common.room.RoomInfo;
import common.schedule.ScheduleInfo;
import common.schedule.ScheduleRequest;
import com.google.gson.Gson;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import support.RoomTestFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Tests CREATE, LIST, and DELETE schedule operations over real TLS transport. */
@Timeout(30)
class ScheduleTransportTest {
    @TempDir static Path temporary;
    private static RoomTestFixture.Tls tls;

    @BeforeAll @Timeout(60)
    static void certificates() throws Exception {
        tls = RoomTestFixture.certificates(temporary);
    }

    @Test
    void scheduleCreateListAndDeleteOverTls() throws Exception {
        try (RoomTestFixture fixture = new RoomTestFixture(tls)) {
            AuthClient client = fixture.client();
            AuthResponse host = fixture.account("HostScheduleUser", "Chủ phòng lịch");
            AuthResponse guest = fixture.account("GuestScheduleUser", "Khách");

            RoomInfo room = client.createRoom(host.token(), "Phòng họp cho lịch", "123456", 5).room();
            assertNotNull(room);

            Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            Instant end = start.plus(1, ChronoUnit.HOURS);

            ScheduleRequest createReq = new ScheduleRequest(
                    "Họp đánh giá tiến độ đồ án",
                    "Đánh giá phần tính năng lịch hẹn qua TLS",
                    start.toString(),
                    end.toString(),
                    "KHONG",
                    room.code()
            );

            // 1. Create schedule
            AuthResponse created = client.createSchedule(host.token(), createReq);
            assertTrue(created.success(), created::message);
            ScheduleInfo schedule = created.schedule();
            assertNotNull(schedule);
            assertTrue(schedule.id() > 0);
            assertEquals("Họp đánh giá tiến độ đồ án", schedule.title());
            assertEquals("Đánh giá phần tính năng lịch hẹn qua TLS", schedule.description());
            assertEquals(start.toString(), schedule.startsAt());
            assertEquals(end.toString(), schedule.endsAt());
            assertEquals("KHONG", schedule.repeatRule());
            assertEquals(room.id(), schedule.roomId());
            assertEquals(room.code(), schedule.roomCode());
            assertNoPrivateSecrets(created);

            // 2. List schedules
            Instant from = start.minus(1, ChronoUnit.DAYS);
            Instant to = end.plus(1, ChronoUnit.DAYS);
            AuthResponse listResp = client.listSchedules(host.token(), from.toString(), to.toString());
            assertTrue(listResp.success(), listResp::message);
            assertNotNull(listResp.schedules());
            assertEquals(1, listResp.schedules().size());
            assertEquals(schedule.id(), listResp.schedules().getFirst().id());
            assertEquals(room.code(), listResp.schedules().getFirst().roomCode());

            // Guest listing over TLS should see empty list (isolation)
            AuthResponse guestList = client.listSchedules(guest.token(), from.toString(), to.toString());
            assertTrue(guestList.success(), guestList::message);
            assertTrue(guestList.schedules().isEmpty());

            // 3. Guest cannot delete host's schedule
            AuthResponse guestDelete = client.deleteSchedule(guest.token(), schedule.id());
            assertFalse(guestDelete.success());
            assertEquals("PERMISSION_DENIED", guestDelete.code());

            // 4. Host deletes schedule
            AuthResponse hostDelete = client.deleteSchedule(host.token(), schedule.id());
            assertTrue(hostDelete.success(), hostDelete::message);

            // 5. Deleting again returns SCHEDULE_ALREADY_DELETED
            AuthResponse deleteAgain = client.deleteSchedule(host.token(), schedule.id());
            assertFalse(deleteAgain.success());
            assertEquals("SCHEDULE_ALREADY_DELETED", deleteAgain.code());

            // 6. List after deletion should now be empty
            AuthResponse listAfter = client.listSchedules(host.token(), from.toString(), to.toString());
            assertTrue(listAfter.success());
            assertTrue(listAfter.schedules().isEmpty());
        }
    }

    @Test
    void rejectsInvalidInputsAndExpiredSessions() throws Exception {
        try (RoomTestFixture fixture = new RoomTestFixture(tls)) {
            AuthClient client = fixture.client();
            AuthResponse user = fixture.account("TestUser", "Người dùng");

            Instant now = Instant.now();
            // Invalid: end before start
            ScheduleRequest invalidTime = new ScheduleRequest(
                    "Lịch lỗi giờ",
                    null,
                    now.plus(2, ChronoUnit.HOURS).toString(),
                    now.plus(1, ChronoUnit.HOURS).toString(),
                    "KHONG_LAP",
                    null
            );
            AuthResponse respInvalidTime = client.createSchedule(user.token(), invalidTime);
            assertFalse(respInvalidTime.success());
            assertEquals("VALIDATION_ERROR", respInvalidTime.code());

            // Invalid: empty title
            ScheduleRequest emptyTitle = new ScheduleRequest(
                    "   ",
                    null,
                    now.toString(),
                    now.plus(1, ChronoUnit.HOURS).toString(),
                    "KHONG_LAP",
                    null
            );
            AuthResponse respEmptyTitle = client.createSchedule(user.token(), emptyTitle);
            assertFalse(respEmptyTitle.success());
            assertEquals("VALIDATION_ERROR", respEmptyTitle.code());

            // Invalid: date range > 62 days
            AuthResponse respRangeExceeded = client.listSchedules(
                    user.token(),
                    now.toString(),
                    now.plus(65, ChronoUnit.DAYS).toString()
            );
            assertFalse(respRangeExceeded.success());
            assertEquals("VALIDATION_ERROR", respRangeExceeded.code());

            // Expired/Invalid token
            AuthResponse respInvalidToken = client.createSchedule("fake-session-token", invalidTime);
            assertFalse(respInvalidToken.success());
            assertEquals("SESSION_EXPIRED", respInvalidToken.code());

            assertTrue(client.logout(user.token()).success());
            AuthResponse respLoggedOut = client.listSchedules(user.token(), now.toString(), now.plus(1, ChronoUnit.DAYS).toString());
            assertFalse(respLoggedOut.success());
            assertEquals("SESSION_EXPIRED", respLoggedOut.code());
        }
    }

    private static void assertNoPrivateSecrets(AuthResponse response) {
        String json = new Gson().toJson(response);
        for (String secret : List.of("\"pin\"", "\"password\"", "Ma_PIN_Bam", "Mat_Khau_Bam", "pbkdf2-sha256")) {
            assertFalse(json.contains(secret), "Private room credentials must not appear in a response");
        }
    }
}
