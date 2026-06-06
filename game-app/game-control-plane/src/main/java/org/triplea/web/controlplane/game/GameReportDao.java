package org.triplea.web.controlplane.game;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;

/**
 * Persists the lifecycle/turn events a game container reports — the DB-is-source-of-truth writes.
 * {@link #recordTurn} runs in a transaction: insert the save row, then point the game's live
 * position + current_save_id at it, so a reader never sees the game advanced without a save behind
 * it.
 */
public final class GameReportDao {

  private final Jdbi jdbi;

  public GameReportDao(final Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  /** The container is up and running — ensure the game is marked active. */
  public void markStarted(final UUID gameId) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    "UPDATE games SET status = 'active', updated_at = now()"
                        + " WHERE id = :gid AND status <> 'finished'")
                .bind("gid", gameId)
                .execute());
  }

  /**
   * A committed step: record the save and advance the game's live Round/Power/Phase position.
   * Returns the active power <em>before</em> this update (null if none / unchanged-from-null) so
   * the caller can tell a real turn handover from a same-power phase change and fire a "your turn"
   * push only on the former.
   */
  public Optional<String> recordTurn(
      final UUID gameId,
      final int round,
      final String power,
      final String phase,
      final String bytesRef) {
    return jdbi.inTransaction(
        handle -> {
          final Optional<String> previousPower =
              handle
                  .createQuery("SELECT current_power FROM games WHERE id = :gid")
                  .bind("gid", gameId)
                  .mapTo(String.class)
                  .findOne();
          final long saveId =
              handle
                  .createUpdate(
                      "INSERT INTO saves (game_id, round, power, phase, bytes_ref)"
                          + " VALUES (:gid, :round, :power, :phase, :ref) RETURNING id")
                  .bind("gid", gameId)
                  .bind("round", round)
                  .bind("power", power)
                  .bind("phase", phase)
                  .bind("ref", bytesRef)
                  .executeAndReturnGeneratedKeys("id")
                  .mapTo(Long.class)
                  .one();
          handle
              .createUpdate(
                  "UPDATE games SET status = 'active', round = :round, current_power = :power,"
                      + " current_phase = :phase, current_save_id = :save, updated_at = now()"
                      + " WHERE id = :gid")
              .bind("round", round)
              .bind("power", power)
              .bind("phase", phase)
              .bind("save", saveId)
              .bind("gid", gameId)
              .execute();
          return previousPower;
        });
  }

  /** The game finished on its own — record why and (if any) the winner alongside the status. */
  public void markFinished(final UUID gameId, final String reason, final String winner) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    "UPDATE games SET status = 'finished', end_reason = :reason, winner = :winner,"
                        + " updated_at = now() WHERE id = :gid")
                .bind("reason", reason)
                .bind("winner", winner)
                .bind("gid", gameId)
                .execute());
  }

  /**
   * A player conceded — mark the seat AI (clears the user so a reconnect routes to a spectator).
   */
  public void resignSeat(final UUID gameId, final String power) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    "UPDATE seats SET kind = 'ai', user_id = NULL, ready = false"
                        + " WHERE game_id = :gid AND power_name = :power")
                .bind("gid", gameId)
                .bind("power", power)
                .execute());
  }

  /**
   * Set the absolute turn deadline (ISO-8601, or null to clear) for {@code power} and clear every
   * other seat's — at most one seat is ever "on the clock". The container reports this so the
   * deadline survives a container reap (it's read back from {@code seats.turn_deadline_at} on
   * rehydrate). A null {@code power} clears all deadlines for the game.
   */
  public void setTurnDeadline(final UUID gameId, final String power, final Long deadlineEpoch) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    "UPDATE seats SET turn_deadline_at ="
                        + " CASE WHEN power_name = :power THEN to_timestamp(:deadline)"
                        + " ELSE NULL END"
                        + " WHERE game_id = :gid")
                .bind("power", power)
                .bind("deadline", deadlineEpoch)
                .bind("gid", gameId)
                .execute());
  }

  /** Mark exactly {@code connectedPowers} as connected for the game (all others disconnected). */
  public void setPresence(final UUID gameId, final List<String> connectedPowers) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    "UPDATE seats SET connected = (power_name = ANY(:powers))"
                        + " WHERE game_id = :gid")
                .bindArray("powers", String.class, connectedPowers.toArray(new String[0]))
                .bind("gid", gameId)
                .execute());
  }

  /** The running container's id for a game, or empty if none is live. */
  public Optional<String> containerId(final UUID gameId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT container_id FROM games WHERE id = :gid")
                .bind("gid", gameId)
                .mapTo(String.class)
                .findOne());
  }

  /**
   * Clear the container handle + endpoint after a reap, so the next connect respawns. Also clears
   * presence — a reaped container has no live connections, so no seat is "connected" anymore.
   */
  public void clearContainer(final UUID gameId) {
    jdbi.useHandle(
        handle -> {
          handle
              .createUpdate(
                  "UPDATE games SET container_id = NULL, ws_endpoint = NULL WHERE id = :gid")
              .bind("gid", gameId)
              .execute();
          handle
              .createUpdate("UPDATE seats SET connected = false WHERE game_id = :gid")
              .bind("gid", gameId)
              .execute();
        });
  }

  /**
   * Games with a live container that haven't committed a turn within the idle window AND have no
   * connected player — so we don't reap a game someone is actively watching/thinking in.
   */
  public List<UUID> idleGameIds(final int idleSeconds) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT g.id FROM games g WHERE g.container_id IS NOT NULL"
                        + " AND g.updated_at < now() - (:secs * interval '1 second')"
                        + " AND NOT EXISTS ("
                        + "   SELECT 1 FROM seats s WHERE s.game_id = g.id AND s.connected)")
                .bind("secs", idleSeconds)
                .mapTo(UUID.class)
                .list());
  }
}
