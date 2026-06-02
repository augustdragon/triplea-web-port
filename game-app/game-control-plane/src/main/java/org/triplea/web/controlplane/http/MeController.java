package org.triplea.web.controlplane.http;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;
import org.triplea.web.controlplane.auth.AuthFilter;
import org.triplea.web.controlplane.auth.Identity;
import org.triplea.web.controlplane.user.User;
import org.triplea.web.controlplane.user.UserDao;

/**
 * {@code GET /api/me} — returns the authenticated caller's own user record. Runs behind {@link
 * AuthFilter}, which has already validated the session and stashed the identity; this just loads
 * the persisted row.
 */
public final class MeController {

  private final UserDao userDao;

  public MeController(final UserDao userDao) {
    this.userDao = userDao;
  }

  public void register(final Javalin app) {
    app.get("/api/me", this::me);
  }

  private void me(final Context ctx) {
    final Identity identity = ctx.attribute(AuthFilter.IDENTITY_ATTR);
    if (identity == null) {
      throw new UnauthorizedResponse("No session");
    }
    final User user =
        userDao
            .findByIdentity(identity)
            .orElseThrow(() -> new UnauthorizedResponse("Session refers to an unknown user"));
    ctx.json(user);
  }
}
