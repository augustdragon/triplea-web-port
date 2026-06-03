package org.triplea.web.controlplane.lobby;

import io.javalin.websocket.WsContext;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Tracks connected lobby WebSocket clients and pushes the current table list to them: a snapshot on
 * connect, and a fresh snapshot to everyone after any lobby mutation (wired as the {@link
 * LobbyController.ChangeListener}). Each message is {@code {type:"lobby", tables:[...]}} — the same
 * {@link LobbyTable} shape the REST endpoints return, so the client has one renderer.
 */
public final class LobbyBroadcaster {

  /** WebSocket envelope carrying the full lobby table list. */
  public record LobbyState(String type, List<LobbyTable> tables) {}

  private final Set<WsContext> sessions = new CopyOnWriteArraySet<>();
  private final LobbyDao dao;

  public LobbyBroadcaster(final LobbyDao dao) {
    this.dao = dao;
  }

  /** Register a freshly-authenticated client and send it the current snapshot. */
  public void add(final WsContext ctx) {
    sessions.add(ctx);
    ctx.send(snapshot());
  }

  public void remove(final WsContext ctx) {
    sessions.remove(ctx);
  }

  /** Push the current lobby state to every open session. */
  public void broadcast() {
    final LobbyState state = snapshot();
    for (final WsContext ctx : sessions) {
      if (ctx.session.isOpen()) {
        ctx.send(state);
      }
    }
  }

  /**
   * Keepalive: send a tiny {@code {type:"ping"}} to every session (the client ignores it) so idle
   * connections don't get dropped by an intermediary, and prune any that have died — the send fails
   * or the session is closed. Scheduled periodically by {@code ControlPlaneMain}.
   */
  public void pingAll() {
    for (final WsContext ctx : sessions) {
      if (!ctx.session.isOpen()) {
        sessions.remove(ctx);
        continue;
      }
      try {
        ctx.send(new Ping("ping"));
      } catch (final RuntimeException e) {
        sessions.remove(ctx);
      }
    }
  }

  /** A no-op keepalive envelope; the client ignores any non-{@code "lobby"} message. */
  private record Ping(String type) {}

  private LobbyState snapshot() {
    return new LobbyState("lobby", dao.listTables());
  }
}
