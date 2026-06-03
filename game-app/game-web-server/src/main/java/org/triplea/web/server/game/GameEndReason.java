package org.triplea.web.server.game;

/**
 * Why a game's run loop ended. Recorded on loop exit, surfaced to clients in the {@code
 * {type:"gameOver"}} envelope, and reported to the control plane so a finished game's row carries
 * the reason (not just {@code status=finished}). Distinguishes a real win from an abnormal stop —
 * the gap that made a victory indistinguishable from the round cap or the safety limit.
 */
enum GameEndReason {
  /** The engine's {@code EndRoundDelegate} signalled a victory ({@code isGameOver()} flipped). */
  VICTORY,
  /**
   * A player conceded and (rarely) it ended the game — concede normally resigns-to-AI and the game
   * continues; reserved for an "all human seats gone" end.
   */
  CONCEDED,
  /** A human seat was abandoned past its deadline with no takeover (reserved — see abandonment). */
  ABANDONED,
  /** The run loop threw — surfaced rather than swallowed. */
  ERROR,
  /** The progress watchdog or the {@code STEP_SAFETY_LIMIT} failsafe fired — a stuck/hung game. */
  STUCK,
  /** An AI-only game hit its configurable round cap (human games run uncapped). */
  ROUND_CAP,
  /** The host explicitly ended the session (reserved — see post-game review). */
  HOST_ENDED
}
