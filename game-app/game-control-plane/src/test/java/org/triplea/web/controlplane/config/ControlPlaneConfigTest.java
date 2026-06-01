package org.triplea.web.controlplane.config;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ControlPlaneConfigTest {

  @Test
  void failsFastWhenPasswordMissing() {
    final IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> ControlPlaneConfig.fromEnv(Map.of()));
    assertThat(thrown.getMessage(), containsString("CONTROL_PLANE_DB_PASSWORD"));
  }

  @Test
  void appliesDevDefaultsAndReadsPassword() {
    final ControlPlaneConfig config =
        ControlPlaneConfig.fromEnv(Map.of("CONTROL_PLANE_DB_PASSWORD", "secret"));

    assertThat(config.dbUrl(), containsString("triplea_web"));
    assertThat(config.dbUser(), is("triplea_web"));
    assertThat(config.dbPassword(), is("secret"));
    assertThat(config.httpPort(), is(7000));
  }

  @Test
  void honorsOverridesFromEnv() {
    final ControlPlaneConfig config =
        ControlPlaneConfig.fromEnv(
            Map.of(
                "CONTROL_PLANE_DB_URL", "jdbc:postgresql://db:5432/other",
                "CONTROL_PLANE_DB_USER", "someone",
                "CONTROL_PLANE_DB_PASSWORD", "secret",
                "CONTROL_PLANE_HTTP_PORT", "9090"));

    assertThat(config.dbUrl(), is("jdbc:postgresql://db:5432/other"));
    assertThat(config.dbUser(), is("someone"));
    assertThat(config.httpPort(), is(9090));
  }

  @Test
  void rejectsNonNumericPort() {
    final IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                ControlPlaneConfig.fromEnv(
                    Map.of(
                        "CONTROL_PLANE_DB_PASSWORD", "secret",
                        "CONTROL_PLANE_HTTP_PORT", "not-a-number")));
    assertThat(thrown.getMessage(), containsString("CONTROL_PLANE_HTTP_PORT"));
  }
}
