package org.triplea.web.server.game;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.player.Player;
import games.strategy.triplea.delegate.BidPurchaseDelegate;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.WebSocket;

/**
 * Owns one game's full lifecycle behind a long-lived {@link GameWebSocketServer}. A game JVM has
 * two states:
 *
 * <ul>
 *   <li><b>SETUP</b> — no {@link ServerGame} yet. The controller loads {@link GameData}, builds a
 *       {@link SeatPlan}, and serves a roster. Clients claim seats as human or assign AI types to
 *       the rest (mirroring the engine's local/network seat selection). On {@code startGame} the
 *       plan becomes the engine's {@code Set<Player>} and the game launches.
 *   <li><b>RUNNING</b> — the {@link Session} steps the game on a daemon loop thread, parking in
 *       {@link WebDecisionBridge#await} whenever a human seat must decide. Decision requests are
 *       routed to the owning seat's connection; {@code newGame} returns to SETUP.
 * </ul>
 *
 * <p>The {@link GameWebSocketServer}'s seat↔connection registry serves both phases: a claim made in
 * SETUP is the same ownership the in-game router uses to deliver decisions in RUNNING.
 *
 * <p>How a reset stops a parked engine cleanly: {@link WebPlayer#start} runs on the game-loop
 * thread and parks in {@code await}. Closing the bridge completes that future exceptionally, so
 * {@code await} throws and unwinds out of {@code runNextStep}; the loop sees {@code alive == false}
 * and exits. We deliberately do <b>not</b> call {@link ServerGame#stopGame()} (it can {@code
 * ExitStatus.exit()} the JVM) and instead abandon the old {@link ServerGame} to GC.
 *
 * <p>Setup mutations arrive on WebSocket threads; all are serialized behind this controller's
 * monitor. Game launch/reset run on a single-thread executor so the WebSocket thread is never
 * blocked while a game loop winds down.
 */
@Slf4j
public final class GameController {
  private static final int STEP_SAFETY_LIMIT = 10_000;
  private static final Gson GSON = new Gson();
  private static final int AUTH_TIMEOUT_SECONDS = 15; // close a lobby connection that never auths
  private static final int WS_CLOSE_UNAUTHORIZED = 4401; // app-private close code

  private final Path gameXml;
  private final int maxRounds;
  private final long stepDelayMs;
  private final GameWebSocketServer server;
  private final SaveStore saveStore;
  // The control plane's game id, when this container was spawned for a lobby game. Drives the save
  // slot so each game's autosave is isolated. Null in standalone dev (slot derived from game name).
  private final @Nullable String gameId;
  // A save to resume from at the control plane's behest. Null → resume from this game's own slot
  // when an autosave exists there.
  private final @Nullable String saveRef;
  // Reports lifecycle + per-turn progress to the control plane (no-op in standalone dev).
  private final GameReporter reporter;
  // The map's objectives.properties (static per map); empty if the map has none.
  private final Properties objectivesProps;

