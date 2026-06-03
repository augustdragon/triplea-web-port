package org.triplea.web.controlplane.orchestrator;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.triplea.web.controlplane.game.GameReportDao;

/**
 * Stops a game's container and clears its handle so the next connect respawns it from the latest
 * save (lazy rehydration). Used both when a game finishes and by the {@link IdleReaper}.
 */
@Slf4j
public final class GameReaper {

  private final GameLauncher launcher;
  private final GameReportDao dao;

  public GameReaper(final GameLauncher launcher, final GameReportDao dao) {
    this.launcher = launcher;
    this.dao = dao;
  }

  /** Reap the game's container (if one is live) and clear container_id/ws_endpoint on the row. */
  public void reapGame(final UUID gameId) {
    dao.containerId(gameId).ifPresent(launcher::reap);
    dao.clearContainer(gameId);
  }
}
