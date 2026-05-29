package org.triplea.web.server.game;

import java.util.List;

/**
 * One political action (e.g. a declaration of war) offered during the politics phase. {@code name}
 * is the engine action id sent back in the reply. {@code summary} is the concise headline shown on
 * the button (e.g. "Declare war on Americans, British") — just what the acting player does, not the
 * full ripple. {@code changes} are <i>all</i> relationship changes it applies, pre-rendered (e.g.
 * "Japanese → French: War"); the client shows these only on hover/detail, since the full picture
 * belongs in the Relationships grid. {@code costPu} is its PU cost (0 = free). {@code chance} is
 * the success odds — "auto" when it always succeeds, else "hit/sides" (e.g. "3/6").
 */
public record PoliticalActionOption(
    String name, String summary, List<String> changes, int costPu, String chance) {}
