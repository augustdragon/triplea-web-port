package org.triplea.web.controlplane.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * @param gameImage the Docker image the orchestrator spawns for each game.
 * @param saveVolume the Docker volume mounted into game containers for the save store.
 * @param gameCallbackUrl the control-plane URL a game container uses to report (reachable from
 *     inside the container — host-gateway by default).
 * @param gameWsHost the host the browser dials for a game container's WebSocket.
 * @param gameIdleSeconds reap a game container after this many seconds with no committed turn (its
 *     state is safe on disk; reconnecting respawns it from the latest save).
 * @param vapidPublicKey the VAPID public key (base64url, uncompressed P-256 point) for "your turn"
 *     Web Push; blank disables push. Also handed to the browser as the {@code applicationServerKey}.
 * @param vapidPrivateKey the VAPID private key (base64url, raw 32-byte scalar); blank disables push.
 *     Must be paired with the public key.
 * @param vapidSubject the VAPID {@code sub} claim — a {@code mailto:} or {@code https:} URL
 *     identifying the sender to the push service; required when push is enabled.
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
    String gameToken,
    String gameImage,
    String saveVolume,
    String gameCallbackUrl,
    String gameWsHost,
    int gameIdleSeconds,
    String launcher,
    String gameBin,
    String gameHeap,
    String publicUrl,
    String vapidPublicKey,
    String vapidPrivateKey,
    String vapidSubject) {

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

  /**
   * "Your turn" Web Push is enabled only when a VAPID keypair is configured. Absent keys → the
   * subscribe endpoints still work but no push is ever sent (and the client never prompts), so dev
   * without keys boots cleanly.
   */
  public boolean pushEnabled() {
    return !isBlank(vapidPublicKey) && !isBlank(vapidPrivateKey);
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
    final String gameImage =
        env.getOrDefault("CONTROL_PLANE_GAME_IMAGE", "triplea-game-web-server");
    final String saveVolume = env.getOrDefault("CONTROL_PLANE_SAVE_VOLUME", "triplea-web-saves");
    final String gameCallbackUrl =
        env.getOrDefault(
            "CONTROL_PLANE_GAME_CALLBACK_URL", "http://host.docker.internal:" + httpPort);
    final String gameWsHost = env.getOrDefault("CONTROL_PLANE_GAME_WS_HOST", "localhost");
    final int gameIdleSeconds = parseIdleSeconds(env, problems);

    // How game JVMs are spawned: "docker" (one container per game, the default) or "process" (a
    // child JVM per game — no Docker daemon/image; the Docker-free single-VM deploy).
    final String launcher =
        env.getOrDefault("CONTROL_PLANE_LAUNCHER", "docker").trim().toLowerCase(Locale.ROOT);
    if (!launcher.equals("docker") && !launcher.equals("process")) {
      problems.add(
          "CONTROL_PLANE_LAUNCHER (must be 'docker' or 'process', was '" + launcher + "')");
    }
    // Process launcher only: the game-web-server installDist launcher script, and the heap cap per
    // child JVM (an uncapped JVM defaults its max heap to ~25% of host RAM).
    final String gameBin = env.get("CONTROL_PLANE_GAME_BIN");
    if (launcher.equals("process") && isBlank(gameBin)) {
      problems.add("CONTROL_PLANE_GAME_BIN (required when CONTROL_PLANE_LAUNCHER=process)");
    }
    final String gameHeap = env.getOrDefault("CONTROL_PLANE_GAME_HEAP", "-Xmx512m");
    // Public base URL (https://your-domain) — for OAuth callbacks/absolute links later; optional
    // now.
    final String publicUrl = env.getOrDefault("CONTROL_PLANE_PUBLIC_URL", "");

    // "Your turn" Web Push (VAPID). All optional — push is simply off when keys are absent. But the
    // keys are a pair (a public key without its private key, or vice versa, is a misconfiguration),
    // and an enabled push must have a subject (the push services require the JWT `sub` claim).
    final String vapidPublicKey = env.getOrDefault("CONTROL_PLANE_VAPID_PUBLIC_KEY", "").trim();
    final String vapidPrivateKey = env.getOrDefault("CONTROL_PLANE_VAPID_PRIVATE_KEY", "").trim();
    final String vapidSubject = env.getOrDefault("CONTROL_PLANE_VAPID_SUBJECT", "").trim();
    if (isBlank(vapidPublicKey) != isBlank(vapidPrivateKey)) {
      problems.add(
          "CONTROL_PLANE_VAPID_PUBLIC_KEY and CONTROL_PLANE_VAPID_PRIVATE_KEY must be set together");
    }
    if (!isBlank(vapidPublicKey) && isBlank(vapidSubject)) {
      problems.add(
          "CONTROL_PLANE_VAPID_SUBJECT (a mailto: or https: URL) is required when VAPID keys are set");
    }

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
        gameToken,
        gameImage,
        saveVolume,
        gameCallbackUrl,
        gameWsHost,
        gameIdleSeconds,
        launcher,
        gameBin,
        gameHeap,
        publicUrl,
        vapidPublicKey,
        vapidPrivateKey,
        vapidSubject);
  }

  private static int parseIdleSeconds(final Map<String, String> env, final List<String> problems) {
    final String raw = env.getOrDefault("CONTROL_PLANE_GAME_IDLE_SECONDS", "1800");
    try {
      final int seconds = Integer.parseInt(raw.trim());
      if (seconds < 1) {
        problems.add("CONTROL_PLANE_GAME_IDLE_SECONDS (must be >= 1, was " + raw + ")");
      }
      return seconds;
    } catch (final NumberFormatException e) {
      problems.add("CONTROL_PLANE_GAME_IDLE_SECONDS (must be an integer, was " + raw + ")");
      return 0;
    }
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
