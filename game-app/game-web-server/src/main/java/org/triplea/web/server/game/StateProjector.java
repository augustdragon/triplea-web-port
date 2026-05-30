package org.triplea.web.server.game;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.RelationshipTracker;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.triplea.web.server.map.GameStateReader;

/**
 * Projects the engine's {@link GameData} into a {@link StateSnapshot} for the web client. This is
 * the spectator/state projection (round/step/owners/units); Phase 3 grows it as the client needs
 * more (resources, battle state). Read-only: it reflects state at the moment of the call and is
 * invoked on the game-loop thread between steps.
 */
public final class StateProjector {
  private StateProjector() {}

  public static StateSnapshot project(final GameData data) {
    final var sequence = data.getSequence();
    final var step = sequence.getStep();
    final var player = step.getPlayerId();
    final List<GamePlayer> players = data.getPlayerList().getPlayers();
    return new StateSnapshot(
        sequence.getRound(),
        step.getName(),
        player == null ? null : player.getName(),
        GameStateReader.ownersByTerritory(data),
        unitsByTerritory(data),
        players.stream().map(GamePlayer::getName).toList(),
        relationships(data, players),
        PlayerStatsProjector.project(data));
  }

  /**
   * The full relationship matrix: {@code relationships[a][b]} is how player {@code a} relates to
   * {@code b} (symmetric, so both directions are filled; a player's relationship to itself is
   * omitted). Read straight from the engine's {@link RelationshipTracker}; the category collapses
   * the (map-specific) type name to war/allied/neutral so the client can color cells generically.
   */
  private static Map<String, Map<String, RelationshipCell>> relationships(
      final GameData data, final List<GamePlayer> players) {
    final RelationshipTracker tracker = data.getRelationshipTracker();
    final Map<String, Map<String, RelationshipCell>> matrix = new LinkedHashMap<>();
    for (final GamePlayer a : players) {
      final Map<String, RelationshipCell> row = new LinkedHashMap<>();
      for (final GamePlayer b : players) {
        if (a.equals(b)) {
          continue;
        }
        final String type = tracker.getRelationshipType(a, b).getName();
        final String category =
            tracker.isAtWar(a, b) ? "war" : tracker.isAllied(a, b) ? "allied" : "neutral";
        row.put(b.getName(), new RelationshipCell(type, category));
      }
      matrix.put(a.getName(), row);
    }
    return matrix;
  }

  /**
   * Territory name -> the unit stacks it holds, as of the call. Stacks are grouped by owner then
   * unit type (both sorted by name for stable output). Empty territories are omitted to keep the
   * snapshot small.
   */
  private static Map<String, List<UnitStack>> unitsByTerritory(final GameData data) {
    final Map<String, List<UnitStack>> result = new TreeMap<>();
    for (final Territory territory : data.getMap().getTerritories()) {
      final Collection<Unit> units = territory.getUnitCollection().getUnits();
      if (units.isEmpty()) {
        continue;
      }
      // owner name -> (unit type name -> count)
      final Map<String, Map<String, Integer>> byOwner = new TreeMap<>();
      for (final Unit unit : units) {
        byOwner
            .computeIfAbsent(unit.getOwner().getName(), owner -> new TreeMap<>())
            .merge(unit.getType().getName(), 1, Integer::sum);
      }
      final List<UnitStack> stacks = new ArrayList<>();
      byOwner.forEach(
          (owner, types) ->
              types.forEach((type, count) -> stacks.add(new UnitStack(owner, type, count))));
      result.put(territory.getName(), stacks);
    }
    return result;
  }
}
