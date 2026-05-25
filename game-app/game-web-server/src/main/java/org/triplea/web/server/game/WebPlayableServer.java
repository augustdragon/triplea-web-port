package org.triplea.web.server.game;

import com.google.gson.Gson;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.triplea.ui.display.HeadlessDisplay;
import java.nio.file.Path;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 3 playable runner: runs a game in-process where one seat ({@code humanPlayer}) is driven
 * from the browser via a {@link WebDecisionBridge}, and the rest are AI. State is pushed over
 * WebSocket after each step, exactly as the spectator does; additionally the human seat's blocking
 * decisions (3b: purchase) are sent as {@code request} envelopes and answered by the client.
 *
 * <p>The game-loop thread parks inside {@code runNextStep()} whenever the human seat must decide
 * (its {@code start()} blocks on the bridge); the WebSocket server runs on its own thread and
 * delivers the reply, waking the loop. Single-browser hotseat for now.
 *
 * <p>Usage: {@code WebPlayableServer <gameXml> <humanPlayer> [port] [maxRounds] [stepDelayMs]}.
 */
@Slf4j
public final class WebPlayableServer {
  private static final Gson GSON = new Gson();
  private static final int STEP_SAFETY_LIMIT = 10_000;

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
    final WebDecisionBridge bridge = new WebDecisionBridge(server::send);
    server.setInboundHandler(bridge::onClientMessage);
    log.info(
        "Playable {} as '{}' — connect a client to ws://<host>:{}",
        gameXml.getFileName(),
        humanPlayer,
        port);

    final ServerGame game =
        WebGameHost.startGame(
            gameXml, Set.of(humanPlayer), PlayerTypes.FAST_AI, bridge, new HeadlessDisplay());
    game.setStopGameOnDelegateExecutionStop(true);
    server.publishState(GSON.toJson(StateProjector.project(game.getData())));

    int steps = 0;
    try {
      while (!game.isGameOver()
          && game.getData().getSequence().getRound() <= maxRounds
          && steps < STEP_SAFETY_LIMIT) {
        game.runNextStep();
        server.publishState(GSON.toJson(StateProjector.project(game.getData())));
        steps++;
        Thread.sleep(stepDelayMs);
      }
    } finally {
      bridge.close();
    }

    log.info(
        "Game finished: gameOver={} round={}. WebSocket server stays up to serve final state.",
        game.isGameOver(),
        game.getData().getSequence().getRound());
  }
}
