package common.auth;

/** Public account data. Password hashes never leave the server. */
public record AuthUser(long id, String username, String displayName, String email) { }
