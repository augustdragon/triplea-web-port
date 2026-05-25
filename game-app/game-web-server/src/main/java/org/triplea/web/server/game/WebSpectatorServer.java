package org.triplea.web.server.game;

import com.google.gson.Gson;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.triplea.ui.display.HeadlessDisplay;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 2 live spectator: runs an AI game in-process and pushes a {@link StateSnapshot} over
 * WebSocket after every engine step, so a browser can watch the game advance. The engine still
 * reports to a no-op {@link HeadlessDisplay}; state is read by polling after each step (the full
 * {@code WebDisplay} for fine-grained battle events comes in Phase 3).
 *
 * <p>Usage: {@code WebSpectatorServer <gameXml> [port] [maxRounds] [stepDelayMs]}.
 */
@Slf4j
public final class WebSpectatorServer {
  private static final Gson GSON = new Gson();
  private static final int STEP_SAFETY_LIMIT = 10_000;

  private WebSpectatorServer() {}

  public static void main(final String[] args) throws Exception {
    if (args.length < 1 || args.length > 4) {
      System.err.println("Usage: WebSpectatorServer <gameXml> [port] [maxRounds] [stepDelayMs]");
      System.exit(2);
      return;
    }
    final Path gameXml = Path.of(args[0]);
    final int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
    final int maxRounds = args.length > 2 ? Integer.parseInt(args[2]) : 20;
    final long stepDelayMs = args.length > 3 ? Long.parseLong(args[3]) : 400;

    final SpectatorWebSocketServer server = new SpectatorWebSocketServer(port);
    server.start();
    log.info("Spectating {} — connect a client to ws://<host>:{}", gameXml.getFileName(), port);

    final ServerGame game =
        WebGameHost.startAiGame(gameXml, PlayerTypes.FAST_AI, new HeadlessDisplay());
    game.setStopGameOnDelegateExecutionStop(true);
    server.publish(GSON.toJson(StateProjector.project(game.getData())));

    int steps = 0;
    while (!game.isGameOver()
        && game.getData().getSequence().getRound() <= maxRounds
        && steps < STEP_SAFETY_LIMIT) {
      game.runNextStep();
      server.publish(GSON.toJson(StateProjector.project(game.getData())));
      steps++;
      Thread.sleep(stepDelayMs);
    }

    log.info(
        "Game finished: gameOver={} round={}. WebSocket server stays up to serve final state.",
        game.isGameOver(),
        game.getData().getSequence().getRound());
    // Intentionally do not stop the server: keep final state available to spectators.
  }
}
