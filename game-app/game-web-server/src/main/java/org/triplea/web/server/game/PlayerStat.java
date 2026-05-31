package org.triplea.web.server.game;

import java.util.List;

/**
 * Per-player summary stats for the client's Players/Resources info tabs — the web analog of the
 * base game's {@code StatPanel} columns. Computed read-only by {@link PlayerStatsProjector} and
 * carried in each {@link StateSnapshot}, so the tables update live as the game advances.
 *
 * @param player the power's name.
 * @param alliances the alliances this power belongs to (drives the client's alliance total rows).
 * @param passive the engine's "optional" player flag — the passive minor powers (e.g. Pacific's
 *     Russians/French/Dutch) that never produce/move/fight. The client collapses these into a single
 *     "Other" group rather than giving each its own alliance section.
 * @param pus current PUs on hand.
 * @param production raw territory production × the map's PU multiplier (StatPanel "Production").
 * @param units total units owned across the map (all units; no map-art draw filter, which we lack
 *     headless).
 * @param tuv total unit value of those units (TuvCostsCalculator costs).
 * @param victoryCities victory cities in owned territories.
 * @param resources every (non-VP) resource's amount + estimated income, for the Resources tab. PUs
 *     also appears here; {@link #pus} is the same value kept as a typed field for the Players
 *     table.
 */
public record PlayerStat(
    String player,
    List<String> alliances,
    boolean passive,
    int pus,
    int production,
    int units,
    int tuv,
    int victoryCities,
    List<ResourceCell> resources) {}
