package games.strategy.engine.framework.startup.launcher;

import games.strategy.engine.chat.Chat;
import games.strategy.engine.framework.AutoSaveFileUtils;
import games.strategy.engine.framework.IGame;
import games.strategy.engine.framework.LocalPlayers;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.player.Player;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

/**
 * Abstraction to allow decoupling the engine from the way a game is launched and hosted. The
 * networked multiplayer launcher was removed in the server-only prune; the surviving
 * implementations ({@code WebLaunchAction}, {@code TestLaunchAction}) run a game in-process.
 */
public interface LaunchAction {
  void onEnd(String message);

  Collection<PlayerTypes.Type> getPlayerTypes();

  void startGame(LocalPlayers localPlayers, IGame game, Set<Player> players, Chat chat);

  Path getAutoSaveFile();

  void onLaunch(ServerGame serverGame);

  AutoSaveFileUtils getAutoSaveFileUtils();

  /**
   * Controls if the AI should be avoided when preparing a game. Headless systems may choose to
   * avoid AI usage where possible to reduce the load on the system.
   */
  boolean shouldMinimizeExpensiveAiUse();

  void handleError(String error);

  PlayerTypes.Type getDefaultLocalPlayerType();
}
