package org.triplea.web.controlplane.http;

import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.Map;

/**
 * {@code GET /api/auth/methods} — a public endpoint telling the SPA which login methods are wired,
 * so the login page shows the right options (Google button and/or the dev-login form) without
 * guessing. Public (no session yet); added to the {@code AuthFilter} allowlist.
 */
public final class AuthConfigController {

  public static final String PATH = "/api/auth/methods";

  private final boolean devLoginEnabled;
  private final boolean googleEnabled;

  public AuthConfigController(final boolean devLoginEnabled, final boolean googleEnabled) {
    this.devLoginEnabled = devLoginEnabled;
    this.googleEnabled = googleEnabled;
  }

  public void register(final Javalin app) {
    app.get(PATH, this::methods);
  }

  private void methods(final Context ctx) {
    ctx.json(Map.of("devLogin", devLoginEnabled, "google", googleEnabled));
  }
}
