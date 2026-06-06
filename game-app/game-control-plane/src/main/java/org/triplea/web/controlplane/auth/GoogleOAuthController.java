package org.triplea.web.controlplane.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.triplea.web.controlplane.config.ControlPlaneConfig;

/**
 * Hand-rolled Google OAuth 2.0 / OIDC login (authorization-code flow), feeding the same {@link
 * LoginService} seam dev-login uses. No third-party OAuth library — the flow is two endpoints and a
 * single token-exchange call over the JDK {@link HttpClient}.
 *
 * <p>{@code /login} redirects to Google with an anti-CSRF {@code state} (stored in a short-lived
 * cookie). {@code /callback} verifies the state, exchanges the code for an {@code id_token}, and —
 * because that token came straight from Google's token endpoint over TLS to a confidential client —
 * reads its claims without re-verifying the signature (still checking {@code iss}/{@code
 * aud}/{@code email_verified}). The resulting {@link Identity} carries the verified email so the
 * allow-list can invite by email. Both routes are public (added to {@code AuthFilter}'s allowlist)
 * — they establish the session.
 */
@Slf4j
public final class GoogleOAuthController {

  private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
  private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
  private static final String PROVIDER = "google";
  private static final String STATE_COOKIE = "oauth_state";
  private static final Duration STATE_TTL = Duration.ofMinutes(10);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final LoginService loginService;
  private final SessionCookies sessionCookies;
  private final ControlPlaneConfig config;
  private final HttpClient httpClient;

  public GoogleOAuthController(
      final LoginService loginService,
      final SessionCookies sessionCookies,
      final ControlPlaneConfig config) {
    this.loginService = loginService;
    this.sessionCookies = sessionCookies;
    this.config = config;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  /** The public paths this controller serves — registered as exempt from the session AuthFilter. */
  public static java.util.Set<String> publicPaths() {
    return java.util.Set.of("/api/oauth/google/login", "/api/oauth/google/callback");
  }

  public void register(final Javalin app) {
    app.get("/api/oauth/google/login", this::login);
    app.get("/api/oauth/google/callback", this::callback);
  }

  private void login(final Context ctx) {
    final String state = randomToken();
    ctx.cookie(stateCookie(state, (int) STATE_TTL.toSeconds()));
    final String url =
        AUTH_ENDPOINT
            + "?client_id="
            + enc(config.googleClientId())
            + "&redirect_uri="
            + enc(config.googleRedirectUri())
            + "&response_type=code"
            + "&scope="
            + enc("openid email profile")
            + "&state="
            + enc(state)
            + "&access_type=online"
            + "&prompt=select_account";
    ctx.redirect(url);
  }

  private void callback(final Context ctx) {
    final String expectedState = ctx.cookie(STATE_COOKIE);
    final String state = ctx.queryParam("state");
    final String code = ctx.queryParam("code");
    ctx.removeCookie(STATE_COOKIE, "/");

    if (expectedState == null || state == null || !constantTimeEquals(expectedState, state)) {
      log.warn("OAuth callback with missing/mismatched state — rejecting");
      ctx.redirect("/login?error=oauth_state");
      return;
    }
    if (code == null || code.isBlank()) {
      ctx.redirect("/login?error=oauth_no_code");
      return;
    }

    final Optional<Identity> identity = exchangeCodeForIdentity(code);
    if (identity.isEmpty()) {
      ctx.redirect("/login?error=oauth_failed");
      return;
    }

    final Optional<LoginService.LoginResult> result = loginService.login(identity.get());
    if (result.isEmpty()) {
      // Authenticated by Google but not invited. Log enough to add them to the allow-list.
      log.warn(
          "Rejected login for un-invited Google identity: subject={} email={}",
          identity.get().subject(),
          identity.get().email());
      ctx.redirect("/login?error=not_invited");
      return;
    }
    sessionCookies.set(ctx, result.get().token());
    ctx.redirect("/lobby");
  }

  /** Exchange the auth code for tokens and read the verified identity from the id_token. */
  private Optional<Identity> exchangeCodeForIdentity(final String code) {
    final String form =
        "code="
            + enc(code)
            + "&client_id="
            + enc(config.googleClientId())
            + "&client_secret="
            + enc(config.googleClientSecret())
            + "&redirect_uri="
            + enc(config.googleRedirectUri())
            + "&grant_type=authorization_code";
    try {
      final HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(TOKEN_ENDPOINT))
                  .timeout(Duration.ofSeconds(15))
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .header("Accept", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(form))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        log.warn("Google token exchange failed: HTTP {}", response.statusCode());
        return Optional.empty();
      }
      final JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
      if (!body.has("id_token")) {
        log.warn("Google token response had no id_token");
        return Optional.empty();
      }
      return identityFromIdToken(body.get("id_token").getAsString());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (final RuntimeException | java.io.IOException e) {
      log.warn("Google token exchange error: {}", e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Read claims from the id_token. The token came directly from Google's token endpoint over TLS to
   * a confidential client (we sent the client secret), so per Google's guidance the signature need
   * not be re-verified here; we still validate issuer, audience, and that the email is verified.
   */
  Optional<Identity> identityFromIdToken(final String idToken) {
    final DecodedJWT jwt;
    try {
      jwt = JWT.decode(idToken);
    } catch (final RuntimeException e) {
      log.warn("Unparseable id_token");
      return Optional.empty();
    }
    final String issuer = jwt.getIssuer();
    if (!"https://accounts.google.com".equals(issuer) && !"accounts.google.com".equals(issuer)) {
      log.warn("id_token from unexpected issuer: {}", issuer);
      return Optional.empty();
    }
    if (!jwt.getAudience().contains(config.googleClientId())) {
      log.warn("id_token audience does not match our client id");
      return Optional.empty();
    }
    final String subject = jwt.getSubject();
    final String email = jwt.getClaim("email").asString();
    final Boolean emailVerified = jwt.getClaim("email_verified").asBoolean();
    if (subject == null) {
      return Optional.empty();
    }
    // Only trust the email for allow-listing if Google says it's verified.
    final String verifiedEmail = Boolean.TRUE.equals(emailVerified) ? email : null;
    final String name = jwt.getClaim("name").asString();
    final String displayName = name != null ? name : (email != null ? email : subject);
    return Optional.of(new Identity(PROVIDER, subject, displayName, verifiedEmail));
  }

  private Cookie stateCookie(final String value, final int maxAgeSeconds) {
    final Cookie cookie = new Cookie(STATE_COOKIE, value);
    cookie.setPath("/");
    cookie.setMaxAge(maxAgeSeconds);
    cookie.setHttpOnly(true);
    cookie.setSecure(config.secureCookies());
    cookie.setSameSite(SameSite.LAX); // must survive the cross-site redirect back from Google
    return cookie;
  }

  private static String randomToken() {
    final byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static boolean constantTimeEquals(final String a, final String b) {
    return java.security.MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  private static String enc(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
