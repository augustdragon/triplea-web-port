package org.triplea.web.server.game;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 3 playable runner: runs a game in-process where one seat ({@code humanPlayer}) is driven
 * from the browser via a {@link WebDecisionBridge}, and the rest are AI. State is pushed over
 * WebSocket after each step, exactly as the spectator does; the human seat's blocking decisions are
 * sent as {@code request} envelopes and answered by the client.
 *
 * <p>Lifecycle lives in {@link GameController}: each game runs on its own daemon game-loop thread,
 * parking inside {@code runNextStep()} whenever the human seat must decide; the WebSocket server
 * runs on its own thread and delivers the reply, waking the loop. The controller also handles the
 * browser's "new game" reset, so a tester can restart without bouncing the JVM. Single-browser
 * hotseat for now.
 *
 * <p>Usage: {@code WebPlayableServer <gameXml> <humanPlayer> [port] [maxRounds] [stepDelayMs]}.
 */
@Slf4j
public final class WebPlayableServer {
  private WebPlayableServer() {}

  public static void main(final String[] args) throws Exception {
    if (args.length < 2 || args.length > 5) {
      System.err.println(
          "Usage: WebPlayableServer <gameXml> <humanPlayer> [port] [maxRounds] [stepDelayMs]");
      System.exit(2);
      return;
    }
    final Path gameXml = Path.of(args[0]);
    final String humanPlayer = args[1];
    final int port = args.length > 2 ? Integer.parseInt(args[2]) : 8080;
    final int maxRounds = args.length > 3 ? Integer.parseInt(args[3]) : 20;
    final long stepDelayMs = args.length > 4 ? Long.parseLong(args[4]) : 300;

    final GameWebSocketServer server = new GameWebSocketServer(port);
    server.start();
    new GameController(gameXml, humanPlayer, maxRounds, stepDelayMs, server).start();
    log.info(
        "Playable {} as '{}' — connect a client to ws://<host>:{}",
        gameXml.getFileName(),
        humanPlayer,
        port);

    // The game runs on the controller's loop thread and the WebSocket server on its own; keep the
    // process alive here so the socket stays up across games (and after one ends) to serve state.
    new CountDownLatch(1).await();
  }
}
