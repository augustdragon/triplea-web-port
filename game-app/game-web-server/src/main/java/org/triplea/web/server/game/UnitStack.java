package org.triplea.web.server.game;

/**
 * A group of identical units in a territory: the owning player's name, the unit type's name, and
 * how many. The web client renders these as stack counts at the territory center and lists them in
 * the hover tooltip. Type/owner are names (not engine objects) so the snapshot stays JSON-friendly.
 */
public record UnitStack(String owner, String type, int count) {}
