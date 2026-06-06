package org.triplea.web.controlplane.notification;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * The server's VAPID identity (RFC 8292): a P-256 key pair whose public half the browser uses as
 * the {@code applicationServerKey} when subscribing, and whose private half signs the short-lived
 * JWT that authenticates each push to the push service.
 *
 * <p>Keys are configured as base64url strings (public = 65-byte uncompressed point, private = raw
 * 32-byte scalar) — the same encoding the {@code web-push generate-vapid-keys} CLI and the browser
 * use. {@link #generate()} mints a fresh pair for first-time setup.
 */
public final class VapidKeys {

  // The VAPID JWT must expire within 24h (RFC 8292 §2); 12h gives comfortable clock-skew margin.
  private static final Duration JWT_LIFETIME = Duration.ofHours(12);

  private final String publicKeyBase64;
  private final Algorithm algorithm;

  private VapidKeys(final String publicKeyBase64, final Algorithm algorithm) {
    this.publicKeyBase64 = publicKeyBase64;
    this.algorithm = algorithm;
  }

  /** Load from configured base64url keys (as produced by {@link #generate()}). */
  public static VapidKeys fromConfig(final String publicKeyBase64, final String privateKeyBase64) {
    final ECPublicKey publicKey =
        EcUtil.publicKeyFromPoint(EcUtil.B64URL_DEC.decode(publicKeyBase64));
    final ECPrivateKey privateKey =
        EcUtil.privateKeyFromScalar(EcUtil.B64URL_DEC.decode(privateKeyBase64));
    return new VapidKeys(publicKeyBase64, Algorithm.ECDSA256(publicKey, privateKey));
  }

  /** The public key (base64url) to hand the browser as its {@code applicationServerKey}. */
  public String publicKeyBase64() {
    return publicKeyBase64;
  }

  /**
   * Build the {@code Authorization: vapid t=<jwt>,k=<key>} header for a push request. {@code
   * audience} is the origin (scheme://host[:port]) of the push endpoint; {@code subject} is the
   * configured {@code mailto:}/{@code https:} contact.
   */
  public String authorizationHeader(final String audience, final String subject) {
    final Instant now = Instant.now();
    final String token =
        JWT.create()
            .withAudience(audience)
            .withSubject(subject)
            .withExpiresAt(Date.from(now.plus(JWT_LIFETIME)))
            .sign(algorithm);
    return "vapid t=" + token + ",k=" + publicKeyBase64;
  }

  /** Generate a fresh VAPID key pair as {@code [publicBase64Url, privateBase64Url]}. */
  public static String[] generate() {
    final KeyPair keyPair = EcUtil.generateKeyPair();
    return new String[] {
      EcUtil.B64URL.encodeToString(EcUtil.encodePoint((ECPublicKey) keyPair.getPublic())),
      EcUtil.B64URL.encodeToString(EcUtil.encodeScalar((ECPrivateKey) keyPair.getPrivate()))
    };
  }
}
