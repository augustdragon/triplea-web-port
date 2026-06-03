package org.triplea.web.server.game;

import javax.annotation.Nullable;

/**
 * Reports a game container's lifecycle + per-turn progress back to the control plane, so the
 * control plane's DB stays the source of truth without holding the game in memory. Implementations:
 * {@link NoOpGameReporter} in standalone dev (no control plane), {@link HttpGameReporter} when
 * spawned with a {@code --control-plane-url}. Calls are best-effort and must never disturb the game
 * loop.
 */
interface GameReporter {

  /** The game has launched (or resumed) and is running. */
  void gameStarted();

  /**
   * A step committed and autosaved. {@code bytesRef} is the save-store slot now holding the latest
   * state; {@code power} is null for any non-power-scoped step.
   */
  void turnCommitted(int round, @Nullable String power, String phase, String bytesRef);

  /**
   * The game finished on its own — not a reset. {@code reason} is a {@link GameEndReason} name;
   * {@code winner} is a comma-separated list of winning powers, or null when there is no winner
   * (e.g. a round-cap or stuck end).
   */
  void gameFinished(String reason, @Nullable String winner);

  /**
   * A human player conceded {@code power} mid-game — it is now AI. Lets the control plane mark the
   * seat AI so a reconnecting (former) player is routed back as a spectator, not to a now-AI seat.
   */
  void seatResigned(String power);

  /**
   * The seat currently on the clock and its absolute deadline (epoch seconds), or {@code (null,
   * null)} to clear. Persisted to {@code seats.turn_deadline_at} so the turn timer survives a
   * container reap and is honored on rehydrate.
   */
  void turnDeadline(@Nullable String power, @Nullable Long deadlineEpoch);

  /**
   * Which seats currently have a live (human) connection. Persisted to {@code seats.connected} so
   * the lobby can show presence and the reaper won't cull a game people are still watching.
   */
  void presence(java.util.List<String> connectedPowers);
}
