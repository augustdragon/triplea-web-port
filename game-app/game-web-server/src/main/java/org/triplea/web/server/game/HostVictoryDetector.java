package org.triplea.web.server.game;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.attachments.AbstractTriggerAttachment;
import games.strategy.triplea.attachments.ICondition;
import games.strategy.triplea.attachments.TriggerAttachment;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;

/**
 * Detects an end-of-game victory from the live {@link GameData}, so the web host can end the game
 * itself. This exists because the engine's triggered-victory firing is gated on a UI notification
 * resource our headless host doesn't provide (see docs/web-port/VICTORY-FIX-PLAN.md); the *condition*
 * evaluation is correct, only the *acting* is gated.
 *
 * <p>Map-general: it reads whatever victory the loaded map defines (no hardcoded territories/sides)
 * and reuses the engine's own evaluation. Fail-closed: anything that can't be evaluated headless is
 * treated as not-a-victory, never a spurious win.
 */
@Slf4j
final class HostVictoryDetector {
  private HostVictoryDetector() {}

  /**
   * Winners detected at this point in the game, or an empty list if no victory condition is met.
   * Intended to be called at each round boundary (the engine's own victory-check cadence).
   */
  static List<GamePlayer> detectWinners(final GameData data) {
    final Set<GamePlayer> winners = new LinkedHashSet<>();
    winners.addAll(directModeWinners(data));
    winners.addAll(triggeredVictoryWinners(data));
    return List.copyOf(winners);
  }

  /**
   * Victory modes the engine signals directly in {@code EndRoundDelegate.start()} (economic, VP,
   * victory-cities, capital capture) — these are not resource-loader-gated, so the delegate already
   * recorded the winners. Reading them here also covers the case where the engine signalled game-over
   * but our custom step loop didn't auto-stop.
   */
  private static Set<GamePlayer> directModeWinners(final GameData data) {
    try {
      return Set.copyOf(data.getEndRoundDelegate().getWinners());
    } catch (final RuntimeException e) {
      log.debug("direct-mode winner read skipped: {}", e.toString());
      return Set.of();
    }
  }

  /**
   * The resource-loader-gated triggered-victory path, re-evaluated here with a read-only bridge.
   * Mirrors {@code EndRoundDelegate.start()}'s collection + condition test exactly, then keeps the
   * satisfied victory triggers.
   */
  private static Set<GamePlayer> triggeredVictoryWinners(final GameData data) {
    final Set<GamePlayer> winners = new LinkedHashSet<>();
    try {
      final Predicate<TriggerAttachment> endRoundMatch =
          AbstractTriggerAttachment.availableUses
              .and(AbstractTriggerAttachment.whenOrDefaultMatch(null, null))
              .and(TriggerAttachment.activateTriggerMatch().or(TriggerAttachment.victoryMatch()));
      final Set<TriggerAttachment> toFire =
          TriggerAttachment.collectForAllTriggersMatching(
              Set.copyOf(data.getPlayerList().getPlayers()), endRoundMatch);
      if (toFire.isEmpty()) {
        return winners;
      }
      final IDelegateBridge bridge = GameDataDelegateBridge.readOnly(data);
      final Map<ICondition, Boolean> tested =
          TriggerAttachment.collectTestsForAllTriggers(toFire, bridge);
      final Predicate<TriggerAttachment> satisfied =
          AbstractTriggerAttachment.isSatisfiedMatch(tested);
      final Predicate<TriggerAttachment> isVictory = TriggerAttachment.victoryMatch();
      for (final TriggerAttachment trigger : toFire) {
        if (isVictory.test(trigger) && satisfied.test(trigger)) {
          winners.addAll(beneficiaryAlliance(data, trigger));
        }
      }
    } catch (final RuntimeException e) {
      // Fail-closed: any evaluation problem ⇒ no triggered winner this pass.
      log.debug("triggered-victory detection skipped: {}", e.toString());
    }
    return winners;
  }

  /**
   * Winners for a satisfied victory trigger = the alliance of its beneficiary (the player the trigger
   * is attached to). {@code TriggerAttachment.getPlayers()} is private, so the alliance is used — it's
   * map-general and sufficient for surfacing which side won.
   */
  private static Set<GamePlayer> beneficiaryAlliance(
      final GameData data, final TriggerAttachment trigger) {
    if (trigger.getAttachedTo() instanceof GamePlayer beneficiary) {
      return data.getRelationshipTracker().getAllies(beneficiary, true);
    }
    return Set.of();
  }
}
