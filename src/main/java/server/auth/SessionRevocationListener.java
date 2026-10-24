package server.auth;

/** Local invalidation after committed logout; the hash must never be logged. */
@FunctionalInterface
public interface SessionRevocationListener {
    SessionRevocationListener NONE = hash -> {};

    void revoked(String tokenHash);
}
