package org.triplea.web.server.map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MapConnectionsTest {
  @Test
  void extractsAdjacencyGraphFromRealMap() {
    final GameData gameData = TestMapGameData.REVISED.getGameData();

    final Map<String, List<String>> connections = MapConnections.from(gameData);

    // One entry per territory, and every territory has at least one neighbor on a connected map.
    assertThat(connections, aMapWithSize(greaterThan(50)));
    assertThat(connections.values(), everyItem(not(org.hamcrest.Matchers.empty())));

    // Adjacency is symmetric: if A lists B as a neighbor, B lists A.
    final var firstEntry = connections.entrySet().iterator().next();
    final String territory = firstEntry.getKey();
    final String neighbor = firstEntry.getValue().get(0);
    assertThat(connections, hasKey(neighbor));
    assertThat(
        territory + " should be a neighbor of " + neighbor,
        connections.get(neighbor),
        hasItem(territory));
  }
}