  // Lobby mode: seats are pre-assigned from the control plane's authenticated roster and the game
  // WS requires a connect-ticket. Off in standalone dev, where seats are claimed by name
  // in-browser.
  private final boolean lobbyMode;
  private final @Nullable String gameToken; // verifies connect-tickets (HMAC key) in lobby mode
  private final @Nullable List<LobbySeat> lobbyAssignments;
  // Ticket gates (lobby mode): nonces already redeemed (single-use), and the connections that have
  // authenticated, with the subset that are the host (may start the game).
  private final Set<String> usedNonces = ConcurrentHashMap.newKeySet();
  private final Set<WebSocket> authedConns = ConcurrentHashMap.newKeySet();
  private final Set<WebSocket> hostConns = ConcurrentHashMap.newKeySet();
  private final ScheduledExecutorService authTimeout =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            final Thread t = new Thread(r, "web-game-auth-timeout");
            t.setDaemon(true);
            return t;
          });

  // Launches and resets run on a single thread so they're serialized and never block a WS thread.
  private final ExecutorService lifecycleExecutor =
      Executors.newSingleThreadExecutor(
          r -> {
            final Thread t = new Thread(r, "web-game-lifecycle");
            t.setDaemon(true);
            return t;
          });

  // The setup-loaded game data + seat plan (SETUP). At launch, gameData is handed to the engine.
  private volatile @Nullable GameData gameData;
  private volatile @Nullable SeatPlan plan;
  private volatile String phase = "setup";
  private volatile @Nullable Session current;
  // The save slot for this game (derived from the game name); the autosave target.
  private volatile @Nullable String saveSlot;
  // Metadata about an existing autosave (round + step), surfaced in the roster as a resume option.
  private volatile @Nullable SeatRoster.SavedGame savedGameInfo;
  // Seats that are human-driven (WebPlayer) in the running game — set at launch, empty in setup.
  // Lets the client offer rejoin on an unmanned human seat (whose decision is buffered) but not AI.
  private volatile Set<String> humanSeats = Set.of();

  public GameController(
      final Path gameXml,
      final int maxRounds,
      final long stepDelayMs,
      final GameWebSocketServer server,
      final SaveStore saveStore,
      final @Nullable String gameId,
      final @Nullable String saveRef,
      final GameReporter reporter,
      final @Nullable String gameToken,
      final @Nullable List<LobbySeat> lobbyAssignments) {
    this.gameXml = gameXml;
    this.maxRounds = maxRounds;
    this.stepDelayMs = stepDelayMs;
    this.server = server;
    this.saveStore = saveStore;
    this.gameId = gameId;
    this.saveRef = saveRef;
    this.reporter = reporter;
    this.objectivesProps = loadObjectivesProperties(gameXml);
    this.gameToken = gameToken;
    this.lobbyAssignments = lobbyAssignments;
    // Lobby mode needs the game id (save slot + ticket gameId match) and a token to verify tickets.
    this.lobbyMode = lobbyAssignments != null && gameId != null && gameToken != null;
  }

  /** The save slot to READ when resuming: an explicit save-ref, else this game's own slot. */
  private @Nullable String resumeSlot() {
    return saveRef != null ? saveRef : saveSlot;
  }

  /** Wire routing and enter the setup phase. Call once, after {@code server.start()}. */
  public void start() {
    server.setInboundHandler(this::onClientMessage);
    server.setSeatVacatedHandler(this::onSeatVacated);
    if (lobbyMode) {
      server.setConnectHandler(this::armAuthTimeout); // close a connection that never authenticates
    }
    enterSetup(null);
  }

  /** Load fresh game data + a seat plan (carrying over prior seat choices) and serve the roster. */
  private synchronized void enterSetup(final @Nullable SeatPlan prior) {
    final GameData data = WebGameHost.load(gameXml);
    final SeatPlan newPlan = new SeatPlan(data);
    if (prior != null) {
      newPlan.carryOver(prior);
    }
    if (lobbyMode) {
      // Pre-assign seats from the lobby's authenticated roster (overrides any carry-over).
      newPlan.applyAssignments(lobbyAssignments);
    }
    gameData = data;
    plan = newPlan;
    // The save slot is the control-plane game id when spawned for a lobby game (isolates each
    // game's autosave); otherwise it's derived from the game name (standalone dev, single game).
    saveSlot = gameId != null ? gameId : slotFor(data.getGameName());
    savedGameInfo = peekSave(resumeSlot()); // offer a resume option if an autosave exists
    humanSeats = Set.of(); // no running game yet
    // Lobby games open into a "waiting room" (seats assigned, awaiting connections); standalone
    // hotseat opens into "setup" (claim seats by name in-browser).
    phase = lobbyMode ? "waiting" : "setup";
    server.resetForNewGame();
    publishSeats();
    publishNotes();
    log.info(
        "{} phase: {} ({} seats)",
        phase,
        gameXml.getFileName(),
        newPlan.toRoster(phase).seats().size());
  }

  /**
   * WebSocket thread: a {@code control} message drives setup (claim/release/type) or lifecycle
   * (start/new game); anything else is a decision reply routed to the running game's bridge, tagged
   * with the connection's seat so cross-seat replies are rejected.
   */
  private void onClientMessage(final WebSocket conn, final String message) {
    final JsonObject msg = tryParse(message);
    if (msg != null && "auth".equals(optString(msg, "type"))) {
      handleAuth(conn, msg);
      return;
    }
    if (msg != null && "control".equals(optString(msg, "type"))) {
      handleControl(conn, msg);
      return;
    }
    final Session session = current;
    if (session != null) {
      final String seat = server.seatOf(conn);
      if (session.bridge.onClientMessage(seat, message)) {
        server.clearPendingRequest();
      }
    }
  }

  private synchronized void handleControl(final WebSocket conn, final JsonObject msg) {
    final String action = optString(msg, "action");
    if (action == null) {
      return;
    }
    if (lobbyMode) {
      // Lobby games: seats are pre-assigned and ticket-bound (see handleAuth); the only control a
      // client may issue is the host starting the game. Claim/release/type/resume/newGame are
      // standalone-only.
      if ("startGame".equals(action) && hostConns.contains(conn)) {
        lifecycleExecutor.submit(this::doLobbyStart);
      } else if (!"startGame".equals(action)) {
        log.debug("Ignoring '{}' in lobby mode (seats are pre-assigned)", action);
      }
      return;
    }
    switch (action) {
      case "claimSeat" -> {
        final String seat = optString(msg, "seat");
        final SeatPlan p = plan;
        if (seat != null && p != null && p.hasSeat(seat)) {
          p.claim(seat, ownerLabel(conn, msg));
          server.bindSeat(conn, seat); // route this seat's decisions to conn (replays any pending)
          publishSeats();
        }
      }
      case "releaseSeat" -> {
        final String seat = optString(msg, "seat");
        final SeatPlan p = plan;
        if (seat != null && p != null && seat.equals(server.seatOf(conn))) {
          server.unbindSeat(conn, seat);
          p.release(seat);
          publishSeats();
        }
      }
      case "setSeatType" -> {
        final String seat = optString(msg, "seat");
        final String type = optString(msg, "playerType");
        final SeatPlan p = plan;
        if (seat != null && type != null && p != null && p.setAiType(seat, type)) {
          publishSeats();
        }
      }
      case "startGame" -> lifecycleExecutor.submit(this::doStartGame);
      case "resumeGame" -> lifecycleExecutor.submit(this::doResumeGame);
      case "newGame" -> lifecycleExecutor.submit(this::doReturnToSetup);
      default -> log.warn("Unknown control action: {}", action);
    }
  }

  /** Lifecycle thread: turn the seat plan into players and launch the game (SETUP → RUNNING). */
  private synchronized void doStartGame() {
    if (current != null) {
      return; // already running
    }
    final GameData data = gameData;
    final SeatPlan p = plan;
    if (data == null || p == null) {
      return;
    }
    server.resetForNewGame();
    final Session session = new Session();
    current = session;
    session.start(data, p);
    humanSeats = p.claimedSeatNames(); // the seats that became WebPlayers — rejoinable mid-game
    phase = "running";
    publishSeats(); // phase flips to "running" → client switches from seat-select to the game UI
    savedGameInfo = null;
    log.info("Game launched ({})", gameXml.getFileName());
  }

  /** Lifecycle thread: load the autosave and launch it — the engine resumes from the saved step. */
  private synchronized void doResumeGame() {
    if (current != null) {
      return; // already running
    }
    final String slot = resumeSlot();
    final SeatPlan p = plan;
    if (slot == null || p == null) {
      return;
    }
    final Optional<GameData> loaded =
        saveStore.read(slot).flatMap(b -> GameDataManager.loadGame(new ByteArrayInputStream(b)));
    if (loaded.isEmpty()) {
      log.warn("Resume requested but no loadable save in slot '{}'", slot);
      return;
    }
    server.resetForNewGame();
    final Session session = new Session();
    current = session;
    // The seat plan's nations match the saved game (same map); buildPlayers binds humans/AI by
    // name.
    session.start(loaded.get(), p);
    humanSeats = p.claimedSeatNames();
    phase = "running";
    publishSeats();
    savedGameInfo = null;
    log.info("Resumed game from save '{}'", slot);
  }

  /** Lifecycle thread: stop the running game and return to a fresh setup (keeping seat choices). */
  private synchronized void doReturnToSetup() {
    final Session old = current;
    if (old != null) {
      old.stop();
      current = null;
    }
    enterSetup(plan);
    log.info("Returned to setup ({})", gameXml.getFileName());
  }

  /**
   * WebSocket thread (via the server): a seat's controlling connection dropped. In standalone setup
   * the seat is freed for re-claim; in a lobby game the seat stays assigned to its user (who may
   * reconnect with a fresh ticket) — we only re-publish so the roster's connected flag updates.
   */
  private synchronized void onSeatVacated(final String seat) {
    final SeatPlan p = plan;
    if (p == null) {
      return;
    }
    if (!lobbyMode) {
      p.release(seat);
    } else {
      pruneClosedConns();
    }
    publishSeats();
  }

  /**
   * WebSocket thread: a connection presented a connect-ticket. Verify it (HMAC, expiry, this game's
   * id, single-use nonce), then bind it to its assigned seat — replaying any pending decision, so a
   * mid-game reconnect resumes. An invalid/expired/replayed ticket closes the connection.
   */
  private synchronized void handleAuth(final WebSocket conn, final JsonObject msg) {
    if (!lobbyMode) {
      return; // standalone hotseat has no tickets
    }
    pruneClosedConns();
    final Optional<ConnectTicket.Payload> verified =
        ConnectTicket.verify(optString(msg, "ticket"), gameToken);
    if (verified.isEmpty()) {
      closeUnauthorized(conn);
      return;
    }
    final ConnectTicket.Payload p = verified.get();
    if (!gameId.equals(p.gameId()) || !usedNonces.add(p.nonce())) {
      closeUnauthorized(conn); // wrong game, or a replayed ticket
      return;
    }
    authedConns.add(conn);
    if (p.isHost()) {
      hostConns.add(conn);
    }
    final String seat = p.seat();
    final SeatPlan sp = plan;
    if (seat != null && sp != null && sp.hasSeat(seat)) {
      server.bindSeat(conn, seat); // route this seat's decisions here (replays any pending)
      log.info("Seat {} bound to {} via ticket", seat, p.displayName());
    }
    publishSeats();
    maybeAutoStart();
  }

  /** Lobby mode: arm a timer that closes a connection which never presents a valid ticket. */
  private void armAuthTimeout(final WebSocket conn) {
    authTimeout.schedule(
        () -> {
          if (conn.isOpen() && !authedConns.contains(conn)) {
            log.info("Closing unauthenticated connection {}", conn.getRemoteSocketAddress());
            closeUnauthorized(conn);
          }
        },
        AUTH_TIMEOUT_SECONDS,
        TimeUnit.SECONDS);
  }

  /**
   * Lobby mode: start once every assigned human seat has a live connection (or the host starts
   * early). Runs on the lifecycle thread; {@link #doStartGame}/{@link #doResumeGame} guard against
   * a double start, so multiple triggers are harmless.
   */
  private void maybeAutoStart() {
    if (!lobbyMode || current != null) {
      return;
    }
    final SeatPlan p = plan;
    if (p == null) {
      return;
    }
    final Set<String> humans = p.humanSeatNames();
    if (!humans.isEmpty() && server.connectedSeats().containsAll(humans)) {
      lifecycleExecutor.submit(this::doLobbyStart);
    }
  }

  /** Lobby mode: resume from the save if this game has one, else start fresh. */
  private synchronized void doLobbyStart() {
    if (savedGameInfo != null) {
      doResumeGame();
    } else {
      doStartGame();
    }
  }

  private void closeUnauthorized(final WebSocket conn) {
    authedConns.remove(conn);
    hostConns.remove(conn);
    conn.close(WS_CLOSE_UNAUTHORIZED, "Authentication required");
  }

  private void pruneClosedConns() {
    authedConns.removeIf(c -> !c.isOpen());
    hostConns.removeIf(c -> !c.isOpen());
  }

  private void publishSeats() {
    final SeatPlan p = plan;
    if (p == null) {
      return;
    }
    final JsonObject env = new JsonObject();
    env.addProperty("type", "seats");
    env.add(
        "roster",
        GSON.toJsonTree(p.toRoster(phase, savedGameInfo, humanSeats, server.connectedSeats())));
    server.publishSeats(GSON.toJson(env));
  }

  /**
   * Load any existing save only to read its round/step for the resume option; data is discarded.
   */
  private @Nullable SeatRoster.SavedGame peekSave(final @Nullable String slot) {
    if (slot == null) {
      return null;
    }
    return saveStore
        .read(slot)
        .flatMap(b -> GameDataManager.loadGame(new ByteArrayInputStream(b)))
        .map(
            s ->
                new SeatRoster.SavedGame(
                    s.getSequence().getRound(), s.getSequence().getStep().getDisplayName()))
        .orElse(null);
  }

  /** Broadcast the map's notes (the {@code <property name="notes">} HTML in the game XML). */
  private void publishNotes() {
    final GameData data = gameData;
    if (data == null) {
      return;
    }
    final JsonObject envelope = new JsonObject();
    envelope.addProperty("type", "notes");
    envelope.addProperty("html", data.getProperties().get("notes", ""));
    server.publishNotes(GSON.toJson(envelope));
  }

  /** Evaluate national objectives against the given state and broadcast them (read-only). */
  private void publishObjectives(final GameData data) {
    final JsonObject envelope = new JsonObject();
    envelope.addProperty("type", "objectives");
    envelope.add("items", GSON.toJsonTree(ObjectivesProjector.project(data, objectivesProps)));
    server.publishObjectives(GSON.toJson(envelope));
  }

  /**
   * Persist the current game state to the save slot (autosave). Called on the game-loop thread
   * between steps, where the {@code GameData} is quiescent — {@code toBytes()} produces a
   * forSaveGame() blob (delegates + history) that resumes mid-game. Crash recovery loses at most an
   * in-progress (un-committed) step, never a completed one. I/O errors are swallowed by the store.
   */
  private void autosave(final GameData data) {
    final String slot = saveSlot;
    if (slot != null) {
      saveStore.write(slot, data.toBytes());
      reportTurn(data, slot);
    }
  }

  /**
   * Tell the control plane a step committed: the round/power/phase and the save slot now holding
   * it.
   */
  private void reportTurn(final GameData data, final String slot) {
    final var step = data.getSequence().getStep();
    final String power = step.getPlayerId() == null ? null : step.getPlayerId().getName();
    reporter.turnCommitted(data.getSequence().getRound(), power, step.getDisplayName(), slot);
  }

  /** A filesystem-safe save slot from the game name. */
  private static String slotFor(final @Nullable String gameName) {
    return gameName == null ? "autosave" : gameName.replaceAll("[^a-zA-Z0-9._-]", "_");
  }

  /** A display label for a claiming connection: the client's chosen name, else a fallback. */
  private static String ownerLabel(final WebSocket conn, final JsonObject msg) {
    final String name = optString(msg, "name");
    if (name != null && !name.isBlank()) {
      return name;
    }
    return "Player " + conn.getRemoteSocketAddress();
  }

  private static @Nullable JsonObject tryParse(final String json) {
    try {
      return GSON.fromJson(json, JsonObject.class);
    } catch (final RuntimeException e) {
      return null; // not JSON
    }
  }

  private static @Nullable String optString(final @Nullable JsonObject o, final String key) {
    return o != null && o.has(key) && o.get(key).isJsonPrimitive()
        ? o.get(key).getAsString()
        : null;
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

  /** One running game instance: its bridge, display, engine, and the loop thread that steps it. */
  private final class Session {
    private final WebDecisionBridge bridge =
        new WebDecisionBridge(server::sendToSeat, server::publishState);
    private final WebDisplay display = new WebDisplay(server::publishBattleEvent);
    private volatile boolean alive = true;
    private Thread thread;
    private ServerGame game;

    void start(final GameData data, final SeatPlan seatPlan) {
      final Set<Player> players = seatPlan.buildPlayers(bridge);
      game = WebGameHost.launch(data, players, display);
      display.setGameData(game.getData()); // enables battle-by-id round/force lookups
      game.setStopGameOnDelegateExecutionStop(true);
      thread = new Thread(this::run, "web-game-loop");
      thread.setDaemon(true);
      thread.start();
    }

    private void run() {
      try {
        // Resume a loaded game (runs the saved step) or, for a fresh game, set the resume flag and
        // start persistent delegates. Our loop steps via runNextStep(), so the engine's own
        // startGame() — which normally does this — never runs; we do it here instead.
        game.setUpGameForRunningSteps();
        reporter.gameStarted();
        publishStep(); // initial state — skipped if we are starting on a silent setup step
        int steps = 0;
        while (alive
            && !game.isGameOver()
            && game.getData().getSequence().getRound() <= maxRounds
            && steps < STEP_SAFETY_LIMIT) {
          game.runNextStep();
          if (!alive) {
            break;
          }
          // Fast-forward the no-op setup steps (game init, 0-value bids) without surfacing them.
          if (isSilentStep()) {
            continue;
          }
          publishStep();
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
        // alive is still true only when the loop ended on its own (game over / round limit), not
        // when a reset stopped it — report completion only in that natural-end case.
        if (alive) {
          reporter.gameFinished();
        }
      }
    }

    /** Publish the current state + objectives and autosave — unless this is a silent setup step. */
    private void publishStep() {
      if (isSilentStep()) {
        return;
      }
      server.publishState(GSON.toJson(StateProjector.project(game.getData())));
      publishObjectives(game.getData());
      autosave(game.getData()); // flush after every committed step (glacial turn rate → cheap)
    }

    /**
     * Setup steps to run but not surface: game initialization, and a bid / bid-placement step whose
     * player has no bid. Bidding is off by default (every bid is 0), so its steps are no-ops we
     * fast-forward; a non-zero bid (once bidding becomes a setup option) is a real turn and is
     * published normally.
     */
    private boolean isSilentStep() {
      final GameStep step = game.getData().getSequence().getStep();
      return switch (step.getDelegateName()) {
        case "initDelegate" -> true;
        case "bid", "placeBid" -> {
          final GamePlayer player = step.getPlayerId();
          yield player == null || !BidPurchaseDelegate.doesPlayerHaveBid(game.getData(), player);
        }
        default -> false;
      };
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
