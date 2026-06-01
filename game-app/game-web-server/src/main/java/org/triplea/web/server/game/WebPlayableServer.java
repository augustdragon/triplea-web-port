package org.triplea.web.server.game;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import lombok.extern.slf4j.Slf4j;

/**
 * Playable multiplayer runner: hosts a game in-process behind one {@link GameWebSocketServer}. The
 * server starts in the <b>setup</b> phase — connected browsers claim nations as human seats or
 * assign AI types to the rest ({@link SeatPlan}/{@link SeatRoster}), then anyone starts the game.
 * Human seats are driven from the browser via a {@link WebDecisionBridge}; the rest run as the
 * chosen AI. Decision requests are routed to the owning seat's connection; spectators (unclaimed
 * connections) only watch. Lifecycle (setup ↔ running, reset) lives in {@link GameController}.
 *
 * <p>Usage: {@code WebPlayableServer <gameXml> [port] [maxRounds] [stepDelayMs]}. (Seats are no
 * longer a launch argument — they are chosen in the browser.)
 */
@Slf4j
public final class WebPlayableServer {
  private WebPlayableServer() {}

  public static void main(final String[] args) throws Exception {
    if (args.length < 1 || args.length > 4) {
      System.err.println("Usage: WebPlayableServer <gameXml> [port] [maxRounds] [stepDelayMs]");
      System.exit(2);
      return;
    }
    final Path gameXml = Path.of(args[0]);
    final int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
    final int maxRounds = args.length > 2 ? Integer.parseInt(args[2]) : 20;
    final long stepDelayMs = args.length > 3 ? Long.parseLong(args[3]) : 300;

    final GameWebSocketServer server = new GameWebSocketServer(port);
    server.start();
    new GameController(gameXml, maxRounds, stepDelayMs, server).start();
    log.info(
        "Playable {} — connect clients to ws://<host>:{} and claim seats",
        gameXml.getFileName(),
        port);

    // The game runs on the controller's loop thread and the WebSocket server on its own; keep the
    // process alive here so the socket stays up across setup/running/reset to serve state.
    new CountDownLatch(1).await();
  }
}
