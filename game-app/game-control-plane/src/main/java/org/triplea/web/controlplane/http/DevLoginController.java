package org.triplea.web.controlplane.http;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import org.triplea.web.controlplane.auth.Identity;
import org.triplea.web.controlplane.auth.LoginService;
import org.triplea.web.controlplane.auth.SessionCookies;

/**
 * Dev-only fake login: {@code POST /api/dev-login {"subject","displayName"}} mints a real session
 * cookie for a synthetic {@code google} identity, so the lobby/orchestrator and this auth machinery
 * can be built and tested before real Google/Discord OAuth apps exist. It still goes through the
 * full {@link LoginService} (allow-list check + user upsert), so an uninvited subject gets 403 just
 * like the real flow. Registered only when {@code CONTROL_PLANE_DEV_LOGIN=true}, which the config
 * forbids under {@code profile=prod}.
 */
public final class DevLoginController {

  /** Request body; provider is forced to a synthetic Google identity (respects the DB CHECK). */
  public record DevLoginRequest(String subject, String displayName) {}

  private static final String DEV_PROVIDER = "google";

  private final LoginService loginService;
  private final SessionCookies sessionCookies;

  public DevLoginController(final LoginService loginService, final SessionCookies sessionCookies) {
    this.loginService = loginService;
    this.sessionCookies = sessionCookies;
  }

  public void register(final Javalin app) {
    app.post("/api/dev-login", this::login);
  }

  private void login(final Context ctx) {
    final DevLoginRequest req = ctx.bodyAsClass(DevLoginRequest.class);
    if (req == null || req.subject() == null || req.subject().isBlank()) {
      throw new BadRequestResponse("subject is required");
    }
    final String displayName =
        req.displayName() == null || req.displayName().isBlank()
            ? req.subject()
            : req.displayName();
    // Dev-login has no email — the allow-list matches it by provider:subject.
    final Identity identity = new Identity(DEV_PROVIDER, req.subject(), displayName, null);
    final LoginService.LoginResult result =
        loginService
            .login(identity)
            .orElseThrow(() -> new ForbiddenResponse("Identity is not on the allow-list"));
    sessionCookies.set(ctx, result.token());
    ctx.json(result.user());
  }
}
