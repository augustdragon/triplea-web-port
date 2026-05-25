package org.triplea.web.server.map;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Territory;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads territory ownership from {@link GameData}. Used both for the static first-render snapshot
 * and live during a running game (it reflects ownership at call time, so it updates as territories
 * change hands).
 */
public final class GameStateReader {
  private GameStateReader() {}

  /**
   * Returns territory name -> owning player name (the engine's neutral/null player for unowned), as
   * of the moment of the call.
   */
  public static Map<String, String> ownersByTerritory(final GameData gameData) {
    final Map<String, String> owners = new TreeMap<>();
    for (final Territory territory : gameData.getMap().getTerritories()) {
      owners.put(territory.getName(), territory.getOwner().getName());
    }
    return owners;
  }
}
