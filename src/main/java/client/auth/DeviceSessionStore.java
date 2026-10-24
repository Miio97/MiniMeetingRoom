package client.auth;

import common.auth.AuthUser;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Local bearer sessions. Never store passwords or log tokens. */
public final class DeviceSessionStore {
    public record SavedSession(
            long userId,
            String username,
            String displayName,
            String email,
            boolean emailVerified,
            String token,
            Instant expiresAt,
            Instant savedAt) {
        @Override
        public String toString() {
            return "SavedSession[userId=" + userId + "]";
        }
    }

    private static final int MAX_ACCOUNTS = 10;
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private final Path path;
    private final Clock clock;

    public DeviceSessionStore(Path path) {
        this(path, Clock.systemUTC());
    }

    DeviceSessionStore(Path path, Clock clock) {
        this.path = path == null ? null : path.toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock);
    }

    public static DeviceSessionStore local() {
        String override = System.getProperty("mmr.session.store");
        if (override != null && !override.isBlank())
            return new DeviceSessionStore(Path.of(override));
        String local = System.getenv("LOCALAPPDATA");
        Path directory =
                local == null || local.isBlank()
                        ? Path.of(System.getProperty("user.home"), ".minimeetingroom")
                        : Path.of(local, "MiniMeetingRoom");
        return new DeviceSessionStore(directory.resolve("device-sessions.properties"));
    }

    public static DeviceSessionStore noop() {
        return new DeviceSessionStore(null);
    }

    public boolean isPersistent() {
        return path != null;
    }

    public String clientInfo() throws IOException {
        return path == null ? null : access(state -> "device:" + state.deviceId);
    }

    public Optional<SavedSession> activeSession() throws IOException {
        if (path == null) return Optional.empty();
        return access(
                state ->
                        state.sessions.values().stream()
                                .filter(s -> s.username().equals(state.active))
                                .findFirst());
    }

    public List<SavedSession> listSessions() throws IOException {
        return path == null ? List.of() : access(State::ordered);
    }

    public void saveSession(AuthUser user, String token, Instant expiresAt, boolean makeActive)
            throws IOException {
        if (path == null) return;
        Objects.requireNonNull(user);
        Objects.requireNonNull(expiresAt);
        if (user.id() <= 0
                || user.username() == null
                || user.username().isBlank()
                || user.displayName() == null
                || token == null
                || token.isBlank()
                || token.length() > 256)
            throw new IOException("Phiên lưu trên thiết bị không hợp lệ.");
        access(
                state -> {
                    Instant savedAt = clock.instant();
                    // Preserve recency even with a fixed/low-resolution clock or a clock
                    // correction.
                    for (SavedSession saved : state.sessions.values()) {
                        if (!savedAt.isAfter(saved.savedAt()))
                            savedAt = saved.savedAt().plusNanos(1);
                    }
                    SavedSession previous = state.sessions.get(user.id());
                    if (previous != null && previous.username().equals(state.active))
                        state.active = user.username();
                    state.sessions.put(
                            user.id(),
                            new SavedSession(
                                    user.id(),
                                    user.username(),
                                    user.displayName(),
                                    user.email(),
                                    user.emailVerified(),
                                    token,
                                    expiresAt,
                                    savedAt));
                    if (makeActive) state.active = user.username();
                    state.dirty = true;
                    return null;
                });
    }

    public void updateUser(AuthUser user) throws IOException {
        if (path == null) return;
        access(
                state -> {
                    SavedSession previous = state.sessions.get(user.id());
                    if (previous != null) {
                        state.sessions.put(
                                user.id(),
                                new SavedSession(
                                        user.id(),
                                        user.username(),
                                        user.displayName(),
                                        user.email(),
                                        user.emailVerified(),
                                        previous.token(),
                                        previous.expiresAt(),
                                        previous.savedAt()));
                        if (previous.username().equals(state.active))
                            state.active = user.username();
                        state.dirty = true;
                    }
                    return null;
                });
    }

    public void clearActiveAccount() throws IOException {
        if (path != null)
            access(
                    state -> {
                        state.active = "";
                        state.dirty = true;
                        return null;
                    });
    }

    public void removeSession(String username) throws IOException {
        if (path != null)
            access(
                    state -> {
                        state.dirty |=
                                state.sessions
                                        .values()
                                        .removeIf(s -> s.username().equals(username));
                        return null;
                    });
    }

    public void removeToken(String token) throws IOException {
        if (path != null)
            access(
                    state -> {
                        state.dirty |=
                                state.sessions.values().removeIf(s -> s.token().equals(token));
                        return null;
                    });
    }

    @FunctionalInterface
    private interface Operation<T> {
        T apply(State state);
    }

    private <T> T access(Operation<T> operation) throws IOException {
        // Serialize read/modify/replace across store instances and across application processes.
        synchronized (LOCKS.computeIfAbsent(path, ignored -> new Object())) {
            Files.createDirectories(path.getParent());
            Path lock = path.resolveSibling(path.getFileName() + ".lock");
            try (FileChannel channel =
                            FileChannel.open(
                                    lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var held = channel.lock()) {
                State state = read();
                prune(state);
                T result = operation.apply(state);
                prune(state);
                if (state.dirty) write(state);
                return result;
            }
        }
    }

    private State read() throws IOException {
        Properties values = new Properties();
        if (Files.exists(path)) {
            if (Files.size(path) > 262144)
                throw new IOException("Tệp phiên thiết bị vượt giới hạn.");
            try (var reader = Files.newBufferedReader(path)) {
                values.load(reader);
            } catch (IllegalArgumentException malformed) {
                throw new IOException("Không đọc được tệp phiên thiết bị.", malformed);
            }
        }
        State state = new State();
        String deviceId = values.getProperty("device.id");
        if (deviceId == null) {
            state.deviceId = UUID.randomUUID().toString();
            state.dirty = true;
        } else {
            try {
                state.deviceId = UUID.fromString(deviceId).toString();
            } catch (IllegalArgumentException malformed) {
                throw new IOException("Định danh thiết bị không hợp lệ.", malformed);
            }
        }
        state.active = values.getProperty("active.username", "");
        for (String id : values.getProperty("session.ids", "").split(",")) {
            if (id.isBlank()) continue;
            String prefix = "session." + id + ".";
            try {
                long userId = Long.parseLong(id);
                String username = values.getProperty(prefix + "username");
                String token = values.getProperty(prefix + "token");
                String name = values.getProperty(prefix + "displayName");
                if (userId <= 0
                        || username == null
                        || username.isBlank()
                        || name == null
                        || token == null
                        || token.isBlank()
                        || token.length() > 256) throw new IllegalArgumentException();
                String email = values.getProperty(prefix + "email");
                state.sessions.put(
                        userId,
                        new SavedSession(
                                userId,
                                username,
                                name,
                                email,
                                email != null
                                        && !email.isBlank()
                                        && Boolean.parseBoolean(
                                                values.getProperty(prefix + "emailVerified")),
                                token,
                                Instant.parse(values.getProperty(prefix + "expiresAt")),
                                Instant.parse(values.getProperty(prefix + "savedAt"))));
            } catch (IllegalArgumentException
                    | java.time.DateTimeException
                    | NullPointerException malformed) {
                state.dirty = true;
            }
        }
        return state;
    }

    private void prune(State state) {
        state.dirty |=
                state.sessions.values().removeIf(s -> !s.expiresAt().isAfter(clock.instant()));
        List<SavedSession> ordered = state.ordered();
        for (int i = MAX_ACCOUNTS; i < ordered.size(); i++) {
            state.sessions.remove(ordered.get(i).userId());
            state.dirty = true;
        }
        if (!state.active.isEmpty()
                && state.sessions.values().stream()
                        .noneMatch(s -> s.username().equals(state.active))) {
            state.active = "";
            state.dirty = true;
        }
    }

    private void write(State state) throws IOException {
        Properties values = new Properties();
        values.setProperty("device.id", state.deviceId);
        values.setProperty("active.username", state.active);
        values.setProperty(
                "session.ids",
                String.join(
                        ",",
                        state.ordered().stream().map(s -> Long.toString(s.userId())).toList()));
        for (SavedSession session : state.sessions.values()) {
            String prefix = "session." + session.userId() + ".";
            values.setProperty(prefix + "username", session.username());
            values.setProperty(prefix + "displayName", session.displayName());
            if (session.email() != null) values.setProperty(prefix + "email", session.email());
            values.setProperty(prefix + "emailVerified", Boolean.toString(session.emailVerified()));
            values.setProperty(prefix + "token", session.token());
            values.setProperty(prefix + "expiresAt", session.expiresAt().toString());
            values.setProperty(prefix + "savedAt", session.savedAt().toString());
        }
        Path temporary = Files.createTempFile(path.getParent(), "device-sessions-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) {
                values.store(
                        writer, "Local device sessions; contains bearer tokens, never passwords.");
            }
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static final class State {
        String deviceId, active = "";
        boolean dirty;
        final Map<Long, SavedSession> sessions = new LinkedHashMap<>();

        List<SavedSession> ordered() {
            return sessions.values().stream()
                    .sorted(
                            Comparator.comparing(SavedSession::savedAt)
                                    .reversed()
                                    .thenComparing(SavedSession::username))
                    .toList();
        }
    }
}
