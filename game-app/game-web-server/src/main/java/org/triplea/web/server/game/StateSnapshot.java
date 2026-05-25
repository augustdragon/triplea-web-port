package org.triplea.web.server.game;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A point-in-time projection of dynamic game state for the web client: the current round, the
 * active step and player, territory ownership, and the units in each territory. Pushed over
 * WebSocket after each engine step. {@code units} maps a territory name to its stacks (owner + unit
 * type + count); only territories holding units appear, to keep the snapshot small.
 */
public record StateSnapshot(
    int round,
    String step,
    @Nullable String currentPlayer,
    Map<String, String> owners,
    Map<String, List<UnitStack>> units) {}
