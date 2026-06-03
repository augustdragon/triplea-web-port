package org.triplea.web.controlplane.game;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UnauthorizedResponse;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.triplea.web.controlplane.orchestrator.GameReaper;

/**
 * Receives a game container's lifecycle/turn reports at {@code POST /internal/games/{id}/events}.
 * This is service-to-service (not under {@code /api/*}, so the user {@code AuthFilter} doesn't
 * apply); it authenticates with the shared game token instead. M5 will issue per-game tokens at
 * spawn and scope each to its own game id.
 */
@Slf4j
public final class GameReportController {

  /** A reported event; only the fields relevant to its {@code type} are populated. */
  public record Event(
      String type,
      Integer round,
      String power,
      String phase,
      String bytesRef,
      String reason,
      String winner,
      Long deadlineEpoch,
      java.util.List<String> powers) {}

  private final GameReportDao dao;
  private final GameReaper reaper;
  private final String expectedToken;

  public GameReportController(
      final GameReportDao dao, final GameReaper reaper, final String expectedToken) {
    this.dao = dao;
    this.reaper = reaper;
    this.expectedToken = expectedToken;
  }

  public void register(final Javalin app) {
    if (expectedToken == null || expectedToken.isBlank()) {
      log.warn(
          "CONTROL_PLANE_GAME_TOKEN not set — game reporting endpoint will reject all reports");
    }
    app.post("/internal/games/{id}/events", this::handle);
  }

  private void handle(final Context ctx) {
    if (expectedToken == null
        || expectedToken.isBlank()
        || !expectedToken.equals(ctx.header("X-Game-Token"))) {
      throw new UnauthorizedResponse("Invalid game token");
    }
    final UUID gameId = parseId(ctx);
    final Event event = ctx.bodyAsClass(Event.class);
    if (event == null || event.type() == null) {
      throw new BadRequestResponse("event type is required");
    }
    switch (event.type()) {
      case "started" -> dao.markStarted(gameId);
      case "turn" ->
          dao.recordTurn(
              gameId, intOr(event.round(), 0), event.power(), event.phase(), event.bytesRef());
      case "finished" -> {
        dao.markFinished(gameId, event.reason(), event.winner());
        reaper.reapGame(gameId); // the game is over — stop its container
      }
      case "resigned" -> dao.resignSeat(gameId, event.power()); // conceded seat is now AI
      case "deadline" ->
          // The active seat's absolute turn deadline (null = cleared); others are cleared.
          dao.setTurnDeadline(gameId, event.power(), event.deadlineEpoch());
      case "presence" ->
          // Which seats currently have a live connection (for the lobby + reaper).
          dao.setPresence(gameId, event.powers() == null ? java.util.List.of() : event.powers());

      default -> throw new BadRequestResponse("unknown event type: " + event.type());
    }
    ctx.status(204);
  }

  private static int intOr(final Integer value, final int dflt) {
    return value == null ? dflt : value;
  }

  private static UUID parseId(final Context ctx) {
    try {
      return UUID.fromString(ctx.pathParam("id"));
    } catch (final IllegalArgumentException e) {
      throw new NotFoundResponse("No such game");
    }
  }
}
