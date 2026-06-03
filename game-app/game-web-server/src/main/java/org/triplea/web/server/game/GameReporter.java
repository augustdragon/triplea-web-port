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

  /** The game finished on its own (game over or round limit) — not a reset. */
  void gameFinished();
}
