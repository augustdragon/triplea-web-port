package org.triplea.web.controlplane.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.StatementContext;

/**
 * Reads and writes {@code push_subscriptions} (parameterized SQL, never concatenated). Also answers
 * the turn-alert question — "who, if anyone, should be pushed when it becomes {@code power}'s
 * turn?" — which is the only cross-table read: a push is sent only to the <em>human</em> holder of
 * the seat when they are <em>disconnected</em>.
 */
public final class PushSubscriptionDao {

  private final Jdbi jdbi;

  public PushSubscriptionDao(final Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  /** Store (or refresh) a subscription, keyed by its globally-unique endpoint. */
  public void upsert(final long userId, final PushSubscription sub) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    "INSERT INTO push_subscriptions (user_id, endpoint, p256dh_key, auth_key)"
                        + " VALUES (:uid, :endpoint, :p256dh, :auth)"
                        + " ON CONFLICT (endpoint) DO UPDATE SET"
                        + " user_id = EXCLUDED.user_id, p256dh_key = EXCLUDED.p256dh_key,"
                        + " auth_key = EXCLUDED.auth_key, last_used_at = now()")
                .bind("uid", userId)
                .bind("endpoint", sub.endpoint())
                .bind("p256dh", sub.p256dh())
                .bind("auth", sub.auth())
                .execute());
  }

  /**
   * Remove a subscription by endpoint (on unsubscribe, or when the push service says it's gone).
   */
  public void deleteByEndpoint(final String endpoint) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate("DELETE FROM push_subscriptions WHERE endpoint = :endpoint")
                .bind("endpoint", endpoint)
                .execute());
  }

  /** Every subscription a user has registered (one per browser/device). */
  public List<PushSubscription> subscriptionsForUser(final long userId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT endpoint, p256dh_key, auth_key FROM push_subscriptions"
                        + " WHERE user_id = :uid")
                .bind("uid", userId)
                .map(PushSubscriptionDao::mapSubscription)
                .list());
  }

  /**
   * The user holding {@code power} in {@code gameId} if (and only if) that seat is a human seat
   * that is currently disconnected — i.e. the player who should get a "your turn" push. Returns
   * empty for a connected human (they're already looking at the game), an AI/open seat, or no such
   * seat.
   */
  public Optional<Long> disconnectedHumanSeatUserId(final UUID gameId, final String power) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT user_id FROM seats WHERE game_id = :gid AND power_name = :power"
                        + " AND kind = 'human' AND connected = false AND user_id IS NOT NULL")
                .bind("gid", gameId)
                .bind("power", power)
                .mapTo(Long.class)
                .findOne());
  }

  private static PushSubscription mapSubscription(final ResultSet rs, final StatementContext ctx)
      throws SQLException {
    return new PushSubscription(
        rs.getString("endpoint"), rs.getString("p256dh_key"), rs.getString("auth_key"));
  }
}
