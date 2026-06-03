package org.triplea.web.controlplane;

import io.javalin.Javalin;
import lombok.extern.slf4j.Slf4j;
import org.triplea.web.controlplane.auth.AllowList;
import org.triplea.web.controlplane.auth.AuthFilter;
import org.triplea.web.controlplane.auth.ConfigAllowList;
import org.triplea.web.controlplane.auth.Identity;
import org.triplea.web.controlplane.auth.JwtService;
import org.triplea.web.controlplane.auth.LoginService;
import org.triplea.web.controlplane.auth.SessionCookies;
import org.triplea.web.controlplane.config.ControlPlaneConfig;
import org.triplea.web.controlplane.db.Database;
import org.triplea.web.controlplane.game.GameCatalog;
import org.triplea.web.controlplane.game.GameReportController;
import org.triplea.web.controlplane.game.GameReportDao;
import org.triplea.web.controlplane.game.GameRouteController;
import org.triplea.web.controlplane.http.DevLoginController;
import org.triplea.web.controlplane.http.HealthController;
import org.triplea.web.controlplane.http.MeController;
import org.triplea.web.controlplane.json.GsonJsonMapper;
import org.triplea.web.controlplane.lobby.LobbyBroadcaster;
import org.triplea.web.controlplane.lobby.LobbyController;
import org.triplea.web.controlplane.lobby.LobbyDao;
import org.triplea.web.controlplane.orchestrator.DockerGameLauncher;
import org.triplea.web.controlplane.orchestrator.GameLauncher;
import org.triplea.web.controlplane.user.UserDao;

/**
 * Entry point for the web-port control plane. Boots in fail-fast order: validate config from the
 * environment, open the database pool and run schema migrations, wire auth + routes, then start the
 * HTTP server. If config is missing or the DB is unreachable at startup, this throws and the
 * process exits rather than serving a half-wired server.
 *
 * <p>M2 adds session auth: a JWT cookie, an allow-list checked on every request, and {@code
 * /api/me}. Real Google/Discord OAuth (pac4j) drops in behind the same {@code LoginService} seam
 * once apps are registered; until then the dev-login route (dev profile only) exercises the flow.
 */
@Slf4j
public final class ControlPlaneMain {

  private ControlPlaneMain() {}

  public static void main(final String[] args) {
    final ControlPlaneConfig config = ControlPlaneConfig.fromEnv();
    final Database database = Database.create(config);
    final GameCatalog gameCatalog = GameCatalog.fromConfig(config);

    final JwtService jwt = new JwtService(config.jwtSecret(), config.sessionTtlMinutes());
    final AllowList allowList = new ConfigAllowList(config.allowList());
    final UserDao userDao = new UserDao(database.jdbi());
    final LoginService loginService = new LoginService(allowList, userDao, jwt);
    final SessionCookies sessionCookies =
        new SessionCookies(config.secureCookies(), jwt.ttlSeconds());

    final Javalin app = Javalin.create(cfg -> cfg.jsonMapper(new GsonJsonMapper()));

    final GameLauncher gameLauncher = new DockerGameLauncher(config);
    final LobbyDao lobbyDao = new LobbyDao(database.jdbi());
    final LobbyBroadcaster lobbyBroadcaster = new LobbyBroadcaster(lobbyDao);

    new HealthController(database).register(app);
    app.before("/api/*", new AuthFilter(jwt, allowList));
    new MeController(userDao).register(app);
    new LobbyController(lobbyDao, gameCatalog, userDao, lobbyBroadcaster::broadcast).register(app);
    // Lazy spawn + route the browser to a launched game's container.
    new GameRouteController(lobbyDao, gameLauncher, userDao).register(app);
    // Service-to-service: game containers report lifecycle/turn events here (token-authenticated,
    // outside /api/* so the user AuthFilter doesn't apply).
    new GameReportController(new GameReportDao(database.jdbi()), config.gameToken()).register(app);

    // Live lobby updates. The WS handshake carries the same session cookie; reject unauthenticated
    // or un-invited connections, otherwise register the client for broadcasts.
    app.ws(
        "/ws/lobby",
        ws -> {
          ws.onConnect(
              ctx -> {
                final String token = SessionCookies.read(ctx);
                final Identity identity = token == null ? null : jwt.verify(token).orElse(null);
                if (identity == null
                    || !allowList.isAllowed(identity.provider(), identity.subject())) {
                  ctx.closeSession();
                  return;
                }
                lobbyBroadcaster.add(ctx);
              });
          ws.onClose(ctx -> lobbyBroadcaster.remove(ctx));
          ws.onError(ctx -> lobbyBroadcaster.remove(ctx));
        });
    app.post(
        "/api/logout",
        ctx -> {
          sessionCookies.clear(ctx);
          ctx.status(204);
        });

    if (config.devLoginEnabled()) {
      new DevLoginController(loginService, sessionCookies).register(app);
      log.warn("DEV LOGIN ENABLED — POST /api/dev-login is active (never permitted in prod)");
    }

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
    log.info(
        "Control plane listening on :{} (profile={}, {} allow-listed, {} game(s) available)",
        config.httpPort(),
        config.profile(),
        config.allowList().size(),
        gameCatalog.all().size());
  }
}
