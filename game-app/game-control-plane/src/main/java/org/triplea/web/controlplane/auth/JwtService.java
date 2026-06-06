package org.triplea.web.controlplane.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Mints and verifies the stateless session JWT carried in the auth cookie. HMAC-signed with the
 * configured secret; the identity travels in the {@code sub} (subject) + {@code provider} + {@code
 * name} claims, with a short expiry. Verification is total — any tampering, wrong signature, or
 * expiry yields {@link Optional#empty()} rather than throwing, so callers map cleanly to 401.
 */
public final class JwtService {

  private static final String ISSUER = "triplea-control-plane";
  private static final String CLAIM_PROVIDER = "provider";
  private static final String CLAIM_NAME = "name";
  private static final String CLAIM_EMAIL = "email";

  private final Algorithm algorithm;
  private final JWTVerifier verifier;
  private final Duration ttl;

  public JwtService(final String secret, final int ttlMinutes) {
    this.algorithm = Algorithm.HMAC256(secret);
    this.verifier = JWT.require(algorithm).withIssuer(ISSUER).build();
    this.ttl = Duration.ofMinutes(ttlMinutes);
  }

  /** Sign a token for this identity, expiring after the configured TTL. */
  public String mint(final Identity identity) {
    final Instant now = Instant.now();
    final var builder =
        JWT.create()
            .withIssuer(ISSUER)
            .withSubject(identity.subject())
            .withClaim(CLAIM_PROVIDER, identity.provider())
            .withClaim(CLAIM_NAME, identity.displayName())
            .withIssuedAt(now)
            .withExpiresAt(now.plus(ttl));
    if (identity.email() != null) {
      builder.withClaim(CLAIM_EMAIL, identity.email());
    }
    return builder.sign(algorithm);
  }

  /** Verify signature + expiry and return the identity, or empty if the token is not valid. */
  public Optional<Identity> verify(final String token) {
    try {
      final DecodedJWT jwt = verifier.verify(token);
      final String provider = jwt.getClaim(CLAIM_PROVIDER).asString();
      final String subject = jwt.getSubject();
      if (provider == null || subject == null) {
        return Optional.empty();
      }
      return Optional.of(
          new Identity(
              provider,
              subject,
              jwt.getClaim(CLAIM_NAME).asString(),
              jwt.getClaim(CLAIM_EMAIL).asString()));
    } catch (final JWTVerificationException e) {
      return Optional.empty();
    }
  }

  /** Cookie max-age matching the token TTL. */
  public int ttlSeconds() {
    return (int) ttl.toSeconds();
  }
}
