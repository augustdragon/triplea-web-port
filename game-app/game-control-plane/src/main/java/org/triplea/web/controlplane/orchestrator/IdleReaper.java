package org.triplea.web.controlplane.orchestrator;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.triplea.web.controlplane.game.GameReportDao;

/**
 * Periodically reaps game containers that haven't committed a turn within the idle window — freeing
 * the JVM/container while the game's state stays safely on disk. A reaped game respawns from its
 * latest save on the next connect; if a browser was still attached, its connect-retry reconnects
 * and triggers that respawn, so a too-eager reap is self-healing.
 */
@Slf4j
public final class IdleReaper {

  private static final int SWEEP_SECONDS = 15;

  private final GameReportDao dao;
  private final GameReaper reaper;
  private final int idleSeconds;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            final Thread t = new Thread(r, "idle-reaper");
            t.setDaemon(true);
            return t;
          });

  public IdleReaper(final GameReportDao dao, final GameReaper reaper, final int idleSeconds) {
    this.dao = dao;
    this.reaper = reaper;
    this.idleSeconds = idleSeconds;
  }

  public void start() {
    scheduler.scheduleWithFixedDelay(this::sweep, SWEEP_SECONDS, SWEEP_SECONDS, TimeUnit.SECONDS);
    log.info("Idle reaper sweeping every {}s; idle threshold {}s", SWEEP_SECONDS, idleSeconds);
  }

  private void sweep() {
    try {
      for (final UUID gameId : dao.idleGameIds(idleSeconds)) {
        log.info("Reaping idle game {}", gameId);
        reaper.reapGame(gameId);
      }
    } catch (final RuntimeException e) {
      log.warn("Idle sweep failed: {}", e.getMessage());
    }
  }
}
