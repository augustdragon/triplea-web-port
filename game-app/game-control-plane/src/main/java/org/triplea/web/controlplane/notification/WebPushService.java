package org.triplea.web.controlplane.notification;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends a single VAPID-authenticated, {@code aes128gcm}-encrypted Web Push to a subscription's
 * endpoint via the JDK {@link HttpClient}. Stateless and thread-safe; callers run it off the request
 * thread.
 *
 * <p>The result tells the caller whether to prune: {@link Result#GONE} means the push service says
 * the subscription no longer exists (404/410) and its row should be deleted.
 */
@Slf4j
public final class WebPushService {

  /** Outcome of a send attempt. */
  public enum Result {
    /** Accepted by the push service (2xx). */
    OK,
    /** The subscription is gone (404/410) — the caller should delete it. */
    GONE,
    /** Any other failure (network error, 4xx/5xx) — transient; left in place. */
    FAILED
  }

  // The push service stores the message this long if the device is offline. A "your turn" alert in
  // correspondence play stays relevant for days, so keep it generous (and below common caps).
  static final int DEFAULT_TTL_SECONDS = (int) Duration.ofDays(2).toSeconds();

  private final VapidKeys vapidKeys;
  private final String subject;
  private final HttpClient httpClient;

  public WebPushService(final VapidKeys vapidKeys, final String subject) {
    this(vapidKeys, subject, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
  }

  WebPushService(final VapidKeys vapidKeys, final String subject, final HttpClient httpClient) {
    this.vapidKeys = vapidKeys;
    this.subject = subject;
    this.httpClient = httpClient;
  }

  /** Encrypt {@code payload} for {@code subscription} and POST it to the push endpoint. */
  public Result send(final PushSubscription subscription, final String payload) {
    final byte[] body;
    try {
      body =
          WebPushCrypto.encrypt(
              payload.getBytes(StandardCharsets.UTF_8),
              EcUtil.B64URL_DEC.decode(subscription.p256dh()),
              EcUtil.B64URL_DEC.decode(subscription.auth()));
    } catch (final RuntimeException e) {
      // A malformed subscription (bad keys) will never succeed — treat it as gone so it's pruned.
      log.warn("Dropping push subscription with unusable keys: {}", e.getMessage());
      return Result.GONE;
    }

    final URI endpoint = URI.create(subscription.endpoint());
    final HttpRequest request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(15))
            .header("TTL", Integer.toString(DEFAULT_TTL_SECONDS))
            .header("Content-Encoding", "aes128gcm")
            .header("Content-Type", "application/octet-stream")
            .header("Urgency", "high")
            .header("Authorization", vapidKeys.authorizationHeader(origin(endpoint), subject))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    try {
      final HttpResponse<Void> response =
          httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      final int status = response.statusCode();
      if (status == 404 || status == 410) {
        return Result.GONE;
      }
      if (status >= 200 && status < 300) {
        return Result.OK;
      }
      log.warn("Push to {} returned HTTP {}", endpoint.getHost(), status);
      return Result.FAILED;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return Result.FAILED;
    } catch (final java.io.IOException e) {
      log.warn("Push to {} failed: {}", endpoint.getHost(), e.getMessage());
      return Result.FAILED;
    }
  }

  /** The push endpoint's origin (scheme://host[:port]) — the VAPID JWT {@code aud}. */
  private static String origin(final URI endpoint) {
    return endpoint.getScheme() + "://" + endpoint.getAuthority();
  }
}
