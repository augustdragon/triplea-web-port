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

  @Test
  void prodMarksCookiesSecure() {
    final Map<String, String> env = validEnv();
    env.put("CONTROL_PLANE_PROFILE", "prod");

    final ControlPlaneConfig config = ControlPlaneConfig.fromEnv(env);

    assertThat(config.isProd(), is(true));
    assertThat(config.secureCookies(), is(true));
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
}
