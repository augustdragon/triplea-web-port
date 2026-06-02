package org.triplea.web.controlplane.user;

import java.time.Instant;

/**
 * A row of the {@code users} table — the persisted identity behind a session. {@code playerChatId}
 * is the public, server-assigned handle; {@code oauthSubject} is the provider's opaque id. Returned
 * as-is from {@code GET /api/me} (it's the caller's own record).
 */
public record User(
    long id,
    String oauthProvider,
    String oauthSubject,
    String displayName,
    String playerChatId,
    String role,
    Instant createdAt,
    Instant lastLoginAt) {}
