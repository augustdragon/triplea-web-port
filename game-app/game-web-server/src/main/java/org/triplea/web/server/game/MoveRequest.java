package org.triplea.web.server.game;

import java.util.Map;
import javax.annotation.Nullable;

/**
 * The payload of a {@code kind:"move"} decision request: the moving player, whether this is the
 * combat (vs non-combat) move phase, and {@code movableUnits} — for each territory the player holds
 * units with movement left, a unit-type → count map so the browser offers only valid picks. {@code
 * error} carries back a delegate rejection of a prior submission. The browser replies either {@code
 * {done:true}} (end the phase) or {@code {route:[territoryNames], units:{type:count}}} (one move to
 * perform; units are drawn from the first territory in the route).
 */
public record MoveRequest(
    String player,
    boolean combat,
    Map<String, Map<String, Integer>> movableUnits,
    @Nullable String error) {}
