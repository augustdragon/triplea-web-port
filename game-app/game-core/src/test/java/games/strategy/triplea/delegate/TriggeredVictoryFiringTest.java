package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.attachments.AbstractTriggerAttachment;
import games.strategy.triplea.attachments.ICondition;
import games.strategy.triplea.attachments.TriggerAttachment;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Documents the engine constraint the web port's host-side victory detection works around (see
 * docs/web-port/VICTORY-FIX-PLAN.md and {@code HostVictoryDetector}).
 *
 * <p>A triggered allied-ownership victory's <b>condition</b> evaluates as satisfied, but the engine
 * only <b>fires</b> the victory (records winners via {@code signalGameOver}) when a {@code
 * ResourceLoader} is present — it is used to resolve the victory notification message ({@code
 * TriggerAttachment.triggerVictory}, the {@code bridge.getResourceLoader().ifPresent(...)} gate).
 * With no resource loader — as in any headless host, and here via {@code MockDelegateBridge} —
 * {@code signalGameOver} is skipped and no winner is recorded.
 *
 * <p>If this test ever fails (the engine fires victory without a resource loader), the host-side
 * workaround may have become unnecessary.
 */
class TriggeredVictoryFiringTest {
  private final GameData gameData = TestMapGameData.VICTORY_TEST.getGameData();
  private final GamePlayer germans = GameDataTestUtil.germans(gameData);

  @Test
  void conditionIsSatisfiedButEngineDoesNotFireVictoryWithoutAResourceLoader() {
    final IDelegateBridge bridge = newDelegateBridge(germans);

    // Seed the Axis (German alliance) owning all four territories of
    // conditionAttachmentAxisVictory1.
    for (final String territoryName : List.of("United Kingdom", "Russia", "Germany", "Japan")) {
      gameData.performChange(
          ChangeFactory.changeOwner(territory(territoryName, gameData), germans));
    }

    // The engine evaluates the victory trigger's condition as SATISFIED ...
    final Predicate<TriggerAttachment> endRoundMatch =
        AbstractTriggerAttachment.availableUses
            .and(AbstractTriggerAttachment.whenOrDefaultMatch(null, null))
            .and(TriggerAttachment.activateTriggerMatch().or(TriggerAttachment.victoryMatch()));
    final Set<TriggerAttachment> toFire =
        TriggerAttachment.collectForAllTriggersMatching(
            Set.copyOf(gameData.getPlayerList().getPlayers()), endRoundMatch);
    final Map<ICondition, Boolean> tested =
        TriggerAttachment.collectTestsForAllTriggers(toFire, bridge);
    final Predicate<TriggerAttachment> satisfied =
        AbstractTriggerAttachment.isSatisfiedMatch(tested);
    final boolean anyVictorySatisfied =
        toFire.stream()
            .anyMatch(t -> TriggerAttachment.victoryMatch().test(t) && satisfied.test(t));
    assertThat(
        "a victory trigger's condition must be satisfied after seeding",
        anyVictorySatisfied,
        is(true));

    // ... yet the engine records NO winner, because the bridge has no ResourceLoader.
    final EndRoundDelegate endRound = gameData.getEndRoundDelegate();
    endRound.setDelegateBridgeAndPlayer(bridge);
    endRound.start();
    assertThat(
        "engine does not fire triggered victory without a ResourceLoader"
            + " (the constraint HostVictoryDetector works around)",
        endRound.getWinners(),
        is(empty()));
  }
}
