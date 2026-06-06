package org.triplea.web.controlplane.config;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ControlPlaneConfigTest {

  // A 36-char value satisfies the HS256 minimum-length requirement.
  private static final String VALID_SECRET = "abcdefghijklmnopqrstuvwxyz0123456789";

  /** A minimal valid environment; tests copy and tweak it. */
  private static Map<String, String> validEnv() {
    final Map<String, String> env = new HashMap<>();
    env.put("CONTROL_PLANE_DB_PASSWORD", "dbsecret");
    env.put("CONTROL_PLANE_JWT_SECRET", VALID_SECRET);
    return env;
  }

  @Test
  void failsFastWhenPasswordMissing() {
    final Map<String, String> env = validEnv();
    env.remove("CONTROL_PLANE_DB_PASSWORD");
    assertThat(
        assertThrows(IllegalStateException.class, () -> ControlPlaneConfig.fromEnv(env))
            .getMessage(),
        containsString("CONTROL_PLANE_DB_PASSWORD"));
  }

  @Test
  void failsFastWhenJwtSecretMissing() {
    final Map<String, String> env = validEnv();
    env.remove("CONTROL_PLANE_JWT_SECRET");
    assertThat(
        assertThrows(IllegalStateException.class, () -> ControlPlaneConfig.fromEnv(env))
            .getMessage(),
        containsString("CONTROL_PLANE_JWT_SECRET"));
  }

  @Test
  void failsFastWhenJwtSecretTooShort() {
    final Map<String, String> env = validEnv();
    env.put("CONTROL_PLANE_JWT_SECRET", "too-short");
    assertThat(
        assertThrows(IllegalStateException.class, () -> ControlPlaneConfig.fromEnv(env))
            .getMessage(),
        containsString("CONTROL_PLANE_JWT_SECRET"));
  }

  @Test
  void rejectsDevLoginInProd() {
    final Map<String, String> env = validEnv();
    env.put("CONTROL_PLANE_PROFILE", "prod");
    env.put("CONTROL_PLANE_DEV_LOGIN", "true");
    assertThat(
        assertThrows(IllegalStateException.class, () -> ControlPlaneConfig.fromEnv(env))
            .getMessage(),
        containsString("CONTROL_PLANE_DEV_LOGIN"));
  }

  @Test
  void appliesDevDefaults() {
    final ControlPlaneConfig config = ControlPlaneConfig.fromEnv(validEnv());

    assertThat(config.profile(), is("dev"));
    assertThat(config.devLoginEnabled(), is(false));
    assertThat(config.secureCookies(), is(false));
    assertThat(config.httpPort(), is(7000));
    assertThat(config.sessionTtlMinutes(), is(60));
    assertThat(config.allowList().isEmpty(), is(true));
  }

  @Test
  void parsesAllowListAndDevLogin() {
    final Map<String, String> env = validEnv();
    env.put("CONTROL_PLANE_DEV_LOGIN", "true");
    env.put("CONTROL_PLANE_ALLOWLIST", "google:alice, discord:bob ,, google:carol");

    final ControlPlaneConfig config = ControlPlaneConfig.fromEnv(env);

    assertThat(config.devLoginEnabled(), is(true));
    assertThat(
        config.allowList(), containsInAnyOrder("google:alice", "discord:bob", "google:carol"));
  }

  /**
   * A valid prod environment requires a real login path (OAuth) and a public URL for its redirect.
   */
  private static Map<String, String> validProdEnv() {
    final Map<String, String> env = validEnv();
    env.put("CONTROL_PLANE_PROFILE", "prod");
    env.put("CONTROL_PLANE_OAUTH_GOOGLE_CLIENT_ID", "client-id");
    env.put("CONTROL_PLANE_OAUTH_GOOGLE_CLIENT_SECRET", "client-secret");
    env.put("CONTROL_PLANE_PUBLIC_URL", "https://triplea.example.com");
    return env;
  }

  @Test
  void prodMarksCookiesSecure() {
    final ControlPlaneConfig config = ControlPlaneConfig.fromEnv(validProdEnv());

    assertThat(config.isProd(), is(true));
    assertThat(config.secureCookies(), is(true));
    assertThat(config.googleOAuthEnabled(), is(true));
  }

  @Test
  void rejectsProdWithoutOAuthLoginPath() {
    final Map<String, String> env = validEnv();
    env.put("CONTROL_PLANE_PROFILE", "prod");
    assertThat(
        assertThrows(IllegalStateException.class, () -> ControlPlaneConfig.fromEnv(env))
            .getMessage(),
        containsString("prod requires a real login path"));
  }

  @Test
  void rejectsOAuthClientIdWithoutSecret() {
    final Map<String, String> env = validEnv();
    env.put("CONTROL_PLANE_OAUTH_GOOGLE_CLIENT_ID", "client-id");
    assertThat(
        assertThrows(IllegalStateException.class, () -> ControlPlaneConfig.fromEnv(env))
            .getMessage(),
        containsString("CONTROL_PLANE_OAUTH_GOOGLE_CLIENT_ID"));
  }

  @Test
  void rejectsOAuthWithoutPublicUrl() {
    final Map<String, String> env = validEnv();
    env.put("CONTROL_PLANE_OAUTH_GOOGLE_CLIENT_ID", "client-id");
    env.put("CONTROL_PLANE_OAUTH_GOOGLE_CLIENT_SECRET", "client-secret");
    assertThat(
        assertThrows(IllegalStateException.class, () -> ControlPlaneConfig.fromEnv(env))
            .getMessage(),
        containsString("CONTROL_PLANE_PUBLIC_URL"));
  }

  @Test
  void buildsGoogleRedirectUriFromPublicUrl() {
    final ControlPlaneConfig config = ControlPlaneConfig.fromEnv(validProdEnv());
    assertThat(
        config.googleRedirectUri(), is("https://triplea.example.com/api/oauth/google/callback"));
  }

  @Test
  void rejectsNonNumericPort() {
    final Map<String, String> env = validEnv();
    env.put("CONTROL_PLANE_HTTP_PORT", "not-a-number");
    assertThat(
        assertThrows(IllegalStateException.class, () -> ControlPlaneConfig.fromEnv(env))
            .getMessage(),
        containsString("CONTROL_PLANE_HTTP_PORT"));
  }

  @Test
  void pushDisabledByDefault() {
    final ControlPlaneConfig config = ControlPlaneConfig.fromEnv(validEnv());
    assertThat(config.pushEnabled(), is(false));
  }

  @Test
  void pushEnabledWhenVapidConfigured() {
    final Map<String, String> env = validEnv();
    env.put("CONTROL_PLANE_VAPID_PUBLIC_KEY", "pub");
    env.put("CONTROL_PLANE_VAPID_PRIVATE_KEY", "priv");
    env.put("CONTROL_PLANE_VAPID_SUBJECT", "mailto:admin@example.com");

    final ControlPlaneConfig config = ControlPlaneConfig.fromEnv(env);

    assertThat(config.pushEnabled(), is(true));
    assertThat(config.vapidSubject(), is("mailto:admin@example.com"));
  }

  @Test
  void rejectsVapidKeyWithoutItsPair() {
    final Map<String, String> env = validEnv();
    env.put("CONTROL_PLANE_VAPID_PUBLIC_KEY", "pub");
    assertThat(
        assertThrows(IllegalStateException.class, () -> ControlPlaneConfig.fromEnv(env))
            .getMessage(),
        containsString("CONTROL_PLANE_VAPID_PUBLIC_KEY"));
  }

  @Test
  void rejectsVapidKeysWithoutSubject() {
    final Map<String, String> env = validEnv();
    env.put("CONTROL_PLANE_VAPID_PUBLIC_KEY", "pub");
    env.put("CONTROL_PLANE_VAPID_PRIVATE_KEY", "priv");
    assertThat(
        assertThrows(IllegalStateException.class, () -> ControlPlaneConfig.fromEnv(env))
            .getMessage(),
        containsString("CONTROL_PLANE_VAPID_SUBJECT"));
  }
}
