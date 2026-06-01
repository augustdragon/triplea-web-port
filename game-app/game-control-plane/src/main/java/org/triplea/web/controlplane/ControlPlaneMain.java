package org.triplea.web.controlplane;

import io.javalin.Javalin;
import lombok.extern.slf4j.Slf4j;
import org.triplea.web.controlplane.config.ControlPlaneConfig;
import org.triplea.web.controlplane.db.Database;
import org.triplea.web.controlplane.http.HealthController;

/**
 * Entry point for the web-port control plane. Boots in fail-fast order: validate config from the
 * environment, open the database pool and run schema migrations, then start the HTTP server and
 * register routes. If config is missing or the DB is unreachable at startup, this throws and the
 * process exits rather than serving a half-wired server.
 *
 * <p>M1 wires only {@code /health}; auth, lobby, game-report, and orchestration routes are added in
 * later milestones (see {@code game-app/game-control-plane/AGENTS.md}).
 */
@Slf4j
public final class ControlPlaneMain {

  private ControlPlaneMain() {}

  public static void main(final String[] args) {
    final ControlPlaneConfig config = ControlPlaneConfig.fromEnv();
    final Database database = Database.create(config);

    final Javalin app = Javalin.create();
    new HealthController(database).register(app);

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  log.info("Shutting down control plane");
                  app.stop();
                  database.close();
                },
                "control-plane-shutdown"));

    app.start(config.httpPort());
    log.info("Control plane listening on :{}", config.httpPort());
  }
}
