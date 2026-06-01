package org.triplea.web.controlplane.http;

import io.javalin.Javalin;
import io.javalin.http.Context;
import org.triplea.web.controlplane.db.Database;

/**
 * Liveness/readiness endpoint. {@code GET /health} returns 200 when the database round-trips and
 * 503 when it does not, so a load balancer or {@code docker compose} healthcheck can gate traffic
 * on actual DB connectivity rather than just "the process is up".
 */
public final class HealthController {

  private final Database database;

  public HealthController(final Database database) {
    this.database = database;
  }

  public void register(final Javalin app) {
    app.get("/health", this::health);
  }

  private void health(final Context ctx) {
    final boolean ok = database.isReachable();
    ctx.status(ok ? 200 : 503)
        .contentType("application/json")
        .result(ok ? "{\"status\":\"ok\"}" : "{\"status\":\"down\"}");
  }
}
