package org.triplea.web.controlplane.game;

import io.javalin.Javalin;
import io.javalin.http.ConflictResponse;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UnauthorizedResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.triplea.web.controlplane.auth.AuthFilter;
import org.triplea.web.controlplane.auth.Identity;
import org.triplea.web.controlplane.lobby.LobbyDao;
import org.triplea.web.controlplane.lobby.LobbyDao.ConnectInfo;
import org.triplea.web.controlplane.orchestrator.GameLauncher;
import org.triplea.web.controlplane.orchestrator.GameLauncher.LaunchSpec;
import org.triplea.web.controlplane.orchestrator.GameLauncher.LaunchedGame;
import org.triplea.web.controlplane.user.User;
import org.triplea.web.controlplane.user.UserDao;

/**
 * Routes a browser to a launched game's WebSocket: {@code GET /api/games/{id}/connect}. Authorizes
 * the caller (must be seated in or host of the game), then returns the running container's endpoint
 * — spawning one lazily (and waiting for it to accept connections) if none is live. This is the
 * lazy-rehydration entry point: when a reaped game is reconnected, it respawns from the latest
 * save.
 */
@Slf4j
public final class GameRouteController {

  private static final long READY_TIMEOUT_MS = 60_000;

  /** The endpoint the browser should dial for this game's WebSocket. */
  public record ConnectResponse(String wsEndpoint) {}

  private final LobbyDao lobbyDao;
  private final GameLauncher launcher;
  private final UserDao userDao;

  public GameRouteController(
      final LobbyDao lobbyDao, final GameLauncher launcher, final UserDao userDao) {
    this.lobbyDao = lobbyDao;
    this.launcher = launcher;
    this.userDao = userDao;
  }

  public void register(final Javalin app) {
    app.get("/api/games/{id}/connect", this::connect);
  }

  private void connect(final Context ctx) {
    final User user = currentUser(ctx);
    final UUID gameId = parseId(ctx);
    final ConnectInfo info =
        lobbyDao.connectInfo(gameId).orElseThrow(() -> new NotFoundResponse("No such game"));
    if (!lobbyDao.isUserInGame(gameId, user.id())) {
      throw new ForbiddenResponse("You are not in this game");
    }
    if ("lobby".equals(info.status())) {
      throw new ConflictResponse("Game has not been launched yet");
    }
    if (info.wsEndpoint() != null) {
      ctx.json(new ConnectResponse(info.wsEndpoint())); // container already running
      return;
    }
    // Lazy spawn: resume from the latest save if there is one (rehydration), else a fresh game.
    final LaunchedGame launched =
        launcher.launch(new LaunchSpec(gameId.toString(), info.mapXml(), info.saveRef()));
    lobbyDao.setContainer(gameId, launched.handle(), launched.wsEndpoint());
    if (!waitForReady(launched.wsEndpoint())) {
      log.warn("Game {} container not reachable in time at {}", gameId, launched.wsEndpoint());
    }
    ctx.json(new ConnectResponse(launched.wsEndpoint()));
  }

  /** Poll the container's WS port until it accepts a connection (the JVM is up and listening). */
  private static boolean waitForReady(final String wsEndpoint) {
    final URI uri = URI.create(wsEndpoint);
    final long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 1000);
        Thread.sleep(500); // brief grace for the WS accept loop after the port binds
        return true;
      } catch (final IOException e) {
        sleep(500);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }

  private static void sleep(final long ms) {
    try {
      Thread.sleep(ms);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private User currentUser(final Context ctx) {
    final Identity identity = ctx.attribute(AuthFilter.IDENTITY_ATTR);
    if (identity == null) {
      throw new UnauthorizedResponse("No session");
    }
    return userDao
        .findByIdentity(identity)
        .orElseThrow(() -> new UnauthorizedResponse("Unknown user"));
  }

  private static UUID parseId(final Context ctx) {
    try {
      return UUID.fromString(ctx.pathParam("id"));
    } catch (final IllegalArgumentException e) {
      throw new NotFoundResponse("No such game");
    }
  }
}
