package org.triplea.web.controlplane.lobby;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ConflictResponse;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UnauthorizedResponse;
import java.util.List;
import java.util.UUID;
import org.triplea.web.controlplane.auth.AuthFilter;
import org.triplea.web.controlplane.auth.Identity;
import org.triplea.web.controlplane.game.GameCatalog;
import org.triplea.web.controlplane.game.GameCatalog.AvailableGame;
import org.triplea.web.controlplane.user.User;
import org.triplea.web.controlplane.user.UserDao;

/**
 * Lobby REST: create a table (a seat per playable power), list/inspect tables, claim/release/ready
 * a seat as the authenticated user, and host-launch. Seat ownership and the host check are enforced
 * server-side from the session identity — never from client-supplied names. Every mutation notifies
 * a {@link ChangeListener} so the lobby WebSocket (M3a-ws) can push updates; until then it is a
 * no-op. Launch flips the table to active and invokes the {@link GameLauncher} seam (which only
 * logs intent until the child-process spawn lands in M4a).
 */
public final class LobbyController {

  /** Notified after any lobby mutation; the WS broadcaster plugs in here. */
  public interface ChangeListener {
    void onLobbyChanged();
  }

  /** Catalog entry as the client sees it (no server-side map path). */
  public record CatalogEntry(String id, String name, List<String> playablePowers) {}

  public record CreateTableRequest(String gameId) {}

  public record ReadyRequest(boolean ready) {}

  private final LobbyDao dao;
  private final GameCatalog catalog;
  private final UserDao userDao;
  private final ChangeListener onChange;

  public LobbyController(
      final LobbyDao dao,
      final GameCatalog catalog,
      final UserDao userDao,
      final ChangeListener onChange) {
    this.dao = dao;
    this.catalog = catalog;
    this.userDao = userDao;
    this.onChange = onChange;
  }

  public void register(final Javalin app) {
    app.get("/api/catalog", this::listCatalog);
    app.post("/api/games", this::createTable);
    app.get("/api/games", this::listTables);
    app.get("/api/games/{id}", this::getTable);
    app.post("/api/games/{id}/seats/{power}/claim", this::claimSeat);
    app.post("/api/games/{id}/seats/{power}/release", this::releaseSeat);
    app.post("/api/games/{id}/seats/{power}/ready", this::setReady);
    app.post("/api/games/{id}/launch", this::launch);
  }

  private void listCatalog(final Context ctx) {
    currentUser(ctx);
    ctx.json(
        catalog.all().stream()
            .map(g -> new CatalogEntry(g.id(), g.name(), g.playablePowers()))
            .toList());
  }

  private void createTable(final Context ctx) {
    final User user = currentUser(ctx);
    final CreateTableRequest req = ctx.bodyAsClass(CreateTableRequest.class);
    if (req == null || req.gameId() == null || req.gameId().isBlank()) {
      throw new BadRequestResponse("gameId is required");
    }
    final AvailableGame game =
        catalog.get(req.gameId()).orElseThrow(() -> new BadRequestResponse("Unknown gameId"));
    final UUID id = dao.createTable(game, user.id());
    onChange.onLobbyChanged();
    ctx.status(201).json(dao.getTable(id).orElseThrow());
  }

  private void listTables(final Context ctx) {
    currentUser(ctx);
    ctx.json(dao.listTables());
  }

  private void getTable(final Context ctx) {
    currentUser(ctx);
    ctx.json(dao.getTable(parseId(ctx)).orElseThrow(() -> new NotFoundResponse("No such table")));
  }

  private void claimSeat(final Context ctx) {
    final User user = currentUser(ctx);
    final UUID id = parseId(ctx);
    if (!dao.claimSeat(id, ctx.pathParam("power"), user.id())) {
      throw new ConflictResponse("Seat is not open");
    }
    onChange.onLobbyChanged();
    ctx.json(dao.getTable(id).orElseThrow());
  }

  private void releaseSeat(final Context ctx) {
    final User user = currentUser(ctx);
    final UUID id = parseId(ctx);
    if (!dao.releaseSeat(id, ctx.pathParam("power"), user.id())) {
      throw new ForbiddenResponse("Not your seat");
    }
    onChange.onLobbyChanged();
    ctx.json(dao.getTable(id).orElseThrow());
  }

  private void setReady(final Context ctx) {
    final User user = currentUser(ctx);
    final UUID id = parseId(ctx);
    final ReadyRequest req = ctx.bodyAsClass(ReadyRequest.class);
    if (!dao.setReady(id, ctx.pathParam("power"), user.id(), req != null && req.ready())) {
      throw new ForbiddenResponse("Not your seat");
    }
    onChange.onLobbyChanged();
    ctx.json(dao.getTable(id).orElseThrow());
  }

  private void launch(final Context ctx) {
    final User user = currentUser(ctx);
    final UUID id = parseId(ctx);
    final LobbyDao.GameForLaunch game =
        dao.gameForLaunch(id).orElseThrow(() -> new NotFoundResponse("No such table"));
    if (game.createdBy() != user.id()) {
      throw new ForbiddenResponse("Only the host can launch");
    }
    if (!"lobby".equals(game.status())) {
      throw new ConflictResponse("Table is not in the lobby");
    }
    final LobbyDao.SeatCounts counts = dao.humanSeatCounts(id);
    if (counts.humans() == 0) {
      throw new ConflictResponse("Need at least one human seat to launch");
    }
    if (counts.unready() > 0) {
      throw new ConflictResponse(counts.unready() + " human seat(s) not ready");
    }
    // Go active; the container is spawned lazily when the first player hits /connect.
    if (!dao.markActive(id)) {
      throw new ConflictResponse("Table is no longer in the lobby");
    }
    onChange.onLobbyChanged();
    ctx.json(dao.getTable(id).orElseThrow());
  }

  private User currentUser(final Context ctx) {
    final Identity identity = ctx.attribute(AuthFilter.IDENTITY_ATTR);
    if (identity == null) {
      throw new UnauthorizedResponse("No session");
    }
    return userDao
        .findByIdentity(identity)
        .orElseThrow(() -> new UnauthorizedResponse("Unknown user"));
  }

  private static UUID parseId(final Context ctx) {
    try {
      return UUID.fromString(ctx.pathParam("id"));
    } catch (final IllegalArgumentException e) {
      throw new NotFoundResponse("No such table");
    }
  }
}
