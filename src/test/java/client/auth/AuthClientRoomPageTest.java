package client.auth;

import com.google.gson.Gson;
import common.auth.AuthRequest;
import common.auth.AuthResponse;
import common.room.RoomInfo;
import common.room.RoomRequest;
import java.io.IOException;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuthClientRoomPageTest {
    private List<RoomInfo> rooms(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> new RoomInfo(i, "Room " + i,
                "MMR-" + String.format("%08d", i), 1, "2026-10-18T01:00:00Z", "DA_DONG",
                false, 10, 0, List.of())).toList();
    }
    private AuthRequest page(int offset, int limit) {
        return new AuthRequest("LIST_ROOMS", null, null, null, null, "token", RoomRequest.page(offset, limit));
    }

    @Test void legacySixteenRoomResponseCanBeReadAsThreeTenThree() throws Exception {
        // Old wire format has no hasMoreRooms field at all.
        Gson gson = new Gson();
        var json = gson.toJsonTree(AuthResponse.roomsOk("OK", rooms(16))).getAsJsonObject();
        json.remove("hasMoreRooms");
        AuthResponse legacy = gson.fromJson(json, AuthResponse.class);
        assertNull(legacy.hasMoreRooms());
        AuthResponse first = AuthClient.normalizeRoomPage(page(0, 3), legacy);
        assertEquals(3, first.rooms().size());
        assertTrue(first.hasMoreRooms());
        AuthResponse second = AuthClient.normalizeRoomPage(page(3, 10), legacy);
        assertEquals(10, second.rooms().size());
        assertEquals(4, second.rooms().getFirst().id());
        assertTrue(second.hasMoreRooms());
        AuthResponse last = AuthClient.normalizeRoomPage(page(13, 10), legacy);
        assertEquals(3, last.rooms().size());
        assertFalse(last.hasMoreRooms());
        assertTrue(AuthClient.normalizeRoomPage(page(16, 10), legacy).rooms().isEmpty());
    }

    @Test void modernPagesAreNotSlicedAgainAndOversizedResponsesAreRejected() throws Exception {
        AuthResponse modern = AuthResponse.roomsPage(rooms(10), true);
        assertSame(modern, AuthClient.normalizeRoomPage(page(13, 10), modern));
        assertThrows(IOException.class, () -> AuthClient.normalizeRoomPage(page(0, 3), modern));
        assertThrows(IOException.class, () -> AuthClient.normalizeRoomPage(page(0, 3),
                AuthResponse.roomsOk("OK", rooms(21))));
    }
}
