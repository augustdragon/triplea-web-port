package org.triplea.web.server.game;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.NamedAttachable;
import games.strategy.engine.data.ProductionFrontier;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.Constants;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.data.CasualtyList;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.triplea.player.AbstractBasePlayer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.triplea.java.collections.IntegerMap;
import org.triplea.util.Tuple;

/**
 * A human seat driven from the browser. Models its control flow on the Swing {@code TripleAPlayer}
 * (dispatch by step name in {@link #start}, submit every decision to the phase delegate so the
 * engine enforces the rules) but blocks on a {@link WebDecisionBridge} instead of {@code
 * CountDownLatch}/EDT.
 *
 * <p>Phase 3b scope: only the <b>purchase</b> step is interactive. Every other decision method
 * returns a safe default so a full game still runs (the human's move/place phases auto-pass — units
 * bought but not placed are lost, which is fine for proving the purchase round-trip). 3c+ replaces
 * the defaults with real browser panels.
 */
@Slf4j
public final class WebPlayer extends AbstractBasePlayer {
  private final WebDecisionBridge bridge;

  public WebPlayer(final String name, final String playerLabel, final WebDecisionBridge bridge) {
    super(name, playerLabel);
    this.bridge = bridge;
  }

  @Override
  public boolean isAi() {
    return false;
  }

  @Override
  public void start(final String stepName) {
    super.start(stepName); // waits for the player bridge to sync to this step
    if (getPlayerBridge().isGameOver()) {
      return;
    }
    if (GameStep.isPurchaseStepName(stepName) || GameStep.isBidStepName(stepName)) {
      handlePurchase(GameStep.isBidStepName(stepName));
    } else if (GameStep.isCombatMoveStepName(stepName)) {
      handleMove(true);
    }
    // Other steps (non-combat move, place, battle, tech, politics): no action yet; 3d+ add them.
  }

  /** Ask the browser what to buy, submit to the purchase delegate, loop until accepted. */
  private void handlePurchase(final boolean bid) {
    final GamePlayer player = getGamePlayer();
    final GameData data = getGameData();
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    final int pusAvailable =
        bid
            ? data.getProperties().get(player.getName() + " bid", 0)
            : player.getResources().getQuantity(pus);

    final ProductionFrontier frontier = player.getProductionFrontier();
    if (frontier == null) {
      return; // nothing this player can buy
    }
    final List<PurchaseOption> options = new ArrayList<>();
    final Map<String, ProductionRule> rulesByName = new HashMap<>();
    for (final ProductionRule rule : frontier.getRules()) {
      final int cost = rule.getCosts().getInt(pus);
      String produces = "";
      int quantity = 0;
      for (final NamedAttachable result : rule.getResults().keySet()) {
        produces = result.getName();
        quantity = rule.getResults().getInt(result);
        break; // summarize the primary produced unit
      }
      options.add(new PurchaseOption(rule.getName(), cost, produces, quantity));
      rulesByName.put(rule.getName(), rule);
    }

    String error = null;
    while (!getPlayerBridge().isGameOver()) {
      final JsonObject reply =
          bridge.await(
              "purchase", new PurchaseRequest(player.getName(), pusAvailable, bid, options, error));

      final IntegerMap<ProductionRule> chosen = new IntegerMap<>();
      if (reply.has("choices") && reply.get("choices").isJsonObject()) {
        for (final var entry : reply.getAsJsonObject("choices").entrySet()) {
          final ProductionRule rule = rulesByName.get(entry.getKey());
          final int count = entry.getValue().getAsInt();
          if (rule != null && count > 0) {
            chosen.put(rule, count);
          }
        }
      }

      final IPurchaseDelegate delegate = (IPurchaseDelegate) getPlayerBridge().getRemoteDelegate();
      error = delegate.purchase(chosen);
      if (error == null) {
        return; // accepted — purchase phase ends when start() returns
      }
      log.info("Purchase rejected for {}: {}", player.getName(), error);
      // loop: re-send the request with the error so the browser can re-choose
    }
  }

