package org.triplea.web.server.game;

import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the host-side victory detector — the fix for the engine's resource-loader-gated
 * triggered-victory firing (see docs/web-port/VICTORY-FIX-PLAN.md). Uses {@code victory_test.xml}
 * (the engine's own triggered-allied-ownership test map). The detector must find the winner the
 * engine itself won't fire headlessly, and must find nobody before the condition is met.
 */
class HostVictoryDetectorTest {
  private final GameData gameData = TestMapGameData.VICTORY_TEST.getGameData();

  @Test
  void noWinnerBeforeAnyVictoryConditionIsMet() {
    assertThat(HostVictoryDetector.detectWinners(gameData), is(empty()));
  }

  @Test
  void detectsTriggeredAlliedOwnershipVictory() {
    final GamePlayer germans = germans(gameData);
    // conditionAttachmentAxisVictory1: German alliance owns {United Kingdom, Russia, Germany,
    // Japan}.
    for (final String territoryName : List.of("United Kingdom", "Russia", "Germany", "Japan")) {
      gameData.performChange(
          ChangeFactory.changeOwner(territory(territoryName, gameData), germans));
    }

    final List<GamePlayer> winners = HostVictoryDetector.detectWinners(gameData);

    assertThat(
        "the satisfied Axis victory trigger's beneficiary (Germans) must be among the winners",
        winners,
        hasItem(germans));
  }
}
