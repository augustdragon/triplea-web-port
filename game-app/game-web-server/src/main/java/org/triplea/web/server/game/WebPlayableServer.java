package org.triplea.web.server.game;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
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
 * <p>Usage: {@code WebPlayableServer <gameXml> [--port=8080] [--max-rounds=20]
 * [--step-delay-ms=300] [--game-id=<id>] [--save-ref=<slot>]}. The game XML is the one required
 * positional argument; everything else is an optional {@code --key=value} flag with a dev default,
 * so a bare {@code <gameXml>} still works. The control plane spawns this with {@code --game-id}
 * (isolating the game's save slot) and, to resume, {@code --save-ref}; seats are chosen in the
 * browser.
 */
@Slf4j
public final class WebPlayableServer {
  private WebPlayableServer() {}

  public static void main(final String[] args) throws Exception {
    String gameXmlArg = null;
    final Map<String, String> flags = new HashMap<>();
    for (final String arg : args) {
      if (arg.startsWith("--")) {
        final int eq = arg.indexOf('=');
        if (eq < 0) {
          usage("flag needs a value: " + arg);
          return;
        }
        flags.put(arg.substring(2, eq), arg.substring(eq + 1));
      } else if (gameXmlArg == null) {
        gameXmlArg = arg;
      } else {
        usage("unexpected argument: " + arg);
        return;
      }
    }
    if (gameXmlArg == null) {
      usage("a game XML path is required");
      return;
    }

    final Path gameXml = Path.of(gameXmlArg);
    final int port = intFlag(flags, "port", 8080);
    final int maxRounds = intFlag(flags, "max-rounds", 20);
    final long stepDelayMs = (long) intFlag(flags, "step-delay-ms", 300);
    final String gameId = flags.get("game-id"); // null → save slot derived from the game name
    final String saveRef = flags.get("save-ref"); // null → resume from this game's own slot

    final GameWebSocketServer server = new GameWebSocketServer(port);
    server.start();
    // Saves persist across restarts (the point of resume), so this is a stable per-user dir, not a
    // temp folder. The slot within it is the game id when spawned for a lobby game.
    final SaveStore saveStore =
        new SaveStore(Path.of(System.getProperty("user.home"), ".triplea-web", "saves"));
    new GameController(gameXml, maxRounds, stepDelayMs, server, saveStore, gameId, saveRef).start();
    log.info(
        "Playable {} on :{} (game-id={}) — connect clients and claim seats",
        gameXml.getFileName(),
        port,
        gameId == null ? "<by-name>" : gameId);

    // The game runs on the controller's loop thread and the WebSocket server on its own; keep the
    // process alive here so the socket stays up across setup/running/reset to serve state.
    new CountDownLatch(1).await();
  }

  private static int intFlag(final Map<String, String> flags, final String key, final int dflt) {
    final String value = flags.get(key);
    return value == null ? dflt : Integer.parseInt(value);
  }

  private static void usage(final String problem) {
    System.err.println("Error: " + problem);
    System.err.println(
        "Usage: WebPlayableServer <gameXml> [--port=8080] [--max-rounds=20]"
            + " [--step-delay-ms=300] [--game-id=<id>] [--save-ref=<slot>]");
    System.exit(2);
  }
}
