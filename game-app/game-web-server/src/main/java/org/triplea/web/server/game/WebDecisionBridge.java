package org.triplea.web.server.game;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Parks the engine thread until the browser answers a decision — the web analog of the Swing
 * client's {@code CountDownLatch}/EDT handoff. A {@link WebPlayer} method calls {@link #await} on
 * the engine (game-loop) thread for <i>its own seat</i>: it sends a {@code {type:"request",...}}
 * envelope <b>targeted at that seat</b> and blocks on a {@link CompletableFuture} keyed by a {@code
 * requestId}. The WebSocket thread delivers the browser's {@code {type:"decision",...}} reply to
 * {@link #onClientMessage}, which completes the future — but only if the reply came from the
 * connection that owns the request's seat. A reply from any other seat (or a spectator) is
 * rejected.
 *
 * <p><b>Seat validation is not yet a security boundary.</b> Until auth lands (P4.3), the replying
 * seat is whatever connection the server has bound to a seat via a (trusted) claim. This guards
 * against <i>accidental</i> cross-seat answers and spectator interference, not deliberate
 * impersonation.
 *
 * <p>Because the engine runs on a single game-loop thread, at most one decision is ever outstanding
 * at a time — but it may target a non-current seat (e.g. the defender selecting casualties during
 * the attacker's turn), which is exactly why requests carry a seat and replies are validated.
 *
 * <p>Thread-safety: {@link #await} runs on the engine thread, {@link #onClientMessage} on the
 * WebSocket thread; the pending-future map is concurrent and the two never block each other.
 */
@Slf4j
public final class WebDecisionBridge {
  private final Gson gson = new Gson();

  /** Sends a request envelope to a specific seat's connection: {@code (seat, envelopeJson)}. */
  private final BiConsumer<String, String> seatSender;

  private final Consumer<String> statePublisher;
  private final Map<String, Pending> pending = new ConcurrentHashMap<>();
  private volatile boolean closed = false;

  /** A parked decision: the seat it was issued to, and the future the engine thread blocks on. */
  private record Pending(String seat, CompletableFuture<JsonObject> future) {}

  /**
   * @param seatSender routes a request-envelope JSON string to the connection bound to the given
   *     seat (e.g. {@code GameWebSocketServer::sendToSeat}).
   * @param statePublisher pushes a state-snapshot JSON (the raw snapshot; wrapped into a {@code
   *     state} envelope by the server) so the map can refresh between an engine step's individual
   *     decisions — e.g. after each accepted move, not just at step boundaries. Broadcast to all.
   */
  public WebDecisionBridge(
      final BiConsumer<String, String> seatSender, final Consumer<String> statePublisher) {
    this.seatSender = seatSender;
    this.statePublisher = statePublisher;
  }

  /**
   * Engine thread: send a decision request of {@code kind} carrying {@code payload} to {@code
   * seat}, then block until that seat replies. Returns the reply's {@code payload} object.
   *
   * @throws IllegalStateException if the bridge has been closed (game stopped) before/while
   *     waiting.
   */
  public JsonObject await(final String seat, final String kind, final Object payload) {
    if (closed) {
      throw new IllegalStateException("WebDecisionBridge is closed");
    }
    final String requestId = UUID.randomUUID().toString();
    final CompletableFuture<JsonObject> future = new CompletableFuture<>();
    pending.put(requestId, new Pending(seat, future));

    final JsonObject envelope = new JsonObject();
    envelope.addProperty("type", "request");
    envelope.addProperty("requestId", requestId);
    envelope.addProperty("seat", seat);
    envelope.addProperty("kind", kind);
    envelope.add("payload", gson.toJsonTree(payload));
    seatSender.accept(seat, gson.toJson(envelope));

    try {
      return future.get();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted awaiting browser decision", e);
    } catch (final ExecutionException e) {
      throw new IllegalStateException("Browser decision failed", e.getCause());
    } finally {
      pending.remove(requestId);
    }
  }

  /**
   * Engine thread: broadcast a fresh state snapshot mid-step (e.g. after an accepted move) so the
   * browser's map reflects the change immediately rather than waiting for the step to end. No-op
   * once closed.
   */
  public void publishState(final String snapshotJson) {
    if (!closed) {
      statePublisher.accept(snapshotJson);
    }
  }

  /**
   * WebSocket thread: route a raw inbound decision message that arrived on the connection bound to
   * {@code replyingSeat} (or {@code null} for an unbound/spectator connection). Completes the
   * matching request only if its target seat equals {@code replyingSeat}.
   *
   * @return {@code true} if a pending decision was completed (the reply was accepted); {@code
   *     false} if the message was not a decision, matched no pending request, or came from the
   *     wrong seat.
   */
  public boolean onClientMessage(final String replyingSeat, final String json) {
    final JsonObject msg;
    try {
      msg = gson.fromJson(json, JsonObject.class);
    } catch (final RuntimeException e) {
      log.warn("Ignoring unparseable client message", e);
      return false;
    }
    if (msg == null || !msg.has("type") || !"decision".equals(msg.get("type").getAsString())) {
      return false; // not a decision reply; ignore (e.g. client chatter)
    }
    final String requestId = msg.has("requestId") ? msg.get("requestId").getAsString() : null;
    final Pending p = requestId == null ? null : pending.get(requestId);
    if (p == null) {
      log.warn("No pending decision for requestId={}", requestId);
      return false;
    }
    if (!p.seat().equals(replyingSeat)) {
      // Cross-seat / spectator reply: a connection tried to answer a decision it doesn't own.
      log.warn(
          "Rejecting decision for seat '{}' from seat '{}' (requestId={})",
          p.seat(),
          replyingSeat,
          requestId);
      return false;
    }
    p.future().complete(msg.has("payload") ? msg.getAsJsonObject("payload") : new JsonObject());
    return true;
  }

  /** Unblock any parked engine thread when the game stops, so it can unwind cleanly. */
  public void close() {
    closed = true;
    pending
        .values()
        .forEach(p -> p.future().completeExceptionally(new IllegalStateException("game stopped")));
    pending.clear();
  }
}
