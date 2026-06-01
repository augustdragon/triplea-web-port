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
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.PoliticalActionAttachment;
import games.strategy.triplea.delegate.AbstractMoveDelegate;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.GameStepPropertiesHelper;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.UndoableMove;
import games.strategy.triplea.delegate.battle.BattleDelegate;
import games.strategy.triplea.delegate.battle.IBattle;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.data.CasualtyList;
import games.strategy.triplea.delegate.move.validation.MoveValidator;
import games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate;
import games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate.BidMode;
import games.strategy.triplea.delegate.remote.IBattleDelegate;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.delegate.remote.IPoliticsDelegate;
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
 * <p>Interactive so far: <b>politics</b> (declarations of war — {@link #handlePolitics}),
 * <b>purchase</b> (3b), <b>combat</b> and <b>non-combat move</b> (3c — land, sea, air, transport
 * load/unload, undo), <b>battle resolution</b> (3d — {@link #selectCasualties} and {@link
 * #retreatQuery}), and <b>place</b> (3e — {@link #handlePlace}). Remaining decision methods return
 * a safe default so a full game still runs. Next: Pacific naval/air queries (3f — scramble,
 * kamikaze, bombardment), then hotseat (3g).
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

  /**
   * Send a decision request to <i>this seat</i> and block for the reply. Tags the request with this
   * player's name so {@link WebDecisionBridge} routes it to the owning connection and rejects a
   * reply from any other seat.
   */
  private JsonObject await(final String kind, final Object payload) {
    return bridge.await(getGamePlayer().getName(), kind, payload);
  }

  @Override
  public void start(final String stepName) {
    super.start(stepName); // waits for the player bridge to sync to this step
    if (getPlayerBridge().isGameOver()) {
      return;
    }
    if (GameStep.isPoliticsStepName(stepName)) {
      handlePolitics();
    } else if (GameStep.isPurchaseStepName(stepName) || GameStep.isBidStepName(stepName)) {
      handlePurchase(GameStep.isBidStepName(stepName));
    } else if (GameStep.isCombatMoveStepName(stepName)) {
      handleMove(true);
    } else if (GameStep.isNonCombatMoveStepName(stepName)) {
      handleMove(false);
    } else if (GameStep.isBattleStepName(stepName)) {
      handleBattle();
    } else if (GameStep.isPlaceStepName(stepName)) {
      handlePlace();
    }
    // Other steps (tech): no action yet.
  }

  /**
   * Politics phase (the first step of a nation's turn): offer the player's currently-legal
   * political actions (declarations of war, treaties) and let the browser <i>stage</i> a set of
   * them, applying none until the player ends the phase. This gives true in-phase undo — staging is
   * reversible client-side, and nothing reaches the engine until "End Politics Phase". On commit we
   * apply each staged action via the politics delegate, which flips relationships and so reshapes
   * what is a legal move this turn (declaring war on the USA makes US territory attackable — the
   * move delegate enforces that automatically).
   *
   * <p>Because the engine has no political undo, the staged set is the only "change your mind"
   * window — but we can't fully predict interactions between staged actions (e.g. the combined "war
   * on all Allies" makes a separate "war on Britain" redundant). So {@link #applyStaged} re-checks
   * {@code getValidActions} immediately before each action and skips any that an earlier one
   * already resolved; if any were skipped we re-prompt with the refreshed list and a note, rather
   * than silently dropping them. An empty commit (or no legal actions — e.g. China, which has a
   * politics step but none) ends the phase. The engine still applies automatic politics (mandatory
   * US entry at end of round 3, the mobilization bonus) via triggers regardless of this seat.
   */
  private void handlePolitics() {
    final IPoliticsDelegate delegate = (IPoliticsDelegate) getPlayerBridge().getRemoteDelegate();
    final Resource pus = getGameData().getResourceList().getResourceOrThrow(Constants.PUS);
    String error = null;
    while (!getPlayerBridge().isGameOver()) {
      final List<PoliticalActionOption> options = new ArrayList<>();
      for (final PoliticalActionAttachment action : delegate.getValidActions()) {
        options.add(toActionOption(action, getGamePlayer(), pus));
      }
      if (options.isEmpty()) {
        return; // nothing legal (or nothing left after a prior commit) — end the phase
      }
      final JsonObject reply =
          await("politics", new PoliticsRequest(getGamePlayer().getName(), options, error));
      final List<String> staged = new ArrayList<>();
      if (reply.has("commit") && reply.get("commit").isJsonArray()) {
        for (final JsonElement element : reply.getAsJsonArray("commit")) {
          staged.add(element.getAsString());
        }
      }
      if (staged.isEmpty()) {
        return; // player ended the phase with nothing (more) to declare
      }
      final List<String> skipped = applyStaged(delegate, staged);
      if (skipped.isEmpty()) {
        return; // every staged declaration applied — phase done
      }
      error = "Skipped (already resolved by another declaration): " + String.join(", ", skipped);
      // loop: re-prompt with the refreshed action list so the player can adjust or finish.
    }
  }

  /**
   * Apply each staged action in order, re-reading {@code getValidActions} just before each so an
   * action invalidated by an earlier one (overlapping declarations) is skipped rather than wrongly
   * applied. Returns the names that were skipped (no longer valid at their turn to apply). Each
   * applied action re-broadcasts state so the map/relationship grid update immediately.
   */
  private List<String> applyStaged(final IPoliticsDelegate delegate, final List<String> staged) {
    final List<String> skipped = new ArrayList<>();
    for (final String name : staged) {
      final Map<String, PoliticalActionAttachment> validNow = new HashMap<>();
      for (final PoliticalActionAttachment action : delegate.getValidActions()) {
        validNow.put(action.getName(), action);
      }
      final PoliticalActionAttachment action = validNow.get(name);
      if (action == null) {
        skipped.add(name);
        continue;
      }
      // attemptAction is void: it charges any cost, rolls (auto-success in Pacific), and applies
      // the
      // relationship changes; the engine reports the outcome text via reportMessage.
      delegate.attemptAction(action);
      bridge.publishState(GSON.toJson(StateProjector.project(getGameData())));
    }
    return skipped;
  }

  /**
   * Render an engine political action for the browser: a concise {@code summary} headline (what the
   * acting player does — the powers it declares war on), the full {@code changes} list (for hover
   * detail), cost, and odds.
   */
  private static PoliticalActionOption toActionOption(
      final PoliticalActionAttachment action, final GamePlayer me, final Resource pus) {
    final List<String> changes = new ArrayList<>();
    final List<String> warTargets = new ArrayList<>();
    for (final PoliticalActionAttachment.RelationshipChange change :
        action.getRelationshipChanges()) {
      changes.add(
          change.player1.getName()
              + " → "
              + change.player2.getName()
              + ": "
              + change.relationshipType.getName());
      if (change.player1.equals(me)
          && change.relationshipType.getRelationshipTypeAttachment().isWar()) {
        warTargets.add(change.player2.getName());
      }
    }
    final String summary =
        warTargets.isEmpty()
            ? "Political action"
            : "Declare war on " + String.join(", ", warTargets);
    final int hit = action.getChanceToHit();
    final int sides = action.getChanceDiceSides();
    final String chance = sides <= 0 || hit >= sides ? "auto" : hit + "/" + sides;
    return new PoliticalActionOption(
        action.getName(), summary, changes, action.getCostResources().getInt(pus), chance);
  }

  /** Ask the browser what to buy, submit to the purchase delegate, loop until accepted. */
  /**
   * Groups a produced unit into the client's purchase columns: factories, AA guns, and other
   * infrastructure/construction are {@code "building"}; otherwise {@code "naval"}/{@code "air"}/
   * {@code "land"} by domain. (AA guns are caught by the AA check even when, as on Pacific 1940,
   * they're mobile combat units rather than {@code isInfrastructure}.)
   */
  private static String categoryOf(final NamedAttachable produced) {
    if (!(produced instanceof final UnitType type)) {
      return "land";
    }
    if (Matches.unitTypeIsAaForAnything().test(type)
        || Matches.unitTypeCanProduceUnits().test(type)
        || Matches.unitTypeIsInfrastructure().test(type)
        || Matches.unitTypeIsConstruction().test(type)) {
      return "building";
    }
    if (Matches.unitTypeIsSea().test(type)) {
      return "naval";
    }
    if (Matches.unitTypeIsAir().test(type)) {
      return "air";
    }
    return "land";
  }

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
      String category = "land";
      for (final NamedAttachable result : rule.getResults().keySet()) {
        produces = result.getName();
        quantity = rule.getResults().getInt(result);
        category = categoryOf(result);
        break; // summarize the primary produced unit
      }
      options.add(new PurchaseOption(rule.getName(), cost, produces, quantity, category));
      rulesByName.put(rule.getName(), rule);
    }

    String error = null;
    while (!getPlayerBridge().isGameOver()) {
      final JsonObject reply =
          await(
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
    MovePreview preview = null;
    while (!getPlayerBridge().isGameOver()) {
      final IMoveDelegate delegate = (IMoveDelegate) getPlayerBridge().getRemoteDelegate();
      final var reply =
          await(
              "move",
              new MoveRequest(
                  player.getName(),
                  combat,
                  movableUnits(player, data),
                  toUndoInfos(delegate.getMovesMade()),
                  error,
                  preview));
      error =
          null; // both are one-shot: only set again by this iteration's action, for the re-prompt
      preview = null;
      if (reply.has("done") && reply.get("done").getAsBoolean()) {
        if (keepMovingToSaveAir(delegate, player, data)) {
          continue; // player chose to keep moving rather than strand the aircraft — re-prompt
        }
        return; // browser ended the phase
      }
      if (reply.has("previewRoute") && reply.get("previewRoute").isJsonObject()) {
        // Compute (don't execute) the best legal route, then re-prompt with it for the browser to
        // highlight. Nothing changed on the board, so skip the state publish below.
        preview = previewMove(player, data, reply.getAsJsonObject("previewRoute"));
        continue;
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
   * Compute — without executing — the engine's best legal route for a source/destination/units the
   * browser asked to preview, via {@link MoveValidator#getBestRoute} (the same routing the Swing
   * client uses). Returns a {@link MovePreview} the browser highlights before committing; the
   * actual move is still fully validated by the move delegate on submit.
   */
  private MovePreview previewMove(
      final GamePlayer player, final GameData data, final JsonObject previewRoute) {
    final String from = previewRoute.has("from") ? previewRoute.get("from").getAsString() : null;
    final String to = previewRoute.has("to") ? previewRoute.get("to").getAsString() : null;
    final Territory start = from == null ? null : data.getMap().getTerritoryOrNull(from);
    final Territory end = to == null ? null : data.getMap().getTerritoryOrNull(to);
    if (start == null || end == null) {
      return new MovePreview(from, to, null, 0, List.of(), "Pick a source and a destination");
    }
    final List<Unit> units = selectUnits(start, player, readUnitCounts(previewRoute));
    if (units.isEmpty()) {
      return new MovePreview(from, to, null, 0, List.of(), "Select units to move");
    }
    final Optional<Route> route =
        MoveValidator.getBestRoute(
            start, end, data, player, units, !GameStepPropertiesHelper.isAirborneMove(data));
    if (route.isEmpty()) {
      return new MovePreview(from, to, null, 0, List.of(), "No legal route for these units");
    }
    final Route best = route.get();
    final List<String> names = best.getAllTerritories().stream().map(Territory::getName).toList();
    // Movement cost is per-unit (terrain-weighted). Flag any chosen type where no unit has the
    // movement left to make the whole trip; the engine does the exact per-unit check on submit.
    // Cargo aboard a transport rides along with it — its own (zero) movement doesn't gate the trip,
    // so it's excluded from both the cost and the "can't reach" check.
    final Map<String, List<Unit>> byType = new TreeMap<>();
    for (final Unit unit : units) {
      if (Matches.unitIsBeingTransported().test(unit)) {
        continue;
      }
      byType.computeIfAbsent(unit.getType().getName(), k -> new ArrayList<>()).add(unit);
    }
    final List<String> blocked = new ArrayList<>();
    double maxCost = 0;
    for (final var entry : byType.entrySet()) {
      final BigDecimal cost = best.getMovementCost(entry.getValue().get(0));
      maxCost = Math.max(maxCost, cost.doubleValue());
      final boolean anyCanReach =
          entry.getValue().stream().anyMatch(u -> u.getMovementLeft().compareTo(cost) >= 0);
      if (!anyCanReach) {
        blocked.add(entry.getKey());
      }
    }
    return new MovePreview(from, to, names, maxCost, blocked, null);
  }

  /**
   * When ending a move phase that removes stranded aircraft, mirror the Swing client's
   * air-can't-land warning ({@code TripleAPlayer.canAirLand}): if the player has air units that
   * can't reach friendly territory this turn — and so would be lost — ask the browser to confirm.
   * Returns true if the player chose to keep moving (the phase should NOT end yet); false when
   * there is nothing stranded or the player accepted the loss. The engine's {@code MoveValidator}
   * is what actually removes the air at the step's end; this is purely the heads-up.
   */
  private boolean keepMovingToSaveAir(
      final IMoveDelegate delegate, final GamePlayer player, final GameData data) {
    if (!GameStepPropertiesHelper.isRemoveAirThatCanNotLand(data)) {
      return false; // this step doesn't strand air (e.g. combat move) — nothing to warn about
    }
    final List<String> stranded =
        delegate.getTerritoriesWhereAirCantLand(player).stream()
            .map(Territory::getName)
            .sorted()
            .toList();
    if (stranded.isEmpty()) {
      return false; // all air can land — safe to end
    }
    final JsonObject reply = await("airWarning", new AirWarningRequest(player.getName(), stranded));
    // endAnyway=true → accept the loss and end the phase; anything else → keep moving.
    return !(reply.has("endAnyway") && reply.get("endAnyway").getAsBoolean());
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
      result.add(
          new UndoableMoveInfo(
              i, summarizeUnits(move.getUnits()), move.getMoveLabel(), move.getCanUndo()));
    }
    return result;
  }

  /** "2 infantry, 1 armour" — the moved units grouped by type, like the Swing undo panel. */
  static String summarizeUnits(final Collection<Unit> units) {
    final Map<String, Integer> byType = new TreeMap<>();
    for (final Unit unit : units) {
      byType.merge(unit.getType().getName(), 1, Integer::sum);
    }
    final StringBuilder summary = new StringBuilder();
    byType.forEach(
        (type, count) -> {
          if (summary.length() > 0) {
            summary.append(", ");
          }
          summary.append(count).append(' ').append(type);
        });
    return summary.toString();
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
    final Map<String, Integer> unitCounts = readUnitCounts(reply);
    final List<String> routeNames = new ArrayList<>();
    if (reply.has("from") && reply.has("to")) {
      // Endpoint move: let the engine find the best legal route from source to destination for the
      // chosen units (the click-destination flow), then move along it.
      final Territory start = data.getMap().getTerritoryOrNull(reply.get("from").getAsString());
      final Territory end = data.getMap().getTerritoryOrNull(reply.get("to").getAsString());
      if (start == null || end == null) {
        return "Unknown source or destination territory";
      }
      final List<Unit> units = selectUnits(start, player, unitCounts);
      if (units.isEmpty()) {
        return "No matching movable units in " + start.getName();
      }
      final Optional<Route> route =
          MoveValidator.getBestRoute(
              start, end, data, player, units, !GameStepPropertiesHelper.isAirborneMove(data));
      if (route.isEmpty()) {
        return "No legal route from " + start.getName() + " to " + end.getName();
      }
      route.get().getAllTerritories().forEach(t -> routeNames.add(t.getName()));
    } else if (reply.has("route") && reply.get("route").isJsonArray()) {
      // Explicit full route (kept for completeness; the browser uses the endpoint form above).
      for (final JsonElement element : reply.getAsJsonArray("route")) {
        routeNames.add(element.getAsString());
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

  /** Parse a {@code {units:{type:count}}} object from a reply (empty if absent). */
  private static Map<String, Integer> readUnitCounts(final JsonObject obj) {
    final Map<String, Integer> counts = new HashMap<>();
    if (obj.has("units") && obj.get("units").isJsonObject()) {
      for (final var entry : obj.getAsJsonObject("units").entrySet()) {
        counts.put(entry.getKey(), entry.getValue().getAsInt());
      }
    }
    return counts;
  }

  /**
   * The requested {@code type -> count} drawn from the player's movable units in {@code source}.
   */
  private static List<Unit> selectUnits(
      final Territory source, final GamePlayer player, final Map<String, Integer> unitCounts) {
    final List<Unit> movablePool =
        source.getUnitCollection().getUnits().stream().filter(movableMatch(player)).toList();
    return resolveByType(movablePool, unitCounts);
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

    final List<Unit> units = selectUnits(source, player, unitCounts);
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

  /**
   * Fight the player's pending battles. The engine does NOT auto-fight — the seat must drive the
   * combat phase (Swing's {@code TripleAPlayer} and the AI both do this). We mirror {@code
   * AbstractAi.battle}: loop the battle listing, calling {@link IBattleDelegate#fightBattle} for
   * each battle until none remain (retrying, since some battles depend on others — e.g. a sea
   * battle before an amphibious assault). Each fight resolves synchronously, prompting the browser
   * via {@link #selectCasualties}/{@link #retreatQuery} for this player's decisions. Without this
   * the attacker's combat moves never resolve and opposing units sit co-located in the contested
   * territory.
   */
  private void handleBattle() {
    final IBattleDelegate delegate = (IBattleDelegate) getPlayerBridge().getRemoteDelegate();
    while (!getPlayerBridge().isGameOver()) {
      final var battlesByType = delegate.getBattleListing().getBattlesMap();
      if (battlesByType.isEmpty()) {
        return;
      }
      boolean foughtOne = false;
      for (final var entry : battlesByType.entrySet()) {
        for (final Territory where : entry.getValue()) {
          if (getPlayerBridge().isGameOver()) {
            return;
          }
          final String error =
              delegate.fightBattle(where, entry.getKey().isBombingRun(), entry.getKey());
          if (error == null) {
            foughtOne = true;
          } else if (!BattleDelegate.isBattleDependencyErrorMessage(error)) {
            log.warn("Cannot fight battle at {}: {}", where.getName(), error);
          }
        }
      }
      if (!foughtOne) {
        return; // only blocked/dependency battles remain — stop rather than spin
      }
    }
  }

  /**
   * Place units bought this turn: loop offering the remaining pool to the browser, placing one
   * territory's worth at a time via {@link IAbstractPlaceDelegate#placeUnits}, until the browser
   * says done or the pool is empty. Mirrors {@code TripleAPlayer.place}. The engine validates each
   * placement (factory presence, production caps, sea-zone adjacency) and the rejection comes back
   * as {@code error}. Units left unplaced when the phase ends are lost (engine behavior).
   */
  private void handlePlace() {
    final GamePlayer player = getGamePlayer();
    final boolean bid = GameStepPropertiesHelper.isBid(getGameData());
    String error = null;
    while (!getPlayerBridge().isGameOver()) {
      final Collection<Unit> pool = player.getUnitCollection().getUnits();
      if (pool.isEmpty()) {
        return; // everything placed
      }
      final JsonObject reply =
          await("place", new PlaceRequest(player.getName(), bid, placePool(pool), error));
      if (reply.has("done") && reply.get("done").getAsBoolean()) {
        return; // browser ended the phase (any leftover units are lost)
      }
      error = submitPlace(player, bid, reply);
      if (error == null) {
        bridge.publishState(GSON.toJson(StateProjector.project(getGameData())));
      }
    }
  }

  /** Resolve the reply (territory + unit-type→count) and submit to the place delegate. */
  private @Nullable String submitPlace(
      final GamePlayer player, final boolean bid, final JsonObject reply) {
    if (!reply.has("territory") || !reply.get("territory").isJsonPrimitive()) {
      return "No territory selected";
    }
    final String name = reply.get("territory").getAsString();
    final Territory at = getGameData().getMap().getTerritoryOrNull(name);
    if (at == null) {
      return "Unknown territory: " + name;
    }
    final Map<String, Integer> counts = new HashMap<>();
    if (reply.has("units") && reply.get("units").isJsonObject()) {
      for (final var entry : reply.getAsJsonObject("units").entrySet()) {
        counts.put(entry.getKey(), entry.getValue().getAsInt());
      }
    }
    final List<Unit> units = resolveByType(player.getUnitCollection().getUnits(), counts);
    if (units.isEmpty()) {
      return "No units selected to place";
    }
    final IAbstractPlaceDelegate delegate =
        (IAbstractPlaceDelegate) getPlayerBridge().getRemoteDelegate();
    return delegate.placeUnits(units, at, bid ? BidMode.BID : BidMode.NOT_BID).orElse(null);
  }

  /** The player's not-yet-placed pool, grouped by type with air/sea flags. */
  private static List<PlaceUnit> placePool(final Collection<Unit> pool) {
    final Map<String, List<Unit>> byType = new TreeMap<>();
    for (final Unit unit : pool) {
      byType.computeIfAbsent(unit.getType().getName(), k -> new ArrayList<>()).add(unit);
    }
    final List<PlaceUnit> rows = new ArrayList<>();
    byType.forEach(
        (type, units) ->
            rows.add(
                new PlaceUnit(
                    type,
                    units.size(),
                    Matches.unitIsAir().test(units.get(0)),
                    Matches.unitIsSea().test(units.get(0)))));
    return rows;
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
    try {
      final JsonObject reply =
          await(
              "selectCasualties",
              new CasualtyRequest(
                  hit.getName(),
                  battlesite == null ? null : battlesite.getName(),
                  message,
                  count,
                  countByType(selectFrom),
                  countByType(defaultCasualties.getKilled()),
                  allowMultipleHitsPerUnit));
      final Map<String, Integer> killedCounts = new HashMap<>();
      if (reply.has("killed") && reply.get("killed").isJsonObject()) {
        for (final var entry : reply.getAsJsonObject("killed").entrySet()) {
          killedCounts.put(entry.getKey(), entry.getValue().getAsInt());
        }
      }
      return new CasualtyDetails(resolveByType(selectFrom, killedCounts), List.of(), false);
    } catch (final RuntimeException e) {
      // Game stopped, or no browser to answer — fall back to the engine's auto-pick rather than
      // crash the battle. (The browser panel enforces the exact hit count, so a real reply is
      // valid.)
      log.warn("Casualty selection unavailable, accepting engine default: {}", e.getMessage());
      return new CasualtyDetails(defaultCasualties, true);
    }
  }

  /** Resolve a {@code type -> count} selection into concrete, distinct units drawn from a pool. */
  static List<Unit> resolveByType(final Collection<Unit> pool, final Map<String, Integer> counts) {
    final List<Unit> chosen = new ArrayList<>();
    counts.forEach(
        (type, n) ->
            pool.stream()
                .filter(u -> u.getType().getName().equals(type))
                .filter(u -> !chosen.contains(u))
                .limit(Math.max(0, n))
                .forEach(chosen::add));
    return chosen;
  }

  /** Unit type -> count, for offering a pool to the browser. */
  private static Map<String, Integer> countByType(final Collection<Unit> units) {
    final Map<String, Integer> byType = new TreeMap<>();
    for (final Unit unit : units) {
      byType.merge(unit.getType().getName(), 1, Integer::sum);
    }
    return byType;
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
    try {
      String attackers = "";
      String defenders = "";
      final IBattle battle =
          AbstractMoveDelegate.getBattleTracker(getGameData()).getPendingBattle(battleId);
      if (battle != null) {
        attackers = summarizeUnits(battle.getAttackingUnits());
        defenders = summarizeUnits(battle.getDefendingUnits());
      }
      final List<String> options =
          possibleTerritories.stream().map(Territory::getName).sorted().toList();
      final JsonObject reply =
          await(
              "retreat",
              new RetreatRequest(
                  getGamePlayer().getName(),
                  battleTerritory.getName(),
                  submerge,
                  options,
                  message,
                  attackers,
                  defenders));
      if (reply.has("retreatTo") && reply.get("retreatTo").isJsonPrimitive()) {
        final String name = reply.get("retreatTo").getAsString();
        return possibleTerritories.stream().filter(t -> t.getName().equals(name)).findFirst();
      }
      return Optional.empty(); // remain and keep fighting
    } catch (final RuntimeException e) {
      log.warn("Retreat query unavailable, staying in battle: {}", e.getMessage());
      return Optional.empty();
    }
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
