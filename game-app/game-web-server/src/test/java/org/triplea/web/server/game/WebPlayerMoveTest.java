package org.triplea.web.server.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.GameDataTestUtil;
import games.strategy.triplea.delegate.UndoableMove;
import games.strategy.triplea.delegate.move.validation.MoveValidator;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards {@link WebPlayer#buildMove} — the bridge logic that turns a browser's (route, unit-type →
 * count) reply into a {@link MoveDescription} the engine can validate. We assert the construction
 * (route shape, resolved units, transport-load mapping) and feed the result to the engine's own
 * {@link MoveValidator} to confirm legality, exactly as {@code MoveDelegate.performMove} does
 * internally.
 *
 * <p>Map geography is <b>discovered</b> from the graph (a real nation's land territory that has a
 * sea neighbor, a friendly land neighbor, and a sea-to-sea hop) so the test isn't pinned to one
 * map's territory names. The chosen sea zones are cleared of any starting ships so our placed units
 * move into open, enemy-free water. Air moves (same code path as land) and amphibious unload
 * (transports inferred by the engine, not by {@code buildMove}) are exercised live in the browser
 * rather than here.
 */
class WebPlayerMoveTest {
  private GameData data;
  private GamePlayer player;
  private Territory land; // owned by `player`, has a sea neighbor and a friendly land neighbor
  private Territory land2; // a friendly land neighbor of `land`
  private Territory sea; // a sea neighbor of `land` (cleared)
  private Territory sea2; // a sea neighbor of `sea` (cleared)

  @BeforeEach
  void setUp() {
    data = TestMapGameData.REVISED.getGameData();
    discoverGeography();
    clear(sea);
    clear(sea2);
  }

