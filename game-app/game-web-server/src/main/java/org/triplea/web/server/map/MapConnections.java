package org.triplea.web.server.map;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Territory;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Extracts the territory adjacency graph from parsed {@link GameData}. Connections are semantic
 * data from the game XML (not the geometry files), so they come from the engine's map model. The
 * result is a per-territory map of sorted neighbor names — the form the web client uses for
 * highlighting and move hints.
 */
public final class MapConnections {
  private MapConnections() {}

  /** Returns territory name -> sorted list of adjacent territory names. */
  public static Map<String, List<String>> from(final GameData gameData) {
    final var map = gameData.getMap();
    final Map<String, List<String>> connections = new TreeMap<>();
    for (final Territory territory : map.getTerritories()) {
      connections.put(
          territory.getName(),
          map.getNeighbors(territory).stream()
              .map(Territory::getName)
              .sorted()
              .collect(Collectors.toList()));
    }
    return connections;
  }
}
