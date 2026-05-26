package org.triplea.web.server.game;

import com.google.gson.Gson;
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
import games.strategy.triplea.delegate.UndoableMove;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.data.CasualtyList;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.triplea.player.AbstractBasePlayer;
import games.strategy.triplea.util.TransportUtils;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
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
 * <p>Interactive so far: <b>purchase</b> (3b) and both <b>combat</b> and <b>non-combat move</b>
 * (3c) — land, sea, air, and transport load/unload. Every other decision method returns a safe
 * default so a full game still runs (e.g. the place phase auto-passes — units bought but not placed
 * are lost). 3d+ replaces more defaults with real browser panels (battle resolution next).
 */
@Slf4j
public final class WebPlayer extends AbstractBasePlayer {
  private static final Gson GSON = new Gson();
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
    } else if (GameStep.isNonCombatMoveStepName(stepName)) {
      handleMove(false);
    }
    // Other steps (place, battle, tech, politics): no action yet; 3d+ add them.
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
   * Move (combat or non-combat): loop asking the browser for one move at a time — drawing the units
   * from the route's first territory and submitting to the move delegate — until the browser says
   * done. Mirrors {@code TripleAPlayer.move}'s submit-then-recurse loop. Land, sea, air, and
   * transport load/unload (incl. amphibious assault) all go through the same path; the engine's
   * move delegate validates each one and the rejection text comes back as {@code error}.
   */
  private void handleMove(final boolean combat) {
    final GamePlayer player = getGamePlayer();
    final GameData data = getGameData();
    String error = null;
    while (!getPlayerBridge().isGameOver()) {
      final IMoveDelegate delegate = (IMoveDelegate) getPlayerBridge().getRemoteDelegate();
      final var reply =
          bridge.await(
              "move",
              new MoveRequest(
                  player.getName(),
                  combat,
                  movableUnits(player, data),
                  toUndoInfos(delegate.getMovesMade()),
                  error));
      if (reply.has("done") && reply.get("done").getAsBoolean()) {
        return; // browser ended the phase
      }
      if (reply.has("undoAll") && reply.get("undoAll").getAsBoolean()) {
        error = undoAll(delegate);
      } else if (reply.has("undo") && reply.get("undo").isJsonPrimitive()) {
        error = delegate.undoMove(reply.get("undo").getAsInt());
      } else {
        error = submitMove(player, data, reply);
      }
      if (error == null) {
        // Refresh the map now — the move phase runs entirely inside one engine step, so without
        // this the browser wouldn't see units shift (or snap back, on undo) until the phase ends.
        bridge.publishState(GSON.toJson(StateProjector.project(data)));
      }
    }
  }

  /**
   * Undo every move made this phase, most-recent first so each is the (always-undoable) last move
   * when removed and dependency chains unwind cleanly. Returns the first error, or null on success.
   */
  private static @Nullable String undoAll(final IMoveDelegate delegate) {
    while (!delegate.getMovesMade().isEmpty()) {
      final String error = delegate.undoMove(delegate.getMovesMade().size() - 1);
      if (error != null) {
        return error; // shouldn't happen undoing last-first, but relay it rather than loop forever
      }
    }
    return null;
  }

  /** Map the delegate's undo list to the browser payload (list position = the undo index). */
  static List<UndoableMoveInfo> toUndoInfos(final List<UndoableMove> moves) {
    final List<UndoableMoveInfo> result = new ArrayList<>();
    for (int i = 0; i < moves.size(); i++) {
      final UndoableMove move = moves.get(i);
      result.add(new UndoableMoveInfo(i, move.getMoveLabel(), move.getCanUndo()));
    }
    return result;
  }

  /**
   * What can still act this phase: a unit may move (movement left + can move) or it is land cargo
   * still aboard a transport (which "moves" by unloading, with zero movement left). Mirrors the
   * engine's own {@code MoveDelegate.delegateCurrentlyRequiresUserInput} predicate.
   */
  static Predicate<Unit> movableMatch(final GamePlayer player) {
    final var canAct = Matches.unitHasMovementLeft().and(Matches.unitCanMove());
    final var transportedCargo = Matches.unitIsLand().and(Matches.unitIsBeingTransported());
    return Matches.unitIsOwnedBy(player).and(canAct.or(transportedCargo));
  }

  /**
   * Territory name -> the player's actable units there, grouped by type (with display metadata).
   */
  private static Map<String, List<MovableUnit>> movableUnits(
      final GamePlayer player, final GameData data) {
    final Predicate<Unit> matcher = movableMatch(player);
    final Map<String, List<MovableUnit>> result = new TreeMap<>();
    for (final Territory territory : data.getMap().getTerritories()) {
      final List<Unit> movable =
          territory.getUnitCollection().getUnits().stream().filter(matcher).toList();
      if (movable.isEmpty()) {
        continue;
      }
      final Map<String, List<Unit>> byType = new TreeMap<>();
      for (final Unit unit : movable) {
        byType.computeIfAbsent(unit.getType().getName(), k -> new ArrayList<>()).add(unit);
      }
      final List<MovableUnit> rows = new ArrayList<>();
      byType.forEach(
          (type, units) -> {
            final Unit sample = units.get(0);
            final double maxMove =
                units.stream()
                    .map(Unit::getMovementLeft)
                    .max(Comparator.naturalOrder())
                    .orElse(BigDecimal.ZERO)
                    .doubleValue();
            rows.add(
                new MovableUnit(
                    type,
                    units.size(),
                    Matches.unitIsAir().test(sample),
                    Matches.unitIsSea().test(sample),
                    maxMove));
          });
      result.put(territory.getName(), rows);
    }
    return result;
  }

  /**
   * Resolve units + route from the reply, submit to the move delegate; return any error message.
   */
  private @Nullable String submitMove(
      final GamePlayer player, final GameData data, final JsonObject reply) {
    final List<String> routeNames = new ArrayList<>();
    if (reply.has("route") && reply.get("route").isJsonArray()) {
      for (final JsonElement element : reply.getAsJsonArray("route")) {
        routeNames.add(element.getAsString());
      }
    }
    final Map<String, Integer> unitCounts = new HashMap<>();
    if (reply.has("units") && reply.get("units").isJsonObject()) {
      for (final var entry : reply.getAsJsonObject("units").entrySet()) {
        unitCounts.put(entry.getKey(), entry.getValue().getAsInt());
      }
    }

    final MoveDescription move;
    try {
      move = buildMove(data, player, routeNames, unitCounts);
    } catch (final IllegalArgumentException e) {
      return e.getMessage(); // surfaced back to the browser as the rejection reason
    }

    final IMoveDelegate delegate = (IMoveDelegate) getPlayerBridge().getRemoteDelegate();
    return delegate.performMove(move).orElse(null);
  }

  /**
   * Turn a route (ordered territory names) and a unit-type→count selection into a {@link
   * MoveDescription} the move delegate can validate, resolving the actual {@link Unit}s from the
   * route's first territory. A land→sea route is treated as a transport <b>load</b>: the chosen
   * land units are mapped onto transports sitting in the destination sea zone (via {@link
   * TransportUtils#mapTransports}). Sea→land routes (unloads / amphibious assaults) and plain moves
   * need no mapping — the engine recovers any carrying transports from the source. Throws {@link
   * IllegalArgumentException} (with a user-facing message) on malformed input; rule legality is
   * left to the engine's {@code MoveValidator}. Package-visible for direct testing.
   */
  static MoveDescription buildMove(
      final GameData data,
      final GamePlayer player,
      final List<String> routeNames,
      final Map<String, Integer> unitCounts) {
    final List<Territory> path = new ArrayList<>();
    for (final String name : routeNames) {
      final Territory territory = data.getMap().getTerritoryOrNull(name);
      if (territory == null) {
        throw new IllegalArgumentException("Unknown territory: " + name);
      }
      path.add(territory);
    }
    if (path.size() < 2) {
      throw new IllegalArgumentException("A move needs a source and at least one destination");
    }
    final Route route = new Route(path);
    final Territory source = path.get(0);

    final List<Unit> units = new ArrayList<>();
    unitCounts.forEach(
        (type, count) ->
            source.getUnitCollection().getUnits().stream()
                .filter(u -> u.isOwnedBy(player) && u.getType().getName().equals(type))
                .filter(movableMatch(player))
                .limit(Math.max(0, count))
                .forEach(units::add));
    if (units.isEmpty()) {
      throw new IllegalArgumentException("No matching movable units in " + source.getName());
    }

    if (route.isLoad()) {
      final Collection<Unit> transports =
          route.getEnd().getUnitCollection().getMatches(Matches.unitIsSeaTransport());
      final Map<Unit, Unit> unitsToTransports =
          TransportUtils.mapTransports(route, units, transports);
      return new MoveDescription(units, route, unitsToTransports);
    }
    return new MoveDescription(units, route);
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
