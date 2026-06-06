package org.triplea.web.controlplane.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.triplea.web.controlplane.config.ControlPlaneConfig;

/**
 * Validates the id_token claim handling — the part that decides who gets in. The signature is not
 * re-verified (the token comes straight from Google's token endpoint over TLS), so the guards that
 * matter are issuer, audience, and email_verified; these tests pin them.
 */
class GoogleOAuthControllerTest {

  private static final String CLIENT_ID = "test-client.apps.googleusercontent.com";

  private final GoogleOAuthController controller = new GoogleOAuthController(null, null, config());

  private static ControlPlaneConfig config() {
    final Map<String, String> env = new HashMap<>();
    env.put("CONTROL_PLANE_DB_PASSWORD", "x");
    env.put("CONTROL_PLANE_JWT_SECRET", "abcdefghijklmnopqrstuvwxyz0123456789");
    env.put("CONTROL_PLANE_OAUTH_GOOGLE_CLIENT_ID", CLIENT_ID);
    env.put("CONTROL_PLANE_OAUTH_GOOGLE_CLIENT_SECRET", "secret");
    env.put("CONTROL_PLANE_PUBLIC_URL", "https://triplea.example.com");
    return ControlPlaneConfig.fromEnv(env);
  }

  // The signature is irrelevant (decode-only), so any key works for building a test token.
  private static String token(
      final String issuer,
      final String audience,
      final String subject,
      final String email,
      final Boolean emailVerified) {
    var builder = JWT.create().withIssuer(issuer).withAudience(audience).withSubject(subject);
    if (email != null) {
      builder = builder.withClaim("email", email).withClaim("name", "Carol");
    }
    if (emailVerified != null) {
      builder = builder.withClaim("email_verified", emailVerified);
    }
    return builder.sign(Algorithm.HMAC256("irrelevant-test-key-1234567890"));
  }

  @Test
  void acceptsValidGoogleTokenWithVerifiedEmail() {
    final Optional<Identity> id =
        controller.identityFromIdToken(
            token("https://accounts.google.com", CLIENT_ID, "117-sub", "carol@example.com", true));

    assertThat(id.isPresent(), is(true));
    assertThat(id.get().provider(), is("google"));
    assertThat(id.get().subject(), is("117-sub"));
    assertThat(id.get().email(), is("carol@example.com"));
    assertThat(id.get().displayName(), is("Carol"));
  }

  @Test
  void dropsEmailWhenNotVerified() {
    final Optional<Identity> id =
        controller.identityFromIdToken(
            token("https://accounts.google.com", CLIENT_ID, "117-sub", "carol@example.com", false));

    assertThat(id.isPresent(), is(true));
    assertThat(
        id.get().email(), is(nullValue())); // unverified email is not trusted for allow-listing
  }

  @Test
  void rejectsWrongAudience() {
    final Optional<Identity> id =
        controller.identityFromIdToken(
            token(
                "https://accounts.google.com", "someone-elses-client", "117-sub", "c@x.com", true));

    assertThat(id.isEmpty(), is(true));
  }

  @Test
  void rejectsWrongIssuer() {
    final Optional<Identity> id =
        controller.identityFromIdToken(
            token("https://evil.example.com", CLIENT_ID, "117-sub", "c@x.com", true));

    assertThat(id.isEmpty(), is(true));
  }

  @Test
  void rejectsUnparseableToken() {
    assertThat(controller.identityFromIdToken("not-a-jwt").isEmpty(), is(true));
  }
}
