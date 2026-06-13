package games.strategy.engine.data;

import games.strategy.engine.chat.Chat;
import games.strategy.engine.framework.AutoSaveFileUtils;
import games.strategy.engine.framework.IGame;
import games.strategy.engine.framework.LocalPlayers;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.WatcherThreadMessaging;
import games.strategy.engine.framework.startup.launcher.LaunchAction;
import games.strategy.engine.framework.startup.mc.IServerStartupRemote;
import games.strategy.engine.framework.startup.mc.ServerConnectionProps;
import games.strategy.engine.framework.startup.mc.ServerModel;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.framework.startup.ui.panels.main.game.selector.GameSelectorModel;
import games.strategy.engine.player.Player;
import games.strategy.net.Messengers;
import games.strategy.net.websocket.ClientNetworkBridge;
import games.strategy.triplea.ResourceLoader;
import games.strategy.triplea.ui.display.HeadlessDisplay;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.triplea.game.chat.ChatModel;
import org.triplea.sound.HeadlessSoundChannel;

/**
 * Minimal {@link LaunchAction} for the smoke-testing AI harness. Replaces the dependency on
 * game-headless's {@code HeadlessLaunchAction} (deleted in the server-only prune). Only {@link
 * #startGame} is exercised by {@link GameTestUtils}; map resources are always skipped (test context
 * never needs map images), and the networked launcher methods throw to fail fast if a test ever
 * routes through the unused networked flow.
 */
@Slf4j
public final class TestLaunchAction implements LaunchAction {

  @Override
  public void startGame(
      final LocalPlayers localPlayers,
      final IGame game,
      final Set<Player> players,
      final Chat chat) {
    // Test/AI play needs no map image resources.
    game.setResourceLoader(new ResourceLoader(List.of()));
    game.setDisplay(new HeadlessDisplay());
    game.setSoundChannel(new HeadlessSoundChannel());
  }

  @Override
  public Collection<PlayerTypes.Type> getPlayerTypes() {
    return PlayerTypes.getBuiltInPlayerTypes();
  }

  @Override
  public boolean shouldMinimizeExpensiveAiUse() {
    return true;
  }

  @Override
  public PlayerTypes.Type getDefaultLocalPlayerType() {
    return PlayerTypes.WEAK_AI;
  }

  @Override
  public void onLaunch(final ServerGame serverGame) {}

  @Override
  public void onEnd(final String message) {
    log.info(message);
  }

  @Override
  public void handleError(final String error) {
    log.error(error);
  }

  @Override
  public Path getAutoSaveFile() {
    return getAutoSaveFileUtils().getOddRoundAutoSaveFile();
  }

  @Override
  public AutoSaveFileUtils getAutoSaveFileUtils() {
    return new AutoSaveFileUtils();
  }

  // --- Networked-launcher methods: never invoked by the smoke-test in-process run path. ---

  @Override
  public void handleGameInterruption(
      final GameSelectorModel gameSelectorModel, final ServerModel serverModel) {
    throw unsupported();
  }

  @Override
  public void onGameInterrupt() {
    throw unsupported();
  }

  @Override
  public ChatModel createChatModel(
      final String chatName, final Messengers messengers, final ClientNetworkBridge bridge) {
    throw unsupported();
  }

  @Override
  public WatcherThreadMessaging createThreadMessaging() {
    throw unsupported();
  }

  @Override
  public Optional<ServerConnectionProps> getFallbackConnection(final Runnable cancelAction) {
    throw unsupported();
  }

  @Override
  public IServerStartupRemote getStartupRemote(
      final IServerStartupRemote.ServerModelView serverModelView) {
    throw unsupported();
  }

  @Override
  public boolean promptGameStop(final String status, final String title, final Path mapLocation) {
    // Reached on the in-process victory path (signalGameOver -> stopGameSequence); the harness
    // runs games to real victory, so the game must actually stop. Matches HeadlessLaunchAction.
    return true;
  }

  private static UnsupportedOperationException unsupported() {
    return new UnsupportedOperationException(
        "TestLaunchAction supports only direct in-process game runs (startGame).");
  }
}
