package org.triplea.web.server.game;

/**
 * One national objective for the client's Objectives tab: the section it belongs to (a nation or
 * grouping), the human-readable HTML description, and whether its condition is currently satisfied.
 * Mirrors a row of the base game's {@code ObjectivePanel}. Built by {@link ObjectivesProjector}.
 *
 * @param section the grouping header (e.g. "Japanese", "Americans").
 * @param text the objective description (author HTML from objectives.properties).
 * @param satisfied whether the objective's condition currently holds.
 */
public record ObjectiveItem(String section, String text, boolean satisfied) {}
