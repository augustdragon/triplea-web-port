package org.triplea.web.controlplane.auth;

/**
 * An authenticated identity from any provider: the OAuth provider, the provider's opaque subject
 * id, and a display name. This is the seam every login path produces — {@code DevLoginController}
 * now, and the (future) pac4j Google/Discord callback — so the allow-list, user upsert, and JWT
 * logic don't care how the identity was obtained.
 */
public record Identity(String provider, String subject, String displayName) {}
