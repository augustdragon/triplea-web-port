package games.strategy.engine.data;

import games.strategy.engine.chat.Chat;
import games.strategy.engine.framework.AutoSaveFileUtils;
import games.strategy.engine.framework.IGame;
import games.strategy.engine.framework.LocalPlayers;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.launcher.LaunchAction;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.player.Player;
import games.strategy.triplea.ResourceLoader;
import games.strategy.triplea.ui.display.HeadlessDisplay;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
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
}
