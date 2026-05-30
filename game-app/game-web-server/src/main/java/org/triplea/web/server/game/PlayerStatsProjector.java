package org.triplea.web.server.game;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Constants;
import games.strategy.triplea.Properties;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.AbstractEndTurnDelegate;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.triplea.java.collections.IntegerMap;

/**
 * Computes per-player {@link PlayerStat}s from the engine's {@link GameData}, mirroring the base
 * game's {@code StatPanel}/{@code EconomyPanel} columns. The engine's own {@code IStat} classes
 * ({@code TuvStat}/{@code UnitsStat}) require a {@code MapData} (for {@code shouldDrawUnit}), which
 * we don't have running headless, so the few formulas are reimplemented here — counting all units
 * rather than just the drawable ones (the only behavioral difference, and immaterial for these
 * totals). Read-only; called on the game-loop thread as part of {@link StateProjector}.
 */
public final class PlayerStatsProjector {
  private PlayerStatsProjector() {}

  public static List<PlayerStat> project(final GameData data) {
    final Resource pus = data.getResourceList().getResourceOptional(Constants.PUS).orElse(null);
    final int puMultiplier = Properties.getPuMultiplier(data.getProperties());
    final TuvCostsCalculator tuvCalculator = new TuvCostsCalculator();

    final List<PlayerStat> stats = new ArrayList<>();
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      stats.add(
          new PlayerStat(
              player.getName(),
              List.copyOf(data.getAllianceTracker().getAlliancesPlayerIsIn(player)),
              pus == null ? 0 : player.getResources().getQuantity(pus),
              production(data, player, puMultiplier),
              unitCount(data, player),
              tuv(data, player, tuvCalculator),
              victoryCities(data, player),
              income(data, player, pus)));
    }
    return stats;
  }

  /** Raw production of income-collecting owned territories × the PU multiplier (StatPanel). */
  private static int production(
      final GameData data, final GamePlayer player, final int multiplier) {
    final int raw =
        data.getMap().getTerritories().stream()
            .filter(Matches.isTerritoryOwnedBy(player))
            .filter(Matches.territoryCanCollectIncomeFrom(player))
            .mapToInt(TerritoryAttachment::getProduction)
            .sum();
    return raw * multiplier;
  }

  private static int unitCount(final GameData data, final GamePlayer player) {
    final Predicate<Unit> owned = Matches.unitIsOwnedBy(player);
    return (int)
        data.getMap().getTerritories().stream()
            .flatMap(t -> t.getUnitCollection().getUnits().stream())
            .filter(owned)
            .count();
  }

  private static int tuv(
      final GameData data, final GamePlayer player, final TuvCostsCalculator tuvCalculator) {
    final IntegerMap<UnitType> costs = tuvCalculator.getCostsForTuv(player);
    final Predicate<Unit> owned = Matches.unitIsOwnedBy(player);
    return data.getMap().getTerritories().stream()
        .flatMap(t -> t.getUnitCollection().getUnits().stream())
        .filter(owned)
        .mapToInt(unit -> costs.getInt(unit.getType()))
        .sum();
  }

  private static int victoryCities(final GameData data, final GamePlayer player) {
    return data.getMap().getTerritories().stream()
        .filter(Matches.isTerritoryOwnedBy(player))
        .map(TerritoryAttachment::get)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .mapToInt(TerritoryAttachment::getVictoryCity)
        .sum();
  }

  private static int income(final GameData data, final GamePlayer player, final Resource pus) {
    return pus == null ? 0 : AbstractEndTurnDelegate.findEstimatedIncome(player, data).getInt(pus);
  }
}
