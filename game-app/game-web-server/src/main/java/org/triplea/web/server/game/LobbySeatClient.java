package org.triplea.web.server.game;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Fetches a launched game's seat→identity assignments from the control plane at boot, so the
 * container builds its seat plan from the lobby's authenticated assignments instead of trusting
 * client-supplied names. Service-to-service (JDK {@link HttpClient}, no extra dependency),
 * authenticated with the shared game token — the same token the {@link HttpGameReporter} uses.
 */
@Slf4j
final class LobbySeatClient {

  private static final Gson GSON = new Gson();
  private static final int MAX_ATTEMPTS = 5;
  private static final long RETRY_DELAY_MS = 500;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final URI endpoint;
  private final String token;

  LobbySeatClient(final String controlPlaneUrl, final String gameId, final String token) {
    this.endpoint =
        URI.create(controlPlaneUrl.replaceAll("/+$", "") + "/internal/games/" + gameId + "/seats");
    this.token = token;
  }

  /**
   * Fetch the seat assignments, retrying briefly (the control plane is already up — it spawned us —
   * but the network binding can race container startup). Returns an empty list if it ultimately
   * fails; the caller logs and the game simply has no pre-assigned human seats.
   */
  List<LobbySeat> fetch() {
    final HttpRequest request =
        HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(Duration.ofSeconds(5))
            .header("X-Game-Token", token)
            .GET()
            .build();
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        final HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
        if (response.statusCode() == 200) {
          final List<LobbySeat> seats =
              GSON.fromJson(response.body(), new TypeToken<List<LobbySeat>>() {}.getType());
          return seats == null ? List.of() : seats;
        }
        log.warn("Seat fetch attempt {} returned HTTP {}", attempt, response.statusCode());
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (final java.io.IOException e) {
        log.warn("Seat fetch attempt {} failed: {}", attempt, e.getMessage());
      }
      sleep();
    }
    log.error("Could not fetch seat assignments from {} after {} attempts", endpoint, MAX_ATTEMPTS);
    return List.of();
  }

  private static void sleep() {
    try {
      Thread.sleep(RETRY_DELAY_MS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
