package org.triplea.web.server.map;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Territory;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads a snapshot of dynamic game state from parsed {@link GameData} for the static first render.
 * For now this is just initial territory ownership; in Phase 2 a live {@code StateProjector} pushed
 * over WebSocket supersedes this static read.
 */
public final class GameStateReader {
  private GameStateReader() {}

  /**
   * Returns territory name -> owning player name (the engine's neutral/null player for unowned).
   */
  public static Map<String, String> initialOwners(final GameData gameData) {
    final Map<String, String> owners = new TreeMap<>();
    for (final Territory territory : gameData.getMap().getTerritories()) {
      owners.put(territory.getName(), territory.getOwner().getName());
    }
    return owners;
  }
}
