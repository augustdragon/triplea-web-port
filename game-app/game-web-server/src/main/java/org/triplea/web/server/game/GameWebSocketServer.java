package org.triplea.web.server.game;

import java.net.InetSocketAddress;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * The web client's WebSocket endpoint. Carries two message directions under a {@code type}
 * envelope:
 *
 * <ul>
 *   <li>server → client {@code {type:"state",snapshot:{...}}} — the latest game state (broadcast
 *       after each engine step; the most recent is re-sent to late joiners as catch-up);
 *   <li>server → client {@code {type:"request",requestId,kind,payload}} — a decision the active
 *       human seat must make (sent via {@link #send});
 *   <li>client → server {@code {type:"decision",requestId,payload}} — the reply, handed to the
 *       inbound handler (a {@link WebDecisionBridge}).
 * </ul>
 *
 * <p>Used by both the spectator and the playable runners. Spectators simply never get requests and
 * their replies (if any) match no pending decision.
 */
@Slf4j
public final class GameWebSocketServer extends WebSocketServer {
  private volatile @Nullable String latestState;
  private volatile @Nullable String pendingRequest;
  private volatile @Nullable Consumer<String> inboundHandler;

  public GameWebSocketServer(final int port) {
    super(new InetSocketAddress(port));
    setReuseAddr(true);
  }

  /** Routes raw inbound client messages here (e.g. {@code bridge::onClientMessage}). */
  public void setInboundHandler(final Consumer<String> handler) {
    this.inboundHandler = handler;
  }

  /** Wrap a state snapshot in a {@code state} envelope, store it for catch-up, and broadcast it. */
  public void publishState(final String snapshotJson) {
    final String envelope = "{\"type\":\"state\",\"snapshot\":" + snapshotJson + "}";
    latestState = envelope;
    broadcast(envelope);
  }

  /**
   * Broadcast a decision-request envelope and remember it as the outstanding request, so a client
   * connecting (or reconnecting) mid-decision is re-prompted instead of leaving the engine parked.
   */
  public void send(final String envelopeJson) {
    pendingRequest = envelopeJson;
    broadcast(envelopeJson);
  }

  @Override
  public void onOpen(final WebSocket conn, final ClientHandshake handshake) {
    log.info("Client connected: {}", conn.getRemoteSocketAddress());
    final String snapshot = latestState;
    if (snapshot != null) {
      conn.send(snapshot);
    }
    final String request = pendingRequest;
    if (request != null) {
      conn.send(request); // catch up a client that joined while a decision is outstanding
    }
  }

  @Override
  public void onClose(
      final WebSocket conn, final int code, final String reason, final boolean remote) {
    log.info("Client disconnected: {}", conn.getRemoteSocketAddress());
  }

  @Override
  public void onMessage(final WebSocket conn, final String message) {
    // A reply clears the outstanding request (single-decision hotseat); then route it to the
    // bridge.
    pendingRequest = null;
    final Consumer<String> handler = inboundHandler;
    if (handler != null) {
      handler.accept(message);
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
