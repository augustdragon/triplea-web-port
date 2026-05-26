package org.triplea.web.server.game;

import java.util.List;
import javax.annotation.Nullable;

/**
 * The payload of a {@code kind:"place"} decision request: the player places units bought this turn.
 * {@code toPlace} is the remaining pool (unit type → count, with air/sea flags). {@code error}
 * carries back a delegate rejection of a prior placement (e.g. no factory, over the production
 * cap). The browser replies {@code {territory:"<name>", units:{type:count}}} to place into a
 * territory (the engine validates), or {@code {done:true}} to end the phase (any unplaced units are
 * lost).
 */
public record PlaceRequest(
    String player, boolean bid, List<PlaceUnit> toPlace, @Nullable String error) {}
