package org.triplea.web.server.game;

import java.net.InetSocketAddress;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * Broadcasts game-state snapshots to read-only spectator clients over WebSocket. New clients
 * immediately receive the latest snapshot so a late joiner sees current state, not a blank map.
 */
@Slf4j
public final class SpectatorWebSocketServer extends WebSocketServer {
  private volatile String latestJson;

  public SpectatorWebSocketServer(final int port) {
    super(new InetSocketAddress(port));
    setReuseAddr(true);
  }

  /** Stores the snapshot as the catch-up state and broadcasts it to all connected clients. */
  public void publish(final String json) {
    latestJson = json;
    broadcast(json);
  }

  @Override
  public void onOpen(final WebSocket conn, final ClientHandshake handshake) {
    log.info("Spectator connected: {}", conn.getRemoteSocketAddress());
    final String snapshot = latestJson;
    if (snapshot != null) {
      conn.send(snapshot);
    }
  }

  @Override
  public void onClose(
      final WebSocket conn, final int code, final String reason, final boolean remote) {
    log.info("Spectator disconnected: {}", conn.getRemoteSocketAddress());
  }

  @Override
  public void onMessage(final WebSocket conn, final String message) {
    // Spectators are read-only in Phase 2; ignore inbound messages.
  }

  @Override
  public void onError(final WebSocket conn, final Exception ex) {
    log.error("Spectator WebSocket error", ex);
  }

  @Override
  public void onStart() {
    log.info("Spectator WebSocket server started on port {}", getPort());
  }
}
