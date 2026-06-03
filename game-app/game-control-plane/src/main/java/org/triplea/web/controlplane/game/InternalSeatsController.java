package org.triplea.web.controlplane.game;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UnauthorizedResponse;
import java.util.UUID;
import org.triplea.web.controlplane.lobby.LobbyDao;

/**
 * Serves a launched game container its seat→identity assignments at {@code GET
 * /internal/games/{id}/seats}. Service-to-service (outside {@code /api/*}, so the user {@code
 * AuthFilter} doesn't apply); authenticated with the shared game token, mirroring {@link
 * GameReportController}. The container calls this once at boot to build its seat plan from the
 * lobby's authenticated assignments rather than trusting client-supplied names.
 */
public final class InternalSeatsController {

  private final LobbyDao lobbyDao;
  private final String expectedToken;

  public InternalSeatsController(final LobbyDao lobbyDao, final String expectedToken) {
    this.lobbyDao = lobbyDao;
    this.expectedToken = expectedToken;
  }

  public void register(final Javalin app) {
    app.get("/internal/games/{id}/seats", this::handle);
  }

  private void handle(final Context ctx) {
    if (expectedToken == null
        || expectedToken.isBlank()
        || !expectedToken.equals(ctx.header("X-Game-Token"))) {
      throw new UnauthorizedResponse("Invalid game token");
    }
    ctx.json(lobbyDao.seatAssignments(parseId(ctx)));
  }

  private static UUID parseId(final Context ctx) {
    try {
      return UUID.fromString(ctx.pathParam("id"));
    } catch (final IllegalArgumentException e) {
      throw new NotFoundResponse("No such game");
    }
  }
}
