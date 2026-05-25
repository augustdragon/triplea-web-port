package org.triplea.web.server.map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

class GameStateReaderTest {
  @Test
  void readsOwnerForEveryTerritory() {
    final GameData gameData = TestMapGameData.REVISED.getGameData();

    final Map<String, String> owners = GameStateReader.ownersByTerritory(gameData);

    assertThat(owners, aMapWithSize(greaterThan(50)));
    // Every territory resolves to some owner name (a real player or the engine's neutral player).
    assertThat(owners.values(), everyItem(not(Matchers.blankOrNullString())));
  }
}
