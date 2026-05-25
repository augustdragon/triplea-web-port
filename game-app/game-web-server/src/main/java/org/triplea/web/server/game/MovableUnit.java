package org.triplea.web.server.game;

/**
 * One row in a territory's movable-units listing for a {@code kind:"move"} request: a unit type,
 * how many of it can still act there, and enough metadata for the browser to present it sensibly
 * without re-deriving rules. {@code air}/{@code sea} let the panel label the unit and hint at where
 * it may go (the engine remains the authority — it validates every submitted move). {@code
 * movementLeft} is the <b>maximum</b> remaining movement among that type's units in the territory;
 * it is a display hint only and is {@code 0} for cargo still aboard a transport (which moves by
 * unloading, not by spending movement). Air/naval movement bonuses from bases are already reflected
 * here because the value comes from the engine's own {@code Unit.getMovementLeft()}.
 */
public record MovableUnit(String type, int count, boolean air, boolean sea, double movementLeft) {}
