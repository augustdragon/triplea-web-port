package org.triplea.web.server.game;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.triplea.ui.display.HeadlessDisplay;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs an AI game in-process and prints step progression — a smoke check that the web server can
 * drive the engine, including host-side victory detection. Usage: {@code AiGameRunnerCli <gameXml>
 * [maxRounds]}.
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
    int lastRound = 0;
    List<String> winners = List.of();
    while (!game.isGameOver()
        && game.getData().getSequence().getRound() <= maxRounds
        && steps < STEP_SAFETY_LIMIT) {
      final var sequence = game.getData().getSequence();
      System.out.printf("round=%d step=%s%n", sequence.getRound(), sequence.getStep().getName());
      game.runNextStep();
      steps++;
      // At each round boundary, run the same host-side victory detection the real game loop uses.
      final int round = game.getData().getSequence().getRound();
      if (round != lastRound) {
        lastRound = round;
        winners =
            HostVictoryDetector.detectWinners(game.getData()).stream()
                .map(GamePlayer::getName)
                .toList();
        if (!winners.isEmpty()) {
          System.out.println("VICTORY detected at round " + round + " — winners: " + winners);
          break;
        }
      }
    }

    System.out.printf(
        "Done. gameOver=%s winners=%s round=%d steps=%d%n",
        game.isGameOver(), winners, game.getData().getSequence().getRound(), steps);
    game.stopGame();
  }
}
