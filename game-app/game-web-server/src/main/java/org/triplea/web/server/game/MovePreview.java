package org.triplea.web.server.game;

import java.util.List;
import javax.annotation.Nullable;

/**
 * A previewed move, computed server-side from the player's chosen source, destination, and units but
 * NOT yet executed — sent back on the next {@code move} request so the browser can highlight the
 * route and warn before the player commits. The engine's own {@code MoveValidator.getBestRoute} (the
 * same routing the Swing client uses) finds the path, so a preview that exists is a legal route.
 *
 * @param from source territory the units start in.
 * @param to destination the player clicked.
 * @param route the best legal path as ordered territory names (source first), or null if there is no
 *     legal route for these units.
 * @param cost that route's movement cost (terrain-weighted step count).
 * @param blockedTypes chosen unit types that lack the movement left to make the whole trip (shown as
 *     a warning); the move can still proceed for the types that can.
 * @param message a human-readable reason when {@code route} is null (e.g. "No legal route").
 */
public record MovePreview(
    String from,
    String to,
    @Nullable List<String> route,
    double cost,
    List<String> blockedTypes,
    @Nullable String message) {}
