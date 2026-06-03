package org.triplea.web.controlplane.game;

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

  /** A committed step: record the save and advance the game's live Round/Power/Phase position. */
  public void recordTurn(
      final UUID gameId,
      final int round,
      final String power,
      final String phase,
      final String bytesRef) {
    jdbi.useTransaction(
        handle -> {
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
        });
  }

  /** The game finished on its own. */
  public void markFinished(final UUID gameId) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    "UPDATE games SET status = 'finished', updated_at = now() WHERE id = :gid")
                .bind("gid", gameId)
                .execute());
  }
}
