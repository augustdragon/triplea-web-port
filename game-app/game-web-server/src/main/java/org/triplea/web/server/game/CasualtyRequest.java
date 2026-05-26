package org.triplea.web.server.game;

import java.util.Map;
import javax.annotation.Nullable;

/**
 * The payload of a {@code kind:"selectCasualties"} decision request: the player taking the hits
 * must choose which of their units die. {@code count} is exactly how many hits to assign; {@code
 * options} is the eligible pool (unit type → available count from {@code selectFrom}); {@code
 * defaultKilled} is the engine's auto-pick (type → count) so the browser can pre-select and the
 * player can just accept. {@code location} is the battle territory (null for anti-aircraft fire
 * during movement), {@code message} the engine's prompt text. The browser replies {@code
 * {killed:{type:count}}} whose counts sum to {@code count}.
 *
 * <p>First pass handles fatal hits only (the common land case); choosing to merely <i>damage</i> a
 * two-hit unit when {@code allowMultipleHits} is true is deferred.
 */
public record CasualtyRequest(
    String player,
    @Nullable String location,
    String message,
    int count,
    Map<String, Integer> options,
    Map<String, Integer> defaultKilled,
    boolean allowMultipleHits) {}
