package games.strategy.engine.data;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;

import games.strategy.engine.framework.GameDataManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Deserialization tripwire for the web-port pruning work (Option A): loads real production saves
 * captured from the live server. If a class deleted from game-core was actually part of the
 * serialized GameData graph, this test fails before the change ships. See docs/web-port/CHARTER.md
 * and the prune/option-a plan.
 */
class ProdSaveCompatibilityTest {

  @ParameterizedTest
  @MethodSource
  void loadProdSaveGames(final Path saveGame) throws Exception {
    final GameData gameData;
    try (InputStream inputStream = Files.newInputStream(saveGame)) {
      gameData = GameDataManager.loadGame(inputStream).orElseThrow();
    }

    assertThat(gameData.getDelegates(), is(notNullValue()));
    assertThat(gameData.getHistory().getLastNode(), is(notNullValue()));
    assertThat(gameData.getMap().getTerritories(), is(notNullValue()));
    assertThat(gameData.getPlayerList().getPlayers(), is(notNullValue()));
    assertThat(gameData.getSequence().getStep(), is(notNullValue()));
    assertThat(gameData.getUnits().getUnits(), is(notNullValue()));
  }

  @SuppressWarnings("unused")
  static Collection<Path> loadProdSaveGames() throws IOException {
    return TestDataFileLister.listFilesInTestClasspathDir("prod-saves");
  }
}