  /**
   * Combat move (3c, land only): loop asking the browser for one move at a time — drawing the units
   * from the route's first territory and submitting to the move delegate — until the browser says
   * done. Mirrors {@code TripleAPlayer.move}'s submit-then-recurse loop. Transports/air come later.
   */
  private void handleMove(final boolean combat) {
    final GamePlayer player = getGamePlayer();
    final GameData data = getGameData();
    String error = null;
    while (!getPlayerBridge().isGameOver()) {
      final var reply =
          bridge.await(
              "move", new MoveRequest(player.getName(), combat, movableUnits(player, data), error));
      if (reply.has("done") && reply.get("done").getAsBoolean()) {
        return; // browser ended the phase
      }
      error = submitMove(player, data, reply);
    }
  }

  /** Territory name -> (unit type -> count) for the player's units that still have movement. */
  private static Map<String, Map<String, Integer>> movableUnits(
      final GamePlayer player, final GameData data) {
    final var matcher =
        Matches.unitIsOwnedBy(player).and(Matches.unitHasMovementLeft()).and(Matches.unitCanMove());
    final Map<String, Map<String, Integer>> result = new TreeMap<>();
    for (final Territory territory : data.getMap().getTerritories()) {
      final List<Unit> movable =
          territory.getUnitCollection().getUnits().stream().filter(matcher).toList();
      if (movable.isEmpty()) {
        continue;
      }
      final Map<String, Integer> byType = new TreeMap<>();
      for (final Unit unit : movable) {
        byType.merge(unit.getType().getName(), 1, Integer::sum);
      }
      result.put(territory.getName(), byType);
    }
    return result;
  }

  /**
   * Resolve units + route from the reply, submit to the move delegate; return any error message.
   */
  private @Nullable String submitMove(
      final GamePlayer player, final GameData data, final JsonObject reply) {
    if (!reply.has("route") || !reply.get("route").isJsonArray()) {
      return "Move is missing a route";
    }
    final List<Territory> path = new ArrayList<>();
    for (final JsonElement element : reply.getAsJsonArray("route")) {
      final Territory territory = data.getMap().getTerritoryOrNull(element.getAsString());
      if (territory == null) {
        return "Unknown territory: " + element.getAsString();
      }
      path.add(territory);
    }
    if (path.size() < 2) {
      return "A move needs a source and at least one destination";
    }
    final Territory source = path.get(0);

    final List<Unit> units = new ArrayList<>();
    if (reply.has("units") && reply.get("units").isJsonObject()) {
      for (final var entry : reply.getAsJsonObject("units").entrySet()) {
        final String type = entry.getKey();
        final int count = entry.getValue().getAsInt();
        source.getUnitCollection().getUnits().stream()
            .filter(u -> u.isOwnedBy(player) && u.getType().getName().equals(type))
            .filter(Unit::hasMovementLeft)
            .limit(count)
            .forEach(units::add);
      }
    }
    if (units.isEmpty()) {
      return "No matching movable units in " + source.getName();
    }

    final IMoveDelegate delegate = (IMoveDelegate) getPlayerBridge().getRemoteDelegate();
    return delegate.performMove(new MoveDescription(units, new Route(path))).orElse(null);
  }

  // ---- Safe-default stubs (3c+ replaces these with real browser panels). ----

  @Override
  public CasualtyDetails selectCasualties(
      final Collection<Unit> selectFrom,
      final Map<Unit, Collection<Unit>> dependents,
      final int count,
      final String message,
      final DiceRoll dice,
      final GamePlayer hit,
      final Collection<Unit> friendlyUnits,
      final Collection<Unit> enemyUnits,
      final boolean amphibious,
      final Collection<Unit> amphibiousLandAttackers,
      final CasualtyList defaultCasualties,
      final UUID battleId,
      final Territory battlesite,
      final boolean allowMultipleHitsPerUnit) {
    return new CasualtyDetails(defaultCasualties, true); // accept the engine's auto-selection
  }

