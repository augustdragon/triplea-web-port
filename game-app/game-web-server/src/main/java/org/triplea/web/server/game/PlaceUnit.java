package org.triplea.web.server.game;

/**
 * One bought-but-unplaced unit type in the player's pool, with air/sea flags so the browser can
 * label it (sea units go in sea zones by a factory, land/air in land territories). No movement here
 * — these units aren't on the board yet. The engine validates the actual placement.
 */
public record PlaceUnit(String type, int count, boolean air, boolean sea) {}