  /** Finds a nation's land territory wired to sea + friendly land + a sea-to-sea hop. */
  private void discoverGeography() {
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater() || t.getOwner().isNull()) {
        continue;
      }
      final GamePlayer owner = t.getOwner();
      final var seaNeighbors = data.getMap().getNeighbors(t, Territory::isWater);
      final var friendlyLand =
          data.getMap()
              .getNeighbors(t, n -> !n.isWater() && owner.equals(n.getOwner()) && !n.equals(t));
      if (seaNeighbors.isEmpty() || friendlyLand.isEmpty()) {
        continue;
      }
      for (final Territory candidateSea : seaNeighbors) {
        final var seaHops = data.getMap().getNeighbors(candidateSea, Territory::isWater);
        if (!seaHops.isEmpty()) {
          player = owner;
          land = t;
          land2 = friendlyLand.iterator().next();
          sea = candidateSea;
          sea2 = seaHops.iterator().next();
          return;
        }
      }
    }
    assertNotNull(player, "no suitable land/sea/land geography found in the test map");
  }

  private void clear(final Territory t) {
    GameDataTestUtil.removeFrom(t, List.copyOf(t.getUnits()));
  }

  private boolean validates(final boolean nonCombat, final MoveDescription move) {
    return new MoveValidator(data, nonCombat).validateMove(move, player).isMoveValid();
  }

  @Test
  void plainLandMove_resolvesUnitsAndValidates() {
    final UnitType infantry = GameDataTestUtil.infantry(data);
    GameDataTestUtil.addTo(land, infantry.create(2, player));

    final MoveDescription move =
        WebPlayer.buildMove(
            data, player, List.of(land.getName(), land2.getName()), Map.of(infantry.getName(), 2));

    assertEquals(land, move.getRoute().getStart());
    assertEquals(land2, move.getRoute().getEnd());
    assertEquals(2, move.getUnits().size());
    assertTrue(move.getUnitsToSeaTransports().isEmpty(), "a land move loads no transports");
    assertTrue(validates(false, move), "2 infantry into a friendly neighbor should be legal");
  }

  @Test
  void landToSea_buildsTransportLoadMapping_andValidates() {
    final UnitType infantry = GameDataTestUtil.infantry(data);
    final UnitType transport = GameDataTestUtil.transport(data);
    GameDataTestUtil.addTo(land, infantry.create(1, player));
    GameDataTestUtil.addTo(sea, transport.create(1, player));

    final MoveDescription move =
        WebPlayer.buildMove(
            data, player, List.of(land.getName(), sea.getName()), Map.of(infantry.getName(), 1));

    assertTrue(move.getRoute().isLoad(), "land→sea is a transport load");
    assertEquals(1, move.getUnitsToSeaTransports().size(), "the infantry maps to one transport");
    final Unit cargo = move.getUnits().iterator().next();
    assertTrue(move.getUnitsToSeaTransports().containsKey(cargo), "cargo keyed to its transport");
    assertTrue(validates(false, move), "loading into an empty sea zone should be legal");
  }

  @Test
  void landToSea_withoutTransport_isRejectedByValidator() {
    final UnitType infantry = GameDataTestUtil.infantry(data);
    GameDataTestUtil.addTo(land, infantry.create(1, player));

    final MoveDescription move =
        WebPlayer.buildMove(
            data, player, List.of(land.getName(), sea.getName()), Map.of(infantry.getName(), 1));

    assertTrue(move.getUnitsToSeaTransports().isEmpty(), "no transport in the sea zone to map to");
    assertFalse(validates(false, move), "infantry cannot enter open sea with no transport");
  }

  @Test
  void navalMove_betweenSeaZones_validates() {
    final UnitType destroyer = GameDataTestUtil.destroyer(data);
    GameDataTestUtil.addTo(sea, destroyer.create(1, player));

    final MoveDescription move =
        WebPlayer.buildMove(
            data, player, List.of(sea.getName(), sea2.getName()), Map.of(destroyer.getName(), 1));

    assertEquals(sea, move.getRoute().getStart());
    assertTrue(move.getUnitsToSeaTransports().isEmpty(), "a pure sea move loads no transports");
    assertTrue(validates(false, move), "a destroyer should reach an adjacent sea zone");
  }

  @Test
  void unknownTerritory_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> WebPlayer.buildMove(data, player, List.of("Nowhere", land.getName()), Map.of()));
  }

  @Test
  void routeWithoutDestination_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> WebPlayer.buildMove(data, player, List.of(land.getName()), Map.of()));
  }

  @Test
  void toUndoInfos_mapsIndexUnitsLabelAndCanUndo() {
    final UnitType infantry = GameDataTestUtil.infantry(data);
    final UnitType armour = GameDataTestUtil.armour(data);
    final List<Unit> mixed = new java.util.ArrayList<>(infantry.create(2, player));
    mixed.addAll(armour.create(1, player));
    final UndoableMove first = new UndoableMove(mixed, new Route(land, land2));
    final UndoableMove second =
        new UndoableMove(infantry.create(1, player), new Route(land2, land));
    second.setCantUndo("a later move depends on this one");

    final List<UndoableMoveInfo> infos = WebPlayer.toUndoInfos(List.of(first, second));

    assertEquals(2, infos.size());
    assertEquals(0, infos.get(0).index());
    assertEquals("1 armour, 2 infantry", infos.get(0).units(), "grouped by type, alphabetical");
    assertEquals(land.getName() + " -> " + land2.getName(), infos.get(0).label());
    assertTrue(infos.get(0).canUndo());
    assertEquals(1, infos.get(1).index());
    assertEquals("1 infantry", infos.get(1).units());
    assertFalse(infos.get(1).canUndo(), "setCantUndo should disable undo");
  }

  @Test
  void resolveKilled_picksRequestedCountsPerTypeAsDistinctUnits() {
    final UnitType infantry = GameDataTestUtil.infantry(data);
    final UnitType armour = GameDataTestUtil.armour(data);
    final List<Unit> pool = new java.util.ArrayList<>(infantry.create(3, player));
    pool.addAll(armour.create(2, player));

    final List<Unit> killed =
        WebPlayer.resolveKilled(pool, Map.of(infantry.getName(), 2, armour.getName(), 1));

    assertEquals(3, killed.size());
    assertEquals(3, new java.util.HashSet<>(killed).size(), "all distinct units");
    assertEquals(
        2, killed.stream().filter(u -> u.getType().getName().equals(infantry.getName())).count());
    assertEquals(
        1, killed.stream().filter(u -> u.getType().getName().equals(armour.getName())).count());

    // Asking for more than available is capped to the pool.
    assertEquals(3, WebPlayer.resolveKilled(pool, Map.of(infantry.getName(), 99)).size());
  }

  @Test
  void noMatchingUnits_throws() {
    final UnitType infantry = GameDataTestUtil.infantry(data);
    // `sea` was cleared, so it holds no infantry to draw from.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WebPlayer.buildMove(
                data,
                player,
                List.of(sea.getName(), sea2.getName()),
                Map.of(infantry.getName(), 1)));
  }
}
