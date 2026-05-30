package org.triplea.web.server.game;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the playable game's lifecycle behind one long-lived {@link GameWebSocketServer}, so the
 * browser can reset the game without restarting the JVM. It runs the current game on its own daemon
 * game-loop thread; a client {@code {type:"control",action:"newGame"}} message stops that session
 * and starts a fresh one on the same socket (same map + human seat the server launched with) — a
 * testing convenience equivalent to {@code restart-web.ps1} but without a process bounce.
 *
 * <p>How a reset stops a parked engine cleanly: {@link WebPlayer#start} runs synchronously on the
 * game-loop thread and parks in {@link WebDecisionBridge#await} when waiting for a browser
 * decision. Closing the bridge completes that future exceptionally, so {@code await} throws and the
 * exception unwinds straight out of {@code runNextStep}; the loop sees {@code alive == false} and
 * exits. We deliberately do <b>not</b> call {@link ServerGame#stopGame()} here — it can {@code
 * ExitStatus.exit()} the whole JVM if it can't block delegate execution — and instead abandon the
 * old {@link ServerGame} to GC (each reset builds a fresh one). Resets are a manual, occasional
 * testing action, so the small per-reset retained state is acceptable.
 */
@Slf4j
public final class GameController {
  private static final int STEP_SAFETY_LIMIT = 10_000;
  private static final Gson GSON = new Gson();

  private final Path gameXml;
  private final String humanPlayer;
  private final int maxRounds;
  private final long stepDelayMs;
  private final GameWebSocketServer server;
  // The map's objectives.properties (static per map); empty if the map has none.
  private final Properties objectivesProps;

  // Resets run on a single thread so they're serialized and never block the WebSocket thread.
  private final ExecutorService restartExecutor =
      Executors.newSingleThreadExecutor(
          r -> {
            final Thread t = new Thread(r, "web-newgame");
            t.setDaemon(true);
            return t;
          });

  private volatile Session current;

  public GameController(
      final Path gameXml,
      final String humanPlayer,
      final int maxRounds,
      final long stepDelayMs,
      final GameWebSocketServer server) {
    this.gameXml = gameXml;
    this.humanPlayer = humanPlayer;
    this.maxRounds = maxRounds;
    this.stepDelayMs = stepDelayMs;
    this.server = server;
    this.objectivesProps = loadObjectivesProperties(gameXml);
  }

  /**
   * Load the map's {@code objectives.properties} (sibling-of-the-games-folder); empty if absent.
   */
  private static Properties loadObjectivesProperties(final Path gameXml) {
    final Properties props = new Properties();
    final Path file = gameXml.getParent().getParent().resolve("objectives.properties");
    if (Files.exists(file)) {
      try (InputStream in = Files.newInputStream(file)) {
        props.load(in);
      } catch (final IOException e) {
        log.warn("Could not read objectives.properties at {}", file, e);
      }
    }
    return props;
  }

  /** Wire inbound routing and launch the first game. Call once, after {@code server.start()}. */
  public void start() {
    server.setInboundHandler(this::onClientMessage);
    startSession();
    publishNotes();
  }

  /**
   * Read this game's notes (the {@code <property name="notes">} HTML in the game XML, parsed into
   * game properties) and broadcast them once. The socket caches and re-sends them on connect; they
   * don't change across {@code newGame} resets, so this runs only at startup.
   */
  private void publishNotes() {
    final String notes = current.game.getData().getProperties().get("notes", "");
    final JsonObject envelope = new JsonObject();
    envelope.addProperty("type", "notes");
    envelope.addProperty("html", notes);
    server.publishNotes(GSON.toJson(envelope));
  }

  /**
   * Evaluate the national objectives against the given game state and broadcast them. Published at
   * each step boundary (objectives change as territories/relationships change), and cached +
   * re-sent on connect. Read-only (see {@link ObjectivesProjector}).
   */
  private void publishObjectives(final GameData data) {
    final JsonObject envelope = new JsonObject();
    envelope.addProperty("type", "objectives");
    envelope.add("items", GSON.toJsonTree(ObjectivesProjector.project(data, objectivesProps)));
    server.publishObjectives(GSON.toJson(envelope));
  }

  /**
   * WebSocket thread: a {@code control}/{@code newGame} message resets the game (off-thread, so the
   * socket isn't blocked while the old loop winds down); anything else is a decision reply routed
   * to the current game's bridge.
   */
  private void onClientMessage(final String json) {
    if (isNewGameControl(json)) {
      log.info("New-game requested by client");
      restartExecutor.submit(this::newGame);
      return;
    }
    final Session session = current;
    if (session != null) {
      session.bridge.onClientMessage(json);
    }
  }

  private static boolean isNewGameControl(final String json) {
    try {
      final JsonObject msg = GSON.fromJson(json, JsonObject.class);
      return msg != null
          && msg.has("type")
          && "control".equals(msg.get("type").getAsString())
          && msg.has("action")
          && "newGame".equals(msg.get("action").getAsString());
    } catch (final RuntimeException e) {
      return false; // not JSON / not our control shape
    }
  }

  /** Restart-executor thread: tear down the running game (if any) and start a fresh one. */
  private void newGame() {
    final Session old = current;
    if (old != null) {
      old.stop();
    }
    server.resetForNewGame();
    startSession();
    log.info("New game started ({} as '{}')", gameXml.getFileName(), humanPlayer);
  }

  private void startSession() {
    final Session session = new Session();
    current = session;
    session.start();
  }

  /** One game instance: its bridge, display, engine, and the loop thread that steps it. */
  private final class Session {
    private final WebDecisionBridge bridge =
        new WebDecisionBridge(server::send, server::publishState);
    private final WebDisplay display = new WebDisplay(server::publishBattleEvent);
    private volatile boolean alive = true;
    private Thread thread;
    private ServerGame game;

    void start() {
      game =
          WebGameHost.startGame(gameXml, Set.of(humanPlayer), PlayerTypes.FAST_AI, bridge, display);
      display.setGameData(game.getData()); // enables battle-by-id round/force lookups
      game.setStopGameOnDelegateExecutionStop(true);
      thread = new Thread(this::run, "web-game-loop");
      thread.setDaemon(true);
      thread.start();
    }

    private void run() {
      try {
        server.publishState(GSON.toJson(StateProjector.project(game.getData())));
        publishObjectives(game.getData());
        int steps = 0;
        while (alive
            && !game.isGameOver()
            && game.getData().getSequence().getRound() <= maxRounds
            && steps < STEP_SAFETY_LIMIT) {
          game.runNextStep();
          if (!alive) {
            break;
          }
          server.publishState(GSON.toJson(StateProjector.project(game.getData())));
          publishObjectives(game.getData());
          steps++;
          Thread.sleep(stepDelayMs);
        }
        log.info(
            "Game loop ended: alive={} gameOver={} round={}",
            alive,
            game.isGameOver(),
            game.getData().getSequence().getRound());
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (final RuntimeException e) {
        // A reset closes the bridge, which makes the parked decision throw — expected, not an
        // error.
        if (alive) {
          log.error("Game loop error", e);
        } else {
          log.info("Game loop stopped for a new game");
        }
      } finally {
        bridge.close();
      }
    }

    void stop() {
      alive = false;
      bridge.close(); // unpark the engine thread if it's waiting on a browser decision
      if (thread != null) {
        thread.interrupt(); // break a Thread.sleep between steps promptly
        try {
          thread.join(8000);
          if (thread.isAlive()) {
            log.warn("Old game loop did not stop within 8s; abandoning it");
          }
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }
}
