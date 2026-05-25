package org.triplea.web.server.game;

import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.triplea.ui.display.HeadlessDisplay;
import java.nio.file.Path;

/**
 * Runs an AI game in-process and prints step progression — a smoke check that the web server can
 * drive the engine. Usage: {@code AiGameRunnerCli <gameXml> [maxRounds]}.
 */
public final class AiGameRunnerCli {
  private static final int STEP_SAFETY_LIMIT = 5000;

  private AiGameRunnerCli() {}

  public static void main(final String[] args) {
    if (args.length < 1 || args.length > 2) {
      System.err.println("Usage: AiGameRunnerCli <gameXml> [maxRounds]");
      System.exit(2);
      return;
    }
    final Path gameXml = Path.of(args[0]);
    final int maxRounds = args.length == 2 ? Integer.parseInt(args[1]) : 3;

    final ServerGame game =
        WebGameHost.startAiGame(gameXml, PlayerTypes.FAST_AI, new HeadlessDisplay());
    game.setStopGameOnDelegateExecutionStop(true);

    int steps = 0;
    while (!game.isGameOver()
        && game.getData().getSequence().getRound() <= maxRounds
        && steps < STEP_SAFETY_LIMIT) {
      final var sequence = game.getData().getSequence();
      System.out.printf("round=%d step=%s%n", sequence.getRound(), sequence.getStep().getName());
      game.runNextStep();
      steps++;
    }

    System.out.printf(
        "Done. gameOver=%s round=%d steps=%d%n",
        game.isGameOver(), game.getData().getSequence().getRound(), steps);
    game.stopGame();
  }
}
