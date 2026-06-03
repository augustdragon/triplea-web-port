package org.triplea.web.controlplane.auth;

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
 * A short-lived, single-use credential a browser presents to a game container's WebSocket to prove
 * which seat it was assigned. Minted by the control plane after the JWT + {@code isUserInGame}
 * checks, so the payload (seat, identity) is authored from the database and cannot be forged or
 * altered by the client.
 *
 * <p>Wire format (deliberately JDK-only on both sides — the container shares no JWT library):
 *
 * <pre>{@code b64url(payloadJson) + "." + b64url(HMAC-SHA256(gameToken, b64url(payloadJson)))}
 * </pre>
 *
 * The HMAC is keyed on the per-game token the container already has, so the container verifies the
 * signature locally with no round-trip and no JWT secret. The container must additionally enforce
 * single-use (track the {@code nonce}) and a matching {@code gameId}; this class only proves the
 * payload is authentic and unexpired. The verifier counterpart in {@code :game-web-server} parses
 * the same format. Keep the two in sync.
 */
public final class ConnectTicket {

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final Gson GSON = new Gson();
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  /**
   * The signed claims: the game, the assigned seat (null for a host-only/spectator), the user's
   * identity, the host flag, a single-use nonce, and an absolute expiry (epoch seconds).
   */
  public record Payload(
      String gameId,
      String seat,
      long userId,
      String displayName,
      boolean isHost,
      String nonce,
      long exp) {}

  private ConnectTicket() {}

  /** Sign a payload into a ticket string keyed on the game token. */
  public static String sign(final Payload payload, final String gameToken) {
    final String body =
        ENCODER.encodeToString(GSON.toJson(payload).getBytes(StandardCharsets.UTF_8));
    return body + "." + ENCODER.encodeToString(hmac(body, gameToken));
  }

  /**
   * Verify the signature and expiry, returning the payload if the ticket is authentic and
   * unexpired. Empty on any tampering, bad signature, expiry, or malformed input — callers never
   * see why.
   */
  public static Optional<Payload> verify(final String ticket, final String gameToken) {
    if (ticket == null) {
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
