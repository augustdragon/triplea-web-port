package org.triplea.web.server.game;

import java.util.List;

/**
 * Per-player summary stats for the client's Players/Resources info tabs — the web analog of the
 * base game's {@code StatPanel} columns. Computed read-only by {@link PlayerStatsProjector} and
 * carried in each {@link StateSnapshot}, so the tables update live as the game advances.
 *
 * @param player the power's name.
 * @param alliances the alliances this power belongs to (drives the client's alliance total rows).
 * @param pus current PUs on hand.
 * @param production raw territory production × the map's PU multiplier (StatPanel "Production").
 * @param units total units owned across the map (all units; no map-art draw filter, which we lack
 *     headless).
 * @param tuv total unit value of those units (TuvCostsCalculator costs).
 * @param victoryCities victory cities in owned territories.
 * @param income estimated PU income next end-of-turn (drives the Resources tab's {@code +N} delta).
 */
public record PlayerStat(
    String player,
    List<String> alliances,
    int pus,
    int production,
    int units,
    int tuv,
    int victoryCities,
    int income) {}
