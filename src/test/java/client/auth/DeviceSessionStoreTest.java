package client.auth;

import common.auth.AuthUser;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DeviceSessionStoreTest {
    @TempDir Path temporary;
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private AuthUser user(long id, String username) { return new AuthUser(id, username, "Tên " + username, username + "@example.com", true); }
    private DeviceSessionStore store(String file) { return new DeviceSessionStore(temporary.resolve(file), CLOCK); }

    @Test void identitySurvivesRestartAndProfilesHaveDifferentDevices() throws Exception {
        String device = store("one.properties").clientInfo();
        assertTrue(device.startsWith("device:"));
        assertEquals(device, store("one.properties").clientInfo());
        assertNotEquals(device, store("two.properties").clientInfo());
    }

    @Test void accountsPersistSwitchAndLogoutIndependently() throws Exception {
        var store = store("device.properties");
        store.saveSession(user(1, "first"), "token-one", NOW.plusSeconds(3600), true);
        store.saveSession(user(2, "second"), "token-two", NOW.plusSeconds(3600), true);
        assertEquals("second", store.activeSession().orElseThrow().username());
        assertEquals("second", store.listSessions().getFirst().username());
        store.clearActiveAccount();
        var restarted = store("device.properties");
        assertTrue(restarted.activeSession().isEmpty()); assertEquals(2, restarted.listSessions().size());
        restarted.saveSession(user(1, "first"), "token-one", NOW.plusSeconds(3600), true);
        assertEquals("first", store.activeSession().orElseThrow().username());
        restarted.removeToken("token-one");
        assertTrue(store.activeSession().isEmpty()); assertEquals("second", store.listSessions().getFirst().username());
        restarted.removeSession("second"); assertTrue(store.listSessions().isEmpty());
    }

    @Test void profileRenameUsesUserIdAndKeepsTokenExpiryAndActiveSelection() throws Exception {
        var store = store("device.properties");
        store.saveSession(user(1, "before"), "token", NOW.plusSeconds(3600), true);
        store.updateUser(new AuthUser(1, "after", "Tên mới", "new@example.com", false));
        var saved = store("device.properties").activeSession().orElseThrow();
        assertEquals("after", saved.username()); assertEquals("Tên mới", saved.displayName());
        assertEquals("new@example.com", saved.email()); assertFalse(saved.emailVerified());
        assertEquals("token", saved.token()); assertEquals(NOW.plusSeconds(3600), saved.expiresAt());
        assertEquals(1, store.listSessions().size());
        store.removeSession("before"); assertEquals(1, store.listSessions().size());
        store.removeSession("after"); assertTrue(store.activeSession().isEmpty());
    }

    @Test void readingPrunesExpiredSessionsOnDiskAtTheExactBoundary() throws Exception {
        var store = store("device.properties");
        String device = store.clientInfo();
        store.saveSession(user(1, "expired"), "expired-token", NOW.plusSeconds(1), true);
        store.saveSession(user(2, "valid"), "valid-token", NOW.plusSeconds(60), false);
        var later = new DeviceSessionStore(temporary.resolve("device.properties"), Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        assertTrue(later.activeSession().isEmpty()); assertEquals("valid", later.listSessions().getFirst().username());
        assertFalse(Files.readString(temporary.resolve("device.properties")).contains("expired-token"));
        assertEquals(device, later.clientInfo());
    }

    @Test void keepsOnlyTenMostRecentlySavedAccountsWithoutLeakingTokensToToString() throws Exception {
        var store = store("device.properties");
        for (int i = 1; i <= 12; i++) store.saveSession(user(i, "user" + i), "secret-token-" + i, NOW.plusSeconds(60), true);
        assertEquals(10, store.listSessions().size());
        assertEquals("user12", store.activeSession().orElseThrow().username());
        assertEquals("user12", store.listSessions().getFirst().username());
        assertFalse(store.listSessions().stream().anyMatch(s -> s.userId() <= 2));
        assertFalse(store.listSessions().toString().contains("secret-token"));
        try (var files = Files.list(temporary)) { assertFalse(files.anyMatch(p -> p.toString().endsWith(".tmp"))); }
    }

    @Test void simultaneousStoreInstancesDoNotLoseIdentityOrAccounts() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> { var store = store("shared.properties"); store.saveSession(user(1, "one"), "one", NOW.plusSeconds(60), true); return store.clientInfo(); });
            var two = workers.submit(() -> { var store = store("shared.properties"); store.saveSession(user(2, "two"), "two", NOW.plusSeconds(60), true); return store.clientInfo(); });
            assertEquals(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));
        }
        assertEquals(2, store("shared.properties").listSessions().size());
    }

    @Test void overrideSelectsIsolatedProfileAndNoopNeverWritesOrRetainsState() throws Exception {
        String previous = System.getProperty("mmr.session.store");
        Path profile = temporary.resolve("override.properties");
        try {
            System.setProperty("mmr.session.store", profile.toString());
            assertEquals(new DeviceSessionStore(profile).clientInfo(), DeviceSessionStore.local().clientInfo());
        } finally {
            if (previous == null) System.clearProperty("mmr.session.store"); else System.setProperty("mmr.session.store", previous);
        }
        var noop = DeviceSessionStore.noop();
        noop.saveSession(user(1, "one"), "token", NOW.plusSeconds(60), true);
        assertFalse(noop.isPersistent()); assertNull(noop.clientInfo());
        assertTrue(noop.listSessions().isEmpty()); assertTrue(noop.activeSession().isEmpty());
    }
}
