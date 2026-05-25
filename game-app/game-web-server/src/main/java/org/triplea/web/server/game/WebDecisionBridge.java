package org.triplea.web.server.game;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Parks the engine thread until the browser answers a decision — the web analog of the Swing
 * client's {@code CountDownLatch}/EDT handoff. A {@link WebPlayer} method calls {@link #await} on
 * the engine (game-loop) thread: it sends a {@code {type:"request",...}} envelope to the browser
 * and blocks on a {@link CompletableFuture} keyed by a {@code requestId}. The WebSocket thread
 * delivers the browser's {@code {type:"decision",...}} reply to {@link #onClientMessage}, which
 * completes the future, waking the engine thread. Requests are id-keyed so this extends to multiple
 * seats later.
 *
 * <p>Thread-safety: {@link #await} runs on the engine thread, {@link #onClientMessage} on the
 * WebSocket thread; the pending-future map is concurrent and the two never block each other.
 */
@Slf4j
public final class WebDecisionBridge {
  private final Gson gson = new Gson();
  private final Consumer<String> sender;
  private final Consumer<String> statePublisher;
  private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
  private volatile boolean closed = false;

  /**
   * @param sender pushes a request-envelope JSON string to the browser (e.g. WS broadcast).
   * @param statePublisher pushes a state-snapshot JSON (the raw snapshot; wrapped into a {@code
   *     state} envelope by the server) so the map can refresh between an engine step's individual
   *     decisions — e.g. after each accepted move, not just at step boundaries.
   */
  public WebDecisionBridge(final Consumer<String> sender, final Consumer<String> statePublisher) {
    this.sender = sender;
    this.statePublisher = statePublisher;
  }

  /**
   * Engine thread: send a decision request of {@code kind} carrying {@code payload}, then block
   * until the browser replies. Returns the reply's {@code payload} object.
   *
   * @throws IllegalStateException if the bridge has been closed (game stopped) before/while
   *     waiting.
   */
  public JsonObject await(final String kind, final Object payload) {
    if (closed) {
      throw new IllegalStateException("WebDecisionBridge is closed");
    }
    final String requestId = UUID.randomUUID().toString();
    final CompletableFuture<JsonObject> future = new CompletableFuture<>();
    pending.put(requestId, future);

    final JsonObject envelope = new JsonObject();
    envelope.addProperty("type", "request");
    envelope.addProperty("requestId", requestId);
    envelope.addProperty("kind", kind);
    envelope.add("payload", gson.toJsonTree(payload));
    sender.accept(gson.toJson(envelope));

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

  /** WebSocket thread: route a raw inbound client message; completes the matching request. */
  public void onClientMessage(final String json) {
    final JsonObject msg;
    try {
      msg = gson.fromJson(json, JsonObject.class);
    } catch (final RuntimeException e) {
      log.warn("Ignoring unparseable client message", e);
      return;
    }
    if (msg == null || !msg.has("type") || !"decision".equals(msg.get("type").getAsString())) {
      return; // not a decision reply; ignore (e.g. client chatter)
    }
    final String requestId = msg.has("requestId") ? msg.get("requestId").getAsString() : null;
    final CompletableFuture<JsonObject> future = requestId == null ? null : pending.get(requestId);
    if (future == null) {
      log.warn("No pending decision for requestId={}", requestId);
      return;
    }
    future.complete(msg.has("payload") ? msg.getAsJsonObject("payload") : new JsonObject());
  }

  /** Unblock any parked engine thread when the game stops, so it can unwind cleanly. */
  public void close() {
    closed = true;
    pending
        .values()
        .forEach(f -> f.completeExceptionally(new IllegalStateException("game stopped")));
    pending.clear();
  }
}
