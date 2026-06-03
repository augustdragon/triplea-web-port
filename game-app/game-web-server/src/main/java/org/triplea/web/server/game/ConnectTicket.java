package org.triplea.web.server.game;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Container-side verifier for the connect-ticket the control plane mints (see {@code
 * org.triplea.web.controlplane.auth.ConnectTicket} — keep the wire format in sync). A browser
 * presents the ticket as its first WebSocket message to prove which seat it was assigned; the
 * container verifies the HMAC locally with the shared game token it was spawned with — no JWT
 * secret and no round-trip to the control plane.
 *
 * <p>Wire format:
 *
 * <pre>{@code b64url(payloadJson) + "." + b64url(HMAC-SHA256(gameToken, b64url(payloadJson)))}
 * </pre>
 *
 * This proves the payload is authentic and unexpired; the caller must additionally enforce
 * single-use (track the {@code nonce}) and a matching {@code gameId}.
 */
final class ConnectTicket {

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final Gson GSON = new Gson();
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  /** The signed claims (mirror of the control-plane payload). */
  record Payload(
      String gameId,
      String seat,
      long userId,
      String displayName,
      boolean isHost,
      String nonce,
      long exp) {}

  private ConnectTicket() {}

  /** Sign a payload (used in tests; the control plane is the real signer in production). */
  static String sign(final Payload payload, final String gameToken) {
    final String body =
        ENCODER.encodeToString(GSON.toJson(payload).getBytes(StandardCharsets.UTF_8));
    return body + "." + ENCODER.encodeToString(hmac(body, gameToken));
  }

  /**
   * Verify signature + expiry; empty on any tampering, bad signature, expiry, or malformed input.
   */
  static Optional<Payload> verify(final String ticket, final String gameToken) {
    if (ticket == null || gameToken == null || gameToken.isBlank()) {
      return Optional.empty();
    }
    final int dot = ticket.indexOf('.');
    if (dot <= 0 || dot == ticket.length() - 1) {
      return Optional.empty();
    }
    final String body = ticket.substring(0, dot);
    final String sig = ticket.substring(dot + 1);
    try {
      if (!MessageDigest.isEqual(DECODER.decode(sig), hmac(body, gameToken))) {
        return Optional.empty();
      }
      final Payload payload =
          GSON.fromJson(new String(DECODER.decode(body), StandardCharsets.UTF_8), Payload.class);
      if (payload == null || payload.exp() < Instant.now().getEpochSecond()) {
        return Optional.empty();
      }
      return Optional.of(payload);
    } catch (final IllegalArgumentException | JsonSyntaxException e) {
      return Optional.empty();
    }
  }

  private static byte[] hmac(final String body, final String gameToken) {
    try {
      final Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(gameToken.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
    } catch (final java.security.GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }
}
