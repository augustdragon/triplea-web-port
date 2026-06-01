package org.triplea.web.controlplane.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Typed configuration for the control plane, read from environment variables only (never
 * hardcoded). {@link #fromEnv()} validates everything up front and fails fast with a single message
 * listing all problems, so a misconfigured deploy dies at startup rather than limping.
 *
 * <p>The database URL and user have local-dev defaults (matching {@code .docker/web-port-db.yml});
 * the password has no default — a missing secret is a hard startup error.
 */
public record ControlPlaneConfig(String dbUrl, String dbUser, String dbPassword, int httpPort) {

  private static final String DEFAULT_DB_URL = "jdbc:postgresql://localhost:5432/triplea_web";
  private static final String DEFAULT_DB_USER = "triplea_web";
  private static final String DEFAULT_HTTP_PORT = "7000";

  /** Build config from the process environment, validating and failing fast on any problem. */
  public static ControlPlaneConfig fromEnv() {
    return fromEnv(System.getenv());
  }

  /** Package-visible for unit testing without touching the real environment. */
  static ControlPlaneConfig fromEnv(final Map<String, String> env) {
    final List<String> problems = new ArrayList<>();

    final String dbUrl = env.getOrDefault("CONTROL_PLANE_DB_URL", DEFAULT_DB_URL);
    final String dbUser = env.getOrDefault("CONTROL_PLANE_DB_USER", DEFAULT_DB_USER);

    final String dbPassword = env.get("CONTROL_PLANE_DB_PASSWORD");
    if (dbPassword == null || dbPassword.isBlank()) {
      problems.add("CONTROL_PLANE_DB_PASSWORD (required, no default)");
    }

    int httpPort = 0;
    final String rawPort = env.getOrDefault("CONTROL_PLANE_HTTP_PORT", DEFAULT_HTTP_PORT);
    try {
      httpPort = Integer.parseInt(rawPort.trim());
      if (httpPort < 1 || httpPort > 65_535) {
        problems.add("CONTROL_PLANE_HTTP_PORT (must be 1-65535, was " + rawPort + ")");
      }
    } catch (final NumberFormatException e) {
      problems.add("CONTROL_PLANE_HTTP_PORT (must be an integer, was " + rawPort + ")");
    }

    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "Invalid control-plane configuration: " + String.join("; ", problems));
    }
    return new ControlPlaneConfig(dbUrl, dbUser, dbPassword, httpPort);
  }
}
