package org.triplea.web.server.game;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Posts lifecycle + turn events to the control plane over HTTP (JDK {@link HttpClient}, no extra
 * dependency), authenticated with a shared game token. Sends are fire-and-forget async so a slow or
 * down control plane never stalls the game loop; failures are logged, not thrown. M5 will issue a
 * per-game token at spawn; for now a single shared token gates the internal endpoint.
 */
@Slf4j
final class HttpGameReporter implements GameReporter {

  private static final Gson GSON = new Gson();

  private final HttpClient http = HttpClient.newHttpClient();
  private final URI endpoint;
  private final String token;

  HttpGameReporter(final String controlPlaneUrl, final String gameId, final String token) {
    this.endpoint =
        URI.create(controlPlaneUrl.replaceAll("/+$", "") + "/internal/games/" + gameId + "/events");
    this.token = token;
  }

  @Override
  public void gameStarted() {
    post(event("started"));
  }

  @Override
  public void turnCommitted(
      final int round, final @Nullable String power, final String phase, final String bytesRef) {
    final JsonObject e = event("turn");
    e.addProperty("round", round);
    e.addProperty("power", power);
    e.addProperty("phase", phase);
    e.addProperty("bytesRef", bytesRef);
    post(e);
  }

  @Override
  public void gameFinished(final String reason, final @Nullable String winner) {
    final JsonObject e = event("finished");
    e.addProperty("reason", reason);
    e.addProperty("winner", winner);
    post(e);
  }

  @Override
  public void seatResigned(final String power) {
    final JsonObject e = event("resigned");
    e.addProperty("power", power);
    post(e);
  }

  @Override
  public void turnDeadline(final @Nullable String power, final @Nullable Long deadlineEpoch) {
    final JsonObject e = event("deadline");
    e.addProperty("power", power);
    e.addProperty("deadlineEpoch", deadlineEpoch);
    post(e);
  }

  @Override
  public void presence(final java.util.List<String> connectedPowers) {
    final JsonObject e = event("presence");
    e.add("powers", GSON.toJsonTree(connectedPowers));
    post(e);
  }

  private static JsonObject event(final String type) {
    final JsonObject e = new JsonObject();
    e.addProperty("type", type);
    return e;
  }

  private void post(final JsonObject body) {
    final HttpRequest request =
        HttpRequest.newBuilder()
            .uri(endpoint)
            .header("Content-Type", "application/json")
            .header("X-Game-Token", token)
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();
    http.sendAsync(request, BodyHandlers.discarding())
        .exceptionally(
            e -> {
              log.warn(
                  "Reporting '{}' to control plane failed: {}", body.get("type"), e.getMessage());
              return null;
            });
  }
}
