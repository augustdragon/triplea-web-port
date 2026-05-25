package org.triplea.web.server.game;

import games.strategy.engine.data.GameData;
import games.strategy.engine.display.IDisplay;
import games.strategy.engine.framework.GameRunner;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.player.Player;
import games.strategy.net.INode;
import games.strategy.net.LocalNoOpMessenger;
import games.strategy.net.Messengers;
import games.strategy.net.websocket.ClientNetworkBridge;
import games.strategy.triplea.settings.ClientSetting;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.web.server.map.GameDataLoader;

/**
 * Boots and steps a game in-process for the web server, with no UI and no real networking. This is
 * the engine-host seam: the same construction the headless smoke test uses ({@code GameTestUtils}),
 * adapted to main code via {@link WebLaunchAction} so it needs neither Mockito nor a {@code
 * HeadlessGameServer}.
 */
public final class WebGameHost {
  private static volatile boolean engineInitialized = false;

  private WebGameHost() {}

  /** One-time engine setup (idempotent): headless mode, client settings, no AI pauses. */
  public static synchronized void initEngine() {
    if (engineInitialized) {
      return;
    }
    System.setProperty(GameRunner.TRIPLEA_HEADLESS, "true");
    // In-memory preferences isolate the server from the user's real TripleA settings (neither
    // reading GUI prefs nor persisting our changes back to disk).
    ClientSetting.setPreferences(new MemoryPreferences());
    ClientSetting.aiMovePauseDuration.setValue(0);
    ClientSetting.aiCombatStepPauseDuration.setValue(0);
    // The engine auto-saves at round boundaries; redirect those writes to a throwaway temp folder.
    try {
      final Path saveRoot = Files.createTempDirectory("triplea-web-server");
      Files.createDirectories(saveRoot.resolve("autoSave"));
      ClientSetting.saveGamesFolderPath.setValue(saveRoot);
    } catch (final IOException e) {
      throw new UncheckedIOException("Could not create temp save folder", e);
    }
    engineInitialized = true;
  }

  /**
   * Parses {@code gameXml}, assigns every player the given AI type, and starts a ready-to-step
   * {@link ServerGame} that reports to {@code display}.
   */
  public static ServerGame startAiGame(
      final Path gameXml, final PlayerTypes.Type aiType, final IDisplay display) {
    initEngine();
    final GameData gameData = GameDataLoader.load(gameXml);

    final Map<String, PlayerTypes.Type> playerTypes = new HashMap<>();
    for (final var player : gameData.getPlayerList().getPlayers()) {
      playerTypes.put(player.getName(), aiType);
    }
    return launch(gameData, gameData.getGameLoader().newPlayers(playerTypes), display);
  }

  /**
   * Like {@link #startAiGame} but the players named in {@code humanPlayers} get a browser-driven
   * {@link WebPlayer} (blocking on {@code bridge}); everyone else gets {@code aiType}. The mixed
   * {@link Player} set is hand-built and passed straight to the engine — {@code PlayerTypes.Type}
   * is an open class and neither {@code ServerGame} nor {@code startGame} validates player
   * provenance, so this needs no engine change.
   */
  public static ServerGame startGame(
      final Path gameXml,
      final Set<String> humanPlayers,
      final PlayerTypes.Type aiType,
      final WebDecisionBridge bridge,
      final IDisplay display) {
    initEngine();
    final GameData gameData = GameDataLoader.load(gameXml);

    final Set<Player> players = new HashSet<>();
    for (final var player : gameData.getPlayerList().getPlayers()) {
      final String name = player.getName();
      players.add(
          humanPlayers.contains(name)
              ? new WebPlayer(name, "Web", bridge)
              : aiType.newPlayerWithName(name));
    }
    return launch(gameData, players, display);
  }

  /** Shared engine-host construction: no UI, no real networking, ready to step. */
  private static ServerGame launch(
      final GameData gameData, final Set<Player> players, final IDisplay display) {
    final WebLaunchAction launchAction = new WebLaunchAction(display);
    final Messengers messengers = new Messengers(new LocalNoOpMessenger());
    final ServerGame game =
        new ServerGame(
            gameData,
            players,
            new HashMap<String, INode>(),
            messengers,
            ClientNetworkBridge.NO_OP_SENDER,
            launchAction);
    game.setDelegateAutosavesEnabled(false);
    gameData.getGameLoader().startGame(game, players, launchAction, null);
    return game;
  }
}
