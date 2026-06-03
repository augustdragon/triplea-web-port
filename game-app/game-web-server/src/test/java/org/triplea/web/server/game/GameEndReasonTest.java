package org.triplea.web.server.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Guards {@link GameController#classifyNaturalEnd} — how a loop exit maps to a {@link
 * GameEndReason}.
 */
class GameEndReasonTest {

  private static final int LIMIT = 10_000; // mirrors STEP_SAFETY_LIMIT

  @Test
  void victoryWinsOverEverything() {
    // Even at/over other thresholds, a real game-over is a VICTORY.
    assertEquals(GameEndReason.VICTORY, GameController.classifyNaturalEnd(true, 99, 20, LIMIT));
    assertEquals(GameEndReason.VICTORY, GameController.classifyNaturalEnd(true, 5, 20, 3));
  }

  @Test
  void roundCapWhenAiOnlyGameExceedsItsCap() {
    assertEquals(GameEndReason.ROUND_CAP, GameController.classifyNaturalEnd(false, 21, 20, 100));
  }

  @Test
  void humanGameIsUncappedSoRoundNeverTripsTheCap() {
    // Human games run with effectiveMaxRounds = Integer.MAX_VALUE — a high round is not an end.
    assertNull(GameController.classifyNaturalEnd(false, 500, Integer.MAX_VALUE, 100));
  }

  @Test
  void safetyLimitIsStuck() {
    assertEquals(
        GameEndReason.STUCK, GameController.classifyNaturalEnd(false, 3, Integer.MAX_VALUE, LIMIT));
  }

  @Test
  void noEndConditionReturnsNull() {
    assertNull(GameController.classifyNaturalEnd(false, 3, 20, 50));
  }
}
