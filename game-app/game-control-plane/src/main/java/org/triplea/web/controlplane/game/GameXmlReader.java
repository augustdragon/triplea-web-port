package org.triplea.web.controlplane.game;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.framework.GameRunner;
import games.strategy.triplea.settings.ClientSetting;
import java.nio.file.Path;
import java.util.List;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Parses a map's game XML with the engine's own {@link GameParser} to read its playable powers,
 * reusing the exact rules logic the game container's setup phase uses (so the lobby and the game
 * agree on which nations are selectable). The control plane only parses — it never launches a game
 * — so this does not engage the engine's process-global game state. {@code MemoryPreferences} keeps
 * engine settings off disk and away from any real TripleA install.
 */
public final class GameXmlReader {

  private static volatile boolean engineInitialized = false;

  private GameXmlReader() {}

  private static synchronized void initEngine() {
    if (engineInitialized) {
      return;
    }
    System.setProperty(GameRunner.TRIPLEA_HEADLESS, "true");
    ClientSetting.setPreferences(new MemoryPreferences());
    engineInitialized = true;
  }

  /** Parse the game XML into engine {@link GameData}; throws if it cannot be parsed. */
  public static GameData parse(final Path xml) {
    initEngine();
    return GameParser.parse(xml, false)
        .orElseThrow(() -> new IllegalStateException("Failed to parse game XML: " + xml));
  }

  /**
   * The selectable nations, in engine turn order. Optional/inert minors (which never
   * produce/move/fight) are excluded, mirroring the game container's seat picker.
   */
  public static List<String> playablePowers(final GameData data) {
    return data.getPlayerList().getPlayers().stream()
        .filter(player -> !player.getOptional())
        .map(GamePlayer::getName)
        .toList();
  }
}
