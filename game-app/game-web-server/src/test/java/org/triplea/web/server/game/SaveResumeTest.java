package org.triplea.web.server.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.engine.data.GameData;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.player.Player;
import games.strategy.triplea.ui.display.HeadlessDisplay;
import games.strategy.triplea.xml.TestMapGameData;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Proves the engine's save/resume works through our launch path with no engine changes: a running
 * game serialized with {@code GameData.toBytes()} reloads at the same round/step (carrying the
 * resume flag {@code GAME_HAS_BEEN_SAVED_PROPERTY}) and continues stepping when relaunched. This is
 * the foundation of "play across days" — the same bytes the autosave writes and resume reads.
 */
class SaveResumeTest {
  private static Map<String, PlayerTypes.Type> allFastAi(final GameData data) {
    final Map<String, PlayerTypes.Type> types = new HashMap<>();
    data.getPlayerList().getPlayers().forEach(p -> types.put(p.getName(), PlayerTypes.FAST_AI));
    return types;
  }

  /**
   * Build a runnable game from data + all-AI players, then run setup (resume for a loaded game).
   */
  private static ServerGame launchAndInit(final GameData data) {
    final Set<Player> players = data.getGameLoader().newPlayers(allFastAi(data));
    final ServerGame game = WebGameHost.launch(data, players, new HeadlessDisplay());
    game.setUpGameForRunningSteps();
    return game;
  }

  @Test
  void savedGameReloadsAtSameStepAndResumes() {
    WebGameHost.initEngine();
    final ServerGame game = launchAndInit(TestMapGameData.REVISED.getGameData());
    for (int i = 0; i < 6; i++) {
      game.runNextStep();
    }
    final int savedRound = game.getData().getSequence().getRound();
    final String savedStep = game.getData().getSequence().getStep().getDisplayName();

    // Save -> reload through the engine's serializer (exactly what autosave/resume do).
    final byte[] bytes = game.getData().toBytes();
    final GameData loaded = GameDataManager.loadGame(new ByteArrayInputStream(bytes)).orElseThrow();
    assertEquals(savedRound, loaded.getSequence().getRound(), "round preserved across save/load");
    assertEquals(
        savedStep,
        loaded.getSequence().getStep().getDisplayName(),
        "current step preserved across save/load");
    assertTrue(
        loaded.getProperties().get(ServerGame.GAME_HAS_BEEN_SAVED_PROPERTY, false),
        "the save carries the resume flag, so the engine resumes rather than restarting");

    // Relaunch from the loaded data; it must continue, not restart at round 1.
    final ServerGame resumed = launchAndInit(loaded);
    resumed.runNextStep();
    assertTrue(
        resumed.getData().getSequence().getRound() >= savedRound,
        "resumed game continues from the saved point");
  }
}
