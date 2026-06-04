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
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.triplea.web.controlplane.auth.AuthFilter;
import org.triplea.web.controlplane.auth.ConnectTicket;
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
  private static final long TICKET_TTL_SECONDS = 60; // only spans the /connect → WS-open hop

  /**
   * The endpoint the browser should dial for this game's WebSocket, plus a short-lived single-use
   * ticket it presents as its first WS message (proving its seat), the seat it was assigned (null
   * for a host-only/spectator), and whether it is the host (may start the game).
   */
  public record ConnectResponse(String wsEndpoint, String ticket, String seat, boolean isHost) {}

  /**
   * Returned instead of a ws endpoint when the game is already over, so the client shows the end
   * screen rather than re-spawning a container that would immediately re-end.
   */
  public record FinishedResponse(String status, String reason, String winner) {}

  private final LobbyDao lobbyDao;
  private final GameLauncher launcher;
  private final UserDao userDao;
  private final String gameToken;

  public GameRouteController(
      final LobbyDao lobbyDao,
      final GameLauncher launcher,
      final UserDao userDao,
      final String gameToken) {
    this.lobbyDao = lobbyDao;
    this.launcher = launcher;
    this.userDao = userDao;
    this.gameToken = gameToken;
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
    if ("finished".equals(info.status())) {
      // Don't respawn a finished game — return its end summary so the client shows the end screen.
      ctx.json(new FinishedResponse("finished", info.endReason(), info.winner()));
      return;
    }
    if (info.wsEndpoint() != null) {
      ctx.json(response(gameId, info, user, info.wsEndpoint())); // container already running
      return;
    }
    // Lazy spawn: resume from the latest save if there is one (rehydration), else a fresh game.
    final LaunchedGame launched =
        launcher.launch(
            new LaunchSpec(
                gameId.toString(), info.mapXml(), info.saveRef(), info.turnLimitSeconds()));
    lobbyDao.setContainer(gameId, launched.handle(), launched.wsEndpoint());
    if (!waitForReady(launched.wsEndpoint())) {
      log.warn("Game {} container not reachable in time at {}", gameId, launched.wsEndpoint());
    }
    ctx.json(response(gameId, info, user, launched.wsEndpoint()));
  }

  /**
   * Resolve the caller's seat + host flag and mint a connect-ticket. The returned {@code
   * wsEndpoint} is a **same-origin path** (the control plane proxies it to the game's internal
   * localhost port), not a direct host:port — so the browser dials {@code
   * wss://<this-origin>/game/{id}/ws} and per- game ports stay off the internet. ({@code
   * containerWsEndpoint} is unused here; the proxy reads the internal endpoint from the DB.)
   */
  private ConnectResponse response(
      final UUID gameId,
      final ConnectInfo info,
      final User user,
      final String containerWsEndpoint) {
    final String seat = lobbyDao.seatForUser(gameId, user.id()).orElse(null);
    final boolean isHost = info.createdBy() == user.id();
    final String ticket = mintTicket(gameId, user, seat, isHost);
    return new ConnectResponse("/game/" + gameId + "/ws", ticket, seat, isHost);
  }

  /** Sign a single-use, short-lived ticket binding this user to its seat, or null if unsignable. */
  private String mintTicket(
      final UUID gameId, final User user, final String seat, final boolean isHost) {
    if (gameToken == null || gameToken.isBlank()) {
      log.warn("CONTROL_PLANE_GAME_TOKEN not set — cannot mint a connect ticket for {}", gameId);
      return null;
    }
    final ConnectTicket.Payload payload =
        new ConnectTicket.Payload(
            gameId.toString(),
            seat,
            user.id(),
            user.displayName(),
            isHost,
            UUID.randomUUID().toString(),
            Instant.now().getEpochSecond() + TICKET_TTL_SECONDS);
    return ConnectTicket.sign(payload, gameToken);
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
