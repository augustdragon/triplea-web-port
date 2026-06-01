package org.triplea.web.server.game;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the seat routing/validation in {@link WebDecisionBridge}: a request is targeted at a seat,
 * and only a reply from that seat completes it — a cross-seat or spectator reply is rejected. This
 * is the core safety property of multiplayer seat play (the engine drives a single game-loop
 * thread, so at most one decision is outstanding, but it may target a non-current seat).
 *
 * <p>{@code await} blocks the calling thread, so each test issues it on a background thread and
 * drives replies from the test thread once the request envelope has been captured by the sender.
 */
class WebDecisionBridgeTest {
  private final Gson gson = new Gson();
  // seat -> latest request envelope captured from the seat sender.
  private final Map<String, String> sent = new ConcurrentHashMap<>();
  private WebDecisionBridge bridge;

  @BeforeEach
  void setUp() {
    sent.clear();
    bridge = new WebDecisionBridge(sent::put, snapshot -> {});
  }

  /**
   * Issue a request for {@code seat} on a background thread; the future carries the await result.
   */
  private CompletableFuture<JsonObject> awaitOn(final String seat) {
    final CompletableFuture<JsonObject> result = new CompletableFuture<>();
    final Thread t =
        new Thread(
            () -> {
              try {
                result.complete(bridge.await(seat, "test", Map.of("q", 1)));
              } catch (final RuntimeException e) {
                result.completeExceptionally(e);
              }
            });
    t.setDaemon(true);
    t.start();
    return result;
  }

  /** Spin until the sender has captured a request for {@code seat}; return its envelope JSON. */
  private String waitForEnvelope(final String seat) throws InterruptedException {
    for (int i = 0; i < 400 && !sent.containsKey(seat); i++) {
      Thread.sleep(5);
    }
    final String env = sent.get(seat);
    assertNotNull(env, "a request envelope should have been sent to seat " + seat);
    return env;
  }

  private String requestIdFor(final String seat) throws InterruptedException {
    return gson.fromJson(waitForEnvelope(seat), JsonObject.class).get("requestId").getAsString();
  }

  private String decision(final String requestId, final Object payload) {
    final JsonObject m = new JsonObject();
    m.addProperty("type", "decision");
    m.addProperty("requestId", requestId);
    m.add("payload", gson.toJsonTree(payload));
    return gson.toJson(m);
  }

  @Test
  void requestEnvelopeCarriesSeatTypeAndKind() throws Exception {
    awaitOn("Chinese");
    final JsonObject env = gson.fromJson(waitForEnvelope("Chinese"), JsonObject.class);
    assertEquals("request", env.get("type").getAsString());
    assertEquals("Chinese", env.get("seat").getAsString());
    assertEquals("test", env.get("kind").getAsString());
  }

  @Test
  void correctSeatReplyCompletesAwait() throws Exception {
    final CompletableFuture<JsonObject> result = awaitOn("Japanese");
    final String id = requestIdFor("Japanese");
    assertTrue(bridge.onClientMessage("Japanese", decision(id, Map.of("ok", true))));
    assertTrue(result.get(2, SECONDS).get("ok").getAsBoolean());
  }

  @Test
  void wrongSeatReplyIsRejectedThenRightSeatSucceeds() throws Exception {
    final CompletableFuture<JsonObject> result = awaitOn("Japanese");
    final String id = requestIdFor("Japanese");
    // Another seat tries to answer Japan's decision — rejected, the engine stays parked.
    assertFalse(bridge.onClientMessage("Americans", decision(id, Map.of("ok", true))));
    assertFalse(result.isDone(), "await must stay parked after a cross-seat reply");
    // The rightful seat answers the same request — accepted.
    assertTrue(bridge.onClientMessage("Japanese", decision(id, Map.of("ok", true))));
    assertTrue(result.get(2, SECONDS).get("ok").getAsBoolean());
  }

  @Test
  void spectatorReplyIsRejected() throws Exception {
    final CompletableFuture<JsonObject> result = awaitOn("Japanese");
    final String id = requestIdFor("Japanese");
    assertFalse(bridge.onClientMessage(null, decision(id, Map.of("ok", true))));
    assertFalse(result.isDone());
  }

  @Test
  void unknownRequestIdIsRejected() {
    assertFalse(bridge.onClientMessage("Japanese", decision("no-such-id", Map.of())));
  }

  @Test
  void nonDecisionMessageIsIgnored() {
    assertFalse(
        bridge.onClientMessage("Japanese", "{\"type\":\"control\",\"action\":\"newGame\"}"));
    assertFalse(bridge.onClientMessage("Japanese", "not json at all"));
  }

  @Test
  void closeUnparksAwait() throws Exception {
    final CompletableFuture<JsonObject> result = awaitOn("Japanese");
    requestIdFor("Japanese");
    bridge.close();
    assertThrows(ExecutionException.class, () -> result.get(2, SECONDS));
  }
}
