package org.triplea.web.controlplane.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Typed configuration for the control plane, read from environment variables only (never
 * hardcoded). {@link #fromEnv()} validates everything up front and fails fast with a single message
 * listing all problems, so a misconfigured deploy dies at startup rather than limping.
 *
 * <p>The database URL and user have local-dev defaults (matching {@code .docker/web-port-db.yml});
 * the password and the JWT signing secret have no defaults — a missing secret is a hard startup
 * error. Dev login is a local-only convenience and is rejected when the profile is {@code prod}.
 *
 * @param profile {@code dev} or {@code prod}; gates dev login and whether cookies are marked
 *     Secure.
 * @param devLoginEnabled whether the dev-only fake-login route is registered (never in prod).
 * @param jwtSecret HMAC signing key for the session JWT (>= 32 chars for HS256).
 * @param sessionTtlMinutes session lifetime; also the cookie max-age and JWT expiry.
 * @param allowList invited identities as {@code "provider:subject"} strings; access control v1.
 * @param gameXml path to the one map's game XML the lobby can host (optional; empty lobby if
 *     unset).
 * @param gameToken shared token game containers present to the internal reporting endpoint
 *     (optional; reports are rejected when unset). M5 replaces it with a per-game token.
 */
public record ControlPlaneConfig(
    String dbUrl,
    String dbUser,
    String dbPassword,
    int httpPort,
    String profile,
    boolean devLoginEnabled,
    String jwtSecret,
    int sessionTtlMinutes,
    Set<String> allowList,
    String gameXml,
    String gameToken) {

  private static final String DEFAULT_DB_URL = "jdbc:postgresql://localhost:5432/triplea_web";
  private static final String DEFAULT_DB_USER = "triplea_web";
  private static final String DEFAULT_HTTP_PORT = "7000";
  private static final String DEFAULT_PROFILE = "dev";
  private static final String DEFAULT_SESSION_TTL = "60";
  private static final int MIN_JWT_SECRET_LENGTH = 32; // HS256 needs a >= 256-bit key

  /** True in production: dev login is forbidden and cookies must be marked Secure (HTTPS). */
  public boolean isProd() {
    return "prod".equals(profile);
  }

  /** Cookies are marked Secure only in prod (local dev runs over plain HTTP). */
  public boolean secureCookies() {
    return isProd();
  }

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
    if (isBlank(dbPassword)) {
      problems.add("CONTROL_PLANE_DB_PASSWORD (required, no default)");
    }

    final int httpPort = parsePort(env, problems);

    final String profile = env.getOrDefault("CONTROL_PLANE_PROFILE", DEFAULT_PROFILE).trim();
    if (!profile.equals("dev") && !profile.equals("prod")) {
      problems.add("CONTROL_PLANE_PROFILE (must be 'dev' or 'prod', was '" + profile + "')");
    }

    final boolean devLoginEnabled = parseBool(env.get("CONTROL_PLANE_DEV_LOGIN"));
    if (profile.equals("prod") && devLoginEnabled) {
      problems.add("CONTROL_PLANE_DEV_LOGIN must be false when CONTROL_PLANE_PROFILE=prod");
    }

    final String jwtSecret = env.get("CONTROL_PLANE_JWT_SECRET");
    if (isBlank(jwtSecret)) {
      problems.add("CONTROL_PLANE_JWT_SECRET (required, no default)");
    } else if (jwtSecret.length() < MIN_JWT_SECRET_LENGTH) {
      problems.add(
          "CONTROL_PLANE_JWT_SECRET (must be >= "
              + MIN_JWT_SECRET_LENGTH
              + " chars, was "
              + jwtSecret.length()
              + ")");
    }

    final int sessionTtlMinutes = parseTtl(env, problems);
    final Set<String> allowList = parseAllowList(env.getOrDefault("CONTROL_PLANE_ALLOWLIST", ""));
    final String gameXml = env.get("CONTROL_PLANE_GAME_XML");
    final String gameToken = env.get("CONTROL_PLANE_GAME_TOKEN");

    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "Invalid control-plane configuration: " + String.join("; ", problems));
    }
    return new ControlPlaneConfig(
        dbUrl,
        dbUser,
        dbPassword,
        httpPort,
        profile,
        devLoginEnabled,
        jwtSecret,
        sessionTtlMinutes,
        allowList,
        gameXml,
        gameToken);
  }

  private static int parsePort(final Map<String, String> env, final List<String> problems) {
    final String raw = env.getOrDefault("CONTROL_PLANE_HTTP_PORT", DEFAULT_HTTP_PORT);
    try {
      final int port = Integer.parseInt(raw.trim());
      if (port < 1 || port > 65_535) {
        problems.add("CONTROL_PLANE_HTTP_PORT (must be 1-65535, was " + raw + ")");
      }
      return port;
    } catch (final NumberFormatException e) {
      problems.add("CONTROL_PLANE_HTTP_PORT (must be an integer, was " + raw + ")");
      return 0;
    }
  }

  private static int parseTtl(final Map<String, String> env, final List<String> problems) {
    final String raw = env.getOrDefault("CONTROL_PLANE_SESSION_TTL_MINUTES", DEFAULT_SESSION_TTL);
    try {
      final int ttl = Integer.parseInt(raw.trim());
      if (ttl < 1) {
        problems.add("CONTROL_PLANE_SESSION_TTL_MINUTES (must be >= 1, was " + raw + ")");
      }
      return ttl;
    } catch (final NumberFormatException e) {
      problems.add("CONTROL_PLANE_SESSION_TTL_MINUTES (must be an integer, was " + raw + ")");
      return 0;
    }
  }

  /** Parse a comma-separated allow-list of {@code "provider:subject"} entries into a set. */
  private static Set<String> parseAllowList(final String raw) {
    final Set<String> entries = new LinkedHashSet<>();
    for (final String entry : Arrays.asList(raw.split(","))) {
      final String trimmed = entry.trim();
      if (!trimmed.isEmpty()) {
        entries.add(trimmed);
      }
    }
    return Set.copyOf(entries);
  }

  private static boolean parseBool(final String value) {
    return value != null && value.trim().equalsIgnoreCase("true");
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }
}