  @Override
  public int[] selectFixedDice(
      final int numDice, final int hitAt, final String title, final int diceSides) {
    return new int[numDice];
  }

  @Override
  public @Nullable Territory selectBombardingTerritory(
      final Unit unit,
      final Territory unitTerritory,
      final Collection<Territory> territories,
      final boolean noneAvailable) {
    return null; // do not bombard
  }

  @Override
  public boolean selectAttackSubs(final Territory unitTerritory) {
    return false;
  }

  @Override
  public boolean selectAttackTransports(final Territory unitTerritory) {
    return false;
  }

  @Override
  public boolean selectAttackUnits(final Territory unitTerritory) {
    return false;
  }

  @Override
  public boolean selectShoreBombard(final Territory unitTerritory) {
    return false;
  }

  @Override
  public void reportError(final String error) {
    log.warn("Engine reported error to {}: {}", getName(), error);
  }

  @Override
  public void reportMessage(final String message, final String title) {
    log.info("Engine message to {}: {} - {}", getName(), title, message);
  }

  @Override
  public boolean shouldBomberBomb(final Territory territory) {
    return false;
  }

  @Override
  public @Nullable Unit whatShouldBomberBomb(
      final Territory territory,
      final Collection<Unit> potentialTargets,
      final Collection<Unit> bombers) {
    return firstOrNull(potentialTargets);
  }

  @Override
  public @Nullable Territory whereShouldRocketsAttack(
      final Collection<Territory> candidates, final Territory from) {
    return null;
  }

  @Override
  public Collection<Unit> getNumberOfFightersToMoveToNewCarrier(
      final Collection<Unit> fightersThatCanBeMoved, final Territory from) {
    return new ArrayList<>(); // move no fighters onto the new carrier
  }

  @Override
  public @Nullable Territory selectTerritoryForAirToLand(
      final Collection<Territory> candidates,
      final Territory currentTerritory,
      final String unitMessage) {
    return firstOrNull(candidates); // must be non-null when candidates exist
  }

  @Override
  public boolean confirmMoveInFaceOfAa(final Collection<Territory> aaFiringTerritories) {
    return true;
  }

  @Override
  public boolean confirmMoveKamikaze() {
    return true;
  }

  @Override
  public Optional<Territory> retreatQuery(
      final UUID battleId,
      final boolean submerge,
      final Territory battleTerritory,
      final Collection<Territory> possibleTerritories,
      final String message) {
    return Optional.empty(); // do not retreat
  }

  @Override
  public Map<Territory, Collection<Unit>> scrambleUnitsQuery(
      final Territory scrambleTo,
      final Map<Territory, Tuple<Collection<Unit>, Collection<Unit>>> possibleScramblers) {
    return new HashMap<>(); // scramble nothing
  }

  @Override
  public Collection<Unit> selectUnitsQuery(
      final Territory current, final Collection<Unit> possible, final String message) {
    return new ArrayList<>();
  }

  @Override
  public void confirmEnemyCasualties(
      final UUID battleId, final String message, final GamePlayer hitPlayer) {}

  @Override
  public void confirmOwnCasualties(final UUID battleId, final String message) {}

  @Override
  public boolean acceptAction(
      final GamePlayer playerSendingProposal,
      final String acceptanceQuestion,
      final boolean politics) {
    return false; // reject political/diplomatic proposals
  }

  @Override
  public @Nullable Map<Territory, Map<Unit, IntegerMap<Resource>>> selectKamikazeSuicideAttacks(
      final Map<Territory, Collection<Unit>> possibleUnitsToAttack) {
    return null; // no kamikaze attacks
  }

  @Override
  public Tuple<Territory, Set<Unit>> pickTerritoryAndUnits(
      final List<Territory> territoryChoices,
      final List<Unit> unitChoices,
      final int unitsPerPick) {
    return Tuple.of(firstOrNull(territoryChoices), new HashSet<>());
  }

  private static <T> @Nullable T firstOrNull(final Collection<T> items) {
    return items.isEmpty() ? null : items.iterator().next();
  }
}
