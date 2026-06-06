package org.triplea.web.controlplane.notification;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends a "your turn" Web Push when a game advances to a disconnected human seat. Wired to the
 * turn-transition report (fired once per change of the active power, not per phase). Work happens on
 * a background executor so a slow/unreachable push service never blocks the reporting request, and a
 * subscription the push service reports as gone is pruned.
 */
@Slf4j
public final class TurnNotifier {

  private final PushSubscriptionDao dao;
  private final WebPushService pushService;
  private final ExecutorService executor;

  public TurnNotifier(
      final PushSubscriptionDao dao,
      final WebPushService pushService,
      final ExecutorService executor) {
    this.dao = dao;
    this.pushService = pushService;
    this.executor = executor;
  }

  /** It is now {@code power}'s turn ({@code phase}) in {@code gameId} — alert them if they're away. */
  public void onTurnAdvanced(final UUID gameId, final String power, final String phase) {
    executor.submit(
        () -> {
          try {
            deliver(gameId, power, phase);
          } catch (final RuntimeException e) {
            log.warn("Turn-alert delivery for {} failed: {}", power, e.getMessage());
          }
        });
  }

  private void deliver(final UUID gameId, final String power, final String phase) {
    final Optional<Long> userId = dao.disconnectedHumanSeatUserId(gameId, power);
    if (userId.isEmpty()) {
      return;
    }
    final List<PushSubscription> subscriptions = dao.subscriptionsForUser(userId.get());
    if (subscriptions.isEmpty()) {
      return;
    }
    final String payload = payload(gameId, power, phase);
    for (final PushSubscription subscription : subscriptions) {
      if (pushService.send(subscription, payload) == WebPushService.Result.GONE) {
        dao.deleteByEndpoint(subscription.endpoint());
      }
    }
  }

  private static String payload(final UUID gameId, final String power, final String phase) {
    final JsonObject json = new JsonObject();
    json.addProperty("title", "Your turn");
    json.addProperty("body", phase == null || phase.isBlank() ? power : power + " · " + phase);
    json.addProperty("gameId", gameId.toString());
    json.addProperty("url", "/game/" + gameId);
    return json.toString();
  }
}
