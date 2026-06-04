package org.triplea.web.controlplane;

import io.javalin.Javalin;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
import org.triplea.web.controlplane.game.GameWsProxy;
import org.triplea.web.controlplane.game.InternalSeatsController;
import org.triplea.web.controlplane.http.DevLoginController;
import org.triplea.web.controlplane.http.HealthController;
import org.triplea.web.controlplane.http.MeController;
import org.triplea.web.controlplane.json.GsonJsonMapper;
import org.triplea.web.controlplane.lobby.LobbyBroadcaster;
import org.triplea.web.controlplane.lobby.LobbyController;
import org.triplea.web.controlplane.lobby.LobbyDao;
import org.triplea.web.controlplane.orchestrator.DockerGameLauncher;
import org.triplea.web.controlplane.orchestrator.GameLauncher;
import org.triplea.web.controlplane.orchestrator.GameReaper;
import org.triplea.web.controlplane.orchestrator.IdleReaper;
import org.triplea.web.controlplane.orchestrator.ProcessGameLauncher;
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

    final GameLauncher gameLauncher =
        "process".equals(config.launcher())
            ? new ProcessGameLauncher(config)
            : new DockerGameLauncher(config);
    final LobbyDao lobbyDao = new LobbyDao(database.jdbi());
    final LobbyBroadcaster lobbyBroadcaster = new LobbyBroadcaster(lobbyDao);

    new HealthController(database).register(app);
    app.before("/api/*", new AuthFilter(jwt, allowList));
    new MeController(userDao).register(app);
    new LobbyController(lobbyDao, gameCatalog, userDao, lobbyBroadcaster::broadcast).register(app);
    // Lazy spawn + route the browser to a launched game's container; mint the connect-ticket that
    // proves the caller's seat to the container (signed with the shared game token it also holds).
    new GameRouteController(lobbyDao, gameLauncher, userDao, config.gameToken()).register(app);
    // Service-to-service (outside /api/* so the user AuthFilter doesn't apply; game-token auth):
    // containers report lifecycle/turn events, and fetch their seat→identity assignments at boot.
    // Reaping (on game-finish and on idle) frees containers — their state stays on disk, and the
    // next connect respawns from the save.
    final GameReportDao gameReportDao = new GameReportDao(database.jdbi());
    final GameReaper gameReaper = new GameReaper(gameLauncher, gameReportDao);
    new GameReportController(gameReportDao, gameReaper, config.gameToken()).register(app);
    new InternalSeatsController(lobbyDao, config.gameToken()).register(app);
    new IdleReaper(gameReportDao, gameReaper, config.gameIdleSeconds()).start();

    // Lobby WebSocket keepalive: ping every 25s so idle connections aren't dropped by an
    // intermediary and dead sessions are pruned (the client ignores the ping).
    final ScheduledExecutorService lobbyKeepalive =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              final Thread t = new Thread(r, "lobby-keepalive");
              t.setDaemon(true);
              return t;
            });
    lobbyKeepalive.scheduleWithFixedDelay(lobbyBroadcaster::pingAll, 25, 25, TimeUnit.SECONDS);

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

    // Per-game WebSocket proxy: the browser dials this same-origin path (behind TLS), and the
    // control
    // plane pipes it to the game's internal ws://localhost:<port>. Keeps per-game ports off the
    // internet so one domain/cert covers everything. Authorize the session here (cookie → JWT →
    // seat/host membership); the browser's connect-ticket is forwarded to the game and verified
    // there.
    app.ws(
        "/game/{id}/ws",
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
                final UUID gameId;
                try {
                  gameId = UUID.fromString(ctx.pathParam("id"));
                } catch (final IllegalArgumentException e) {
                  ctx.closeSession();
                  return;
                }
                final var user = userDao.findByIdentity(identity).orElse(null);
                final var info = lobbyDao.connectInfo(gameId).orElse(null);
                if (user == null
                    || !lobbyDao.isUserInGame(gameId, user.id())
                    || info == null
                    || info.wsEndpoint() == null) {
                  ctx.closeSession();
                  return;
                }
                final GameWsProxy proxy = new GameWsProxy(ctx, info.wsEndpoint());
                ctx.attribute("proxy", proxy);
                proxy.connectUpstream();
              });
          ws.onMessage(
              ctx -> {
                final GameWsProxy proxy = ctx.attribute("proxy");
                if (proxy != null) {
                  proxy.toUpstream(ctx.message());
                }
              });
          ws.onClose(
              ctx -> {
                final GameWsProxy proxy = ctx.attribute("proxy");
                if (proxy != null) {
                  proxy.close();
                }
              });
          ws.onError(
              ctx -> {
                final GameWsProxy proxy = ctx.attribute("proxy");
                if (proxy != null) {
                  proxy.close();
                }
              });
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
