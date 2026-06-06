package org.triplea.web.controlplane.auth;

import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.Handler;
import io.javalin.http.UnauthorizedResponse;
import java.util.HashSet;
import java.util.Set;

/**
 * Guards protected {@code /api/*} routes. Validates the session JWT from the cookie, re-checks the
 * allow-list on every request (so an eviction takes effect within the token's TTL), and stashes the
 * authenticated {@link Identity} for downstream handlers. Missing/invalid token → 401; valid token
 * for an identity no longer invited → 403. Login and logout are public (they have no session yet).
 */
public final class AuthFilter implements Handler {

  /** Request-attribute key under which the authenticated identity is stored. */
  public static final String IDENTITY_ATTR = "identity";

  // No session yet: login routes and logout. The OAuth login/callback establish the session.
  private static final Set<String> PUBLIC_PATHS = publicPaths();

  private static Set<String> publicPaths() {
    final Set<String> paths =
        new HashSet<>(Set.of("/api/dev-login", "/api/logout", "/api/auth/methods"));
    paths.addAll(GoogleOAuthController.publicPaths());
    return Set.copyOf(paths);
  }

  private final JwtService jwt;
  private final AllowList allowList;

  public AuthFilter(final JwtService jwt, final AllowList allowList) {
    this.jwt = jwt;
    this.allowList = allowList;
  }

  @Override
  public void handle(final Context ctx) {
    if (PUBLIC_PATHS.contains(ctx.path())) {
      return;
    }
    final String token = SessionCookies.read(ctx);
    if (token == null) {
      throw new UnauthorizedResponse("No session");
    }
    final Identity identity =
        jwt.verify(token).orElseThrow(() -> new UnauthorizedResponse("Invalid session"));
    if (!allowList.isAllowed(identity)) {
      throw new ForbiddenResponse("Not allow-listed");
    }
    ctx.attribute(IDENTITY_ATTR, identity);
  }
}
