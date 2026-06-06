package org.triplea.web.controlplane.notification;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;
import java.util.Map;
import org.triplea.web.controlplane.auth.AuthFilter;
import org.triplea.web.controlplane.auth.Identity;
import org.triplea.web.controlplane.user.User;
import org.triplea.web.controlplane.user.UserDao;

/**
 * "Your turn" Web Push subscription management for the logged-in user. Registered only when VAPID
 * keys are configured (push enabled), so the client treats a 404 on {@code /api/push/vapid-key} as
 * "push unavailable" and hides the opt-in. Runs behind {@link AuthFilter} (it's under {@code
 * /api/*}), which has validated the session and stashed the identity.
 */
public final class PushController {

  /** Client subscribe body — the browser's {@code PushSubscription} flattened (keys hoisted). */
  public record SubscribeRequest(String endpoint, String p256dh, String auth) {}

  /** Client unsubscribe body. */
  public record UnsubscribeRequest(String endpoint) {}

  private final PushSubscriptionDao dao;
  private final UserDao userDao;
  private final String vapidPublicKey;

  public PushController(
      final PushSubscriptionDao dao, final UserDao userDao, final VapidKeys vapidKeys) {
    this.dao = dao;
    this.userDao = userDao;
    this.vapidPublicKey = vapidKeys.publicKeyBase64();
  }

  public void register(final Javalin app) {
    app.get("/api/push/vapid-key", this::vapidKey);
    app.post("/api/push/subscribe", this::subscribe);
    app.delete("/api/push/subscribe", this::unsubscribe);
  }

  private void vapidKey(final Context ctx) {
    ctx.json(Map.of("key", vapidPublicKey));
  }

  private void subscribe(final Context ctx) {
    final SubscribeRequest req = ctx.bodyAsClass(SubscribeRequest.class);
    if (req == null || isBlank(req.endpoint()) || isBlank(req.p256dh()) || isBlank(req.auth())) {
      throw new BadRequestResponse("endpoint, p256dh and auth are required");
    }
    dao.upsert(
        currentUser(ctx).id(), new PushSubscription(req.endpoint(), req.p256dh(), req.auth()));
    ctx.status(204);
  }

  private void unsubscribe(final Context ctx) {
    final UnsubscribeRequest req = ctx.bodyAsClass(UnsubscribeRequest.class);
    if (req == null || isBlank(req.endpoint())) {
      throw new BadRequestResponse("endpoint is required");
    }
    // Deleting by globally-unique endpoint is sufficient; the AuthFilter already proved a session.
    dao.deleteByEndpoint(req.endpoint());
    ctx.status(204);
  }

  private User currentUser(final Context ctx) {
    final Identity identity = ctx.attribute(AuthFilter.IDENTITY_ATTR);
    if (identity == null) {
      throw new UnauthorizedResponse("No session");
    }
    return userDao
        .findByIdentity(identity)
        .orElseThrow(() -> new UnauthorizedResponse("Session refers to an unknown user"));
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }
}
