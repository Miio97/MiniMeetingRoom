package server.auth;

import java.time.Instant;
import java.util.Objects;

/** Server-only identity derived from a live database session; never send or log it wholesale. */
public record SessionPrincipal(
        long sessionId, long userId, String displayName, Instant expiresAt, String tokenHash) {
    public SessionPrincipal {
        if (sessionId <= 0
                || userId <= 0
                || displayName == null
                || displayName.length() > 100
                || tokenHash == null
                || !tokenHash.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invalid session principal");
        }
        Objects.requireNonNull(expiresAt, "Session expiry required");
    }

    @Override
    public String toString() {
        return "SessionPrincipal[redacted]";
    }
}
