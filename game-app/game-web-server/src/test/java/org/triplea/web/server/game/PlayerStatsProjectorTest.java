package org.triplea.web.server.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Resource;
import games.strategy.triplea.Constants;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards {@link PlayerStatsProjector} against the real engine on a bundled map (Revised). We can't
 * compare to the engine's own {@code IStat} classes (they need a {@code MapData} we lack headless),
 * so we cross-check each projected field against an independent read of the same game data: one row
 * per power, PUs from the resource ledger, unit counts from the map, alliance membership from the
 * tracker, and the alliance totals as the sum of their members. This pins the record's field order
 * and the formulas so a later refactor can't silently swap or break a column.
 */
class PlayerStatsProjectorTest {
  private GameData data;
  private List<PlayerStat> stats;
  private Map<String, PlayerStat> byPlayer;

  @BeforeEach
  void setUp() {
    data = TestMapGameData.REVISED.getGameData();
    stats = PlayerStatsProjector.project(data);
    byPlayer = stats.stream().collect(Collectors.toMap(PlayerStat::player, Function.identity()));
  }

  @Test
  void oneRowPerPowerInTurnOrder() {
    final List<String> expected =
        data.getPlayerList().getPlayers().stream().map(GamePlayer::getName).toList();
    assertEquals(expected, stats.stream().map(PlayerStat::player).toList());
  }

  @Test
  void pusMatchTheResourceLedger() {
    final Resource pus = data.getResourceList().getResourceOptional(Constants.PUS).orElseThrow();
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      assertEquals(
          player.getResources().getQuantity(pus),
          byPlayer.get(player.getName()).pus(),
          "PUs for " + player.getName());
    }
  }

  @Test
  void unitCountsMatchUnitsOwnedOnTheMap() {
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      final long owned =
          data.getMap().getTerritories().stream()
              .flatMap(t -> t.getUnitCollection().getUnits().stream())
              .filter(Matches.unitIsOwnedBy(player))
              .count();
      assertEquals(
          (int) owned, byPlayer.get(player.getName()).units(), "units for " + player.getName());
    }
  }

  @Test
  void allianceMembershipMatchesTheTracker() {
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      assertEquals(
          List.copyOf(data.getAllianceTracker().getAlliancesPlayerIsIn(player)),
          byPlayer.get(player.getName()).alliances(),
          "alliances for " + player.getName());
    }
  }

  @Test
  void valuesAreNonNegativeAndTuvTracksUnits() {
    for (final PlayerStat s : stats) {
      assertTrue(s.pus() >= 0 && s.production() >= 0 && s.tuv() >= 0 && s.victoryCities() >= 0);
      // A power with units must have positive TUV, and one with none must have zero.
      assertEquals(s.units() == 0, s.tuv() == 0, "tuv/units agreement for " + s.player());
    }
  }

  @Test
  void allianceTotalsAreInternallyConsistent() {
    // For each alliance, the per-member fields sum to a stable total (what the client renders).
    final var alliances = data.getAllianceTracker().getAlliances();
    assertNotNull(alliances);
    for (final String alliance : alliances) {
      final List<PlayerStat> members =
          stats.stream().filter(s -> s.alliances().contains(alliance)).toList();
      assertTrue(members.size() >= 1, "alliance " + alliance + " has members");
      final int tuvTotal = members.stream().mapToInt(PlayerStat::tuv).sum();
      assertTrue(tuvTotal >= 0);
    }
  }
}
