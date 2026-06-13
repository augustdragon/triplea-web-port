package org.triplea.web.server.game;

import games.strategy.engine.chat.Chat;
import games.strategy.engine.display.IDisplay;
import games.strategy.engine.framework.AutoSaveFileUtils;
import games.strategy.engine.framework.IGame;
import games.strategy.engine.framework.LocalPlayers;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.launcher.LaunchAction;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.player.Player;
import games.strategy.triplea.ResourceLoader;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
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
}
