package org.triplea.web.server.game;

import java.util.Map;
import javax.annotation.Nullable;

/**
 * A point-in-time projection of dynamic game state for the web client: the current round, the
 * active step and player, and territory ownership. Pushed over WebSocket after each engine step.
 */
public record StateSnapshot(
    int round, String step, @Nullable String currentPlayer, Map<String, String> owners) {}
