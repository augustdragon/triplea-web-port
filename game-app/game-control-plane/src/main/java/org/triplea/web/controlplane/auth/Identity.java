package org.triplea.web.controlplane.auth;

/**
 * An authenticated identity from any provider: the OAuth provider, the provider's opaque subject
 * id, a display name, and (when the provider supplies it) the verified email. This is the seam
 * every login path produces — {@code DevLoginController} and the Google OAuth callback — so the
 * allow-list, user upsert, and JWT logic don't care how the identity was obtained.
 *
 * <p>{@code email} is nullable (dev-login and providers that don't return one leave it null). It
 * lets a private group invite by email — which they know in advance — rather than by the opaque
 * provider subject, which is only knowable after a first login.
 */
public record Identity(String provider, String subject, String displayName, String email) {}
