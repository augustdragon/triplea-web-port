package org.triplea.web.server.game;

import games.strategy.engine.chat.Chat;
import games.strategy.engine.display.IDisplay;
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
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.triplea.game.chat.ChatModel;
import org.triplea.sound.HeadlessSoundChannel;

/**
 * Minimal {@link LaunchAction} for running a game in-process inside the web server. Only {@link
 * #startGame} is exercised by the direct game-loader path ({@code TripleA#startGame}); it wires the
 * engine to the supplied {@link IDisplay} (the seam where {@code WebDisplay} plugs in). The
 * lobby/network methods belong to the networked {@code ServerLauncher} flow that this path never
 * uses, so they throw to fail fast if that assumption ever changes.
 */
@Slf4j
public final class WebLaunchAction implements LaunchAction {
  private final IDisplay display;

  public WebLaunchAction(final IDisplay display) {
    this.display = display;
  }

  @Override
  public void startGame(
      final LocalPlayers localPlayers,
      final IGame game,
      final Set<Player> players,
      final Chat chat) {
    // Headless/AI play needs no map image resources.
    game.setResourceLoader(new ResourceLoader(List.of()));
    game.setDisplay(display);
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
    return PlayerTypes.FAST_AI;
  }

  @Override
  public void onLaunch(final ServerGame serverGame) {
    // The web server owns the ServerGame instance directly; nothing to register here.
  }

  @Override
  public void onEnd(final String message) {
    log.info(message);
  }

  @Override
  public void handleError(final String error) {
    log.error(error);
  }

  // --- Networked-launcher methods: never invoked by the direct in-process run path. ---

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
  public Path getAutoSaveFile() {
    // Called at game end; the engine also auto-saves at round boundaries via getAutoSaveFileUtils.
    return getAutoSaveFileUtils().getOddRoundAutoSaveFile();
  }

  @Override
  public AutoSaveFileUtils getAutoSaveFileUtils() {
    // ServerGame writes a round-boundary autosave; WebGameHost points saves at a temp folder.
    return new AutoSaveFileUtils();
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
    throw unsupported();
  }

  private static UnsupportedOperationException unsupported() {
    return new UnsupportedOperationException(
        "WebLaunchAction supports only direct in-process game runs (startGame).");
  }
}
