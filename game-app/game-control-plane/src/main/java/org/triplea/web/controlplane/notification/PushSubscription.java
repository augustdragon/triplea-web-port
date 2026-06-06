package org.triplea.web.controlplane.notification;

/**
 * A browser push subscription: the push service's per-subscription {@code endpoint} URL plus the
 * keys needed to encrypt to it ({@code p256dh} = the subscription's public key, {@code auth} = its
 * shared secret), both base64url. This is exactly the shape the browser's {@code
 * PushSubscription.toJSON()} produces (keys nested under {@code keys}); the client flattens it when
 * POSTing to {@code /api/push/subscribe}.
 */
public record PushSubscription(String endpoint, String p256dh, String auth) {}
