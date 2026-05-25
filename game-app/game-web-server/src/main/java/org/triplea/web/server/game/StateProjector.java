package org.triplea.web.server.game;

import games.strategy.engine.data.GameData;
import org.triplea.web.server.map.GameStateReader;

/**
 * Projects the engine's {@link GameData} into a {@link StateSnapshot} for the web client. This is
 * the Phase 2 spectator projection (round/step/owners); Phase 3 grows it as the client needs more
 * (units per territory, resources, battle state).
 */
public final class StateProjector {
  private StateProjector() {}

  public static StateSnapshot project(final GameData data) {
    final var sequence = data.getSequence();
    final var step = sequence.getStep();
    final var player = step.getPlayerId();
    return new StateSnapshot(
        sequence.getRound(),
        step.getName(),
        player == null ? null : player.getName(),
        GameStateReader.ownersByTerritory(data));
  }
}
