package org.triplea.web.server.game;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * The web client's WebSocket endpoint. Carries messages under a {@code type} envelope:
 *
 * <ul>
 *   <li>server → client {@code {type:"state",snapshot:{...}}} — latest game state (broadcast);
 *   <li>server → client {@code {type:"seats",roster:{...}}} — the seat-assignment roster
 *       (broadcast, cached and re-sent on connect) — who has claimed what, and the AI type for
 *       unclaimed seats;
 *   <li>server → <b>one seat</b> {@code {type:"request",requestId,seat,kind,payload}} — a decision
 *       that seat must make, delivered only to the connection bound to it (via {@link
 *       #sendToSeat});
 *   <li>client → server {@code {type:"decision",requestId,payload}} — the reply, routed to the
 *       inbound handler tagged with the sending connection's seat;
 *   <li>client → server {@code {type:"control",action:...}} — setup actions (claim/release/start),
 *       also routed to the inbound handler.
 * </ul>
 *
 * <p><b>Seat routing.</b> A connection becomes a seat's controller via {@link #bindSeat} (driven by
 * the controller when it accepts a claim). The server tracks the two-way connection↔seat mapping so
 * it can deliver a seat's decision requests to exactly that connection and clean up on disconnect.
 * Unclaimed connections are spectators: they receive broadcasts but no requests, and any decision
 * reply they send is rejected downstream (no seat → no matching pending request).
 *
 * <p>Because the engine runs on one game-loop thread, at most one decision is outstanding at a
 * time; the single {@code pendingRequest{Seat,Envelope}} pair caches it so a (re)claiming
 * connection is caught up to an in-flight decision for its seat.
 */
@Slf4j
public final class GameWebSocketServer extends WebSocketServer {
  private volatile @Nullable String latestState;
  private volatile @Nullable String notesEnvelope;
  private volatile @Nullable String objectivesEnvelope;
  private volatile @Nullable String seatsEnvelope;

  // Battle-result events are transient (one per completed battle); cache a bounded history so a
  // reconnecting client (page reload) sees the full log, not just battles after it reconnected.
  private static final int MAX_BATTLE_LOG = 500;
  private final Deque<String> battleLog = new ArrayDeque<>();

  // The single outstanding decision (engine is single-threaded), cached for (re)claim catch-up.
  private volatile @Nullable String pendingRequestSeat;
  private volatile @Nullable String pendingRequestEnvelope;

  // Two-way connection↔seat binding for request routing. A seat maps to at most one live
  // connection (latest claim wins); a connection controls at most one seat.
  private final Map<WebSocket, String> seatByConn = new ConcurrentHashMap<>();
  private final Map<String, WebSocket> connBySeat = new ConcurrentHashMap<>();

  private volatile @Nullable BiConsumer<WebSocket, String> inboundHandler;
  private volatile @Nullable Consumer<String> seatVacatedHandler;
  private volatile @Nullable Consumer<WebSocket> connectHandler;

  public GameWebSocketServer(final int port) {
    super(new InetSocketAddress(port));
    setReuseAddr(true);
  }

  /** Routes raw inbound client messages here as {@code (connection, message)}. */
  public void setInboundHandler(final BiConsumer<WebSocket, String> handler) {
    this.inboundHandler = handler;
  }

  /** Called when a connection opens, after the cached envelopes are sent (for auth gating). */
  public void setConnectHandler(final Consumer<WebSocket> handler) {
    this.connectHandler = handler;
  }

  /** Names of seats with a live controlling connection (for the waiting room / drop detection). */
  public Set<String> connectedSeats() {
    return new HashSet<>(connBySeat.keySet());
  }

  /** Called with a seat name when its controlling connection drops, so the plan can free it. */
  public void setSeatVacatedHandler(final Consumer<String> handler) {
    this.seatVacatedHandler = handler;
  }

  /**
   * Bind {@code conn} as the controller of {@code seat} (the controller calls this when it accepts
   * a claim). Evicts any prior connection on that seat (latest claim wins → the old one becomes a
   * spectator) and any prior seat on that connection. If a decision for this seat is currently
   * outstanding, it is (re)sent to the newly-bound connection so a reconnecting player resumes
   * mid-decision.
   */
  public void bindSeat(final WebSocket conn, final String seat) {
    final String priorSeatOfConn = seatByConn.put(conn, seat);
    if (priorSeatOfConn != null && !priorSeatOfConn.equals(seat)) {
      connBySeat.remove(priorSeatOfConn, conn);
    }
    final WebSocket priorConnOfSeat = connBySeat.put(seat, conn);
    if (priorConnOfSeat != null && priorConnOfSeat != conn) {
      seatByConn.remove(priorConnOfSeat);
    }
    final String pendingSeat = pendingRequestSeat;
    final String pendingEnvelope = pendingRequestEnvelope;
    if (pendingEnvelope != null && seat.equals(pendingSeat) && conn.isOpen()) {
      conn.send(pendingEnvelope);
    }
  }

  /** Release {@code conn}'s binding to {@code seat} (an explicit setup-phase release). */
  public void unbindSeat(final WebSocket conn, final String seat) {
    if (seat.equals(seatByConn.get(conn))) {
      seatByConn.remove(conn);
      connBySeat.remove(seat, conn);
    }
  }

  /** The seat {@code conn} currently controls, or {@code null} if it is a spectator. */
  public @Nullable String seatOf(final WebSocket conn) {
    return seatByConn.get(conn);
  }

  /**
   * Send a decision-request envelope to the connection bound to {@code seat}, and remember it as
   * the outstanding request so a connection (re)claiming that seat is caught up. If no connection
   * is currently bound, the request is buffered until one claims the seat.
   */
  public void sendToSeat(final String seat, final String envelopeJson) {
    pendingRequestSeat = seat;
    pendingRequestEnvelope = envelopeJson;
    final WebSocket conn = connBySeat.get(seat);
    if (conn != null && conn.isOpen()) {
      conn.send(envelopeJson);
    }
  }

  /** Clear the outstanding-request cache once a seat's decision has been answered. */
  public void clearPendingRequest() {
    pendingRequestSeat = null;
    pendingRequestEnvelope = null;
  }

  /**
   * Drop per-game caches when a new game (or a return to setup) is starting, so a (re)connecting
   * client isn't caught up to a finished game. Seat bindings are intentionally kept — the same
   * players keep their seats across a reset.
   */
  public void resetForNewGame() {
    latestState = null;
    clearPendingRequest();
    synchronized (battleLog) {
      battleLog.clear();
    }
  }

  /** Wrap a state snapshot in a {@code state} envelope, store it for catch-up, and broadcast it. */
  public void publishState(final String snapshotJson) {
    final String envelope = "{\"type\":\"state\",\"snapshot\":" + snapshotJson + "}";
    latestState = envelope;
    broadcast(envelope);
  }

  /** Broadcast (and cache) the seat roster — re-sent to every client on connect. */
  public void publishSeats(final String envelopeJson) {
    seatsEnvelope = envelopeJson;
    broadcast(envelopeJson);
  }

  /** Broadcast (and cache) the game's notes envelope; re-sent to every client on connect. */
  public void publishNotes(final String envelopeJson) {
    notesEnvelope = envelopeJson;
    broadcast(envelopeJson);
  }

  /** Broadcast (and cache) the national-objectives envelope; re-sent to clients on connect. */
  public void publishObjectives(final String envelopeJson) {
    objectivesEnvelope = envelopeJson;
    broadcast(envelopeJson);
  }

  /**
   * Broadcast a {@code {type:"battle",...}} event and cache it (bounded) for reconnect catch-up.
   */
  public void publishBattleEvent(final String envelopeJson) {
    synchronized (battleLog) {
      battleLog.addLast(envelopeJson);
      while (battleLog.size() > MAX_BATTLE_LOG) {
        battleLog.removeFirst();
      }
    }
    broadcast(envelopeJson);
  }

  /** A snapshot copy of the cached battle-log envelopes, oldest first. */
  List<String> snapshotBattleLog() {
    synchronized (battleLog) {
      return new ArrayList<>(battleLog);
    }
  }

  @Override
  public void onOpen(final WebSocket conn, final ClientHandshake handshake) {
    log.info("Client connected: {}", conn.getRemoteSocketAddress());
    sendIfPresent(conn, seatsEnvelope); // the roster, so the client can pick/see seats
    sendIfPresent(conn, latestState);
    sendIfPresent(conn, notesEnvelope);
    sendIfPresent(conn, objectivesEnvelope);
    for (final String battle : snapshotBattleLog()) {
      conn.send(battle); // replay the battle log so a reconnecting client's log isn't empty
    }
    // No decision request here: the connection has not claimed a seat yet. bindSeat() replays any
    // outstanding request once it claims one.
    final Consumer<WebSocket> onConnect = connectHandler;
    if (onConnect != null) {
      onConnect.accept(conn); // lobby mode arms an auth timeout for this connection
    }
  }

  private static void sendIfPresent(final WebSocket conn, final @Nullable String message) {
    if (message != null) {
      conn.send(message);
    }
  }

  @Override
  public void onClose(
      final WebSocket conn, final int code, final String reason, final boolean remote) {
    log.info("Client disconnected: {}", conn.getRemoteSocketAddress());
    final String seat = seatByConn.remove(conn);
    if (seat != null) {
      connBySeat.remove(seat, conn);
      final Consumer<String> handler = seatVacatedHandler;
      if (handler != null) {
        handler.accept(seat); // let the controller free the seat in the plan + rebroadcast
      }
    }
  }

  @Override
  public void onMessage(final WebSocket conn, final String message) {
    final BiConsumer<WebSocket, String> handler = inboundHandler;
    if (handler != null) {
      handler.accept(conn, message);
    }
  }

  @Override
  public void onError(final WebSocket conn, final Exception ex) {
    log.error("WebSocket error", ex);
  }

  @Override
  public void onStart() {
    log.info("Game WebSocket server started on port {}", getPort());
  }
}
