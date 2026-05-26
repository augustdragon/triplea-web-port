package org.triplea.web.server.game;

/**
 * A move the player has already made this phase and may undo. {@code index} is the move's position
 * in the delegate's undo list (what {@code IMoveDelegate.undoMove(index)} expects); {@code units}
 * is a "2 infantry, 1 armour"-style summary of what moved (grouped by type, like the Swing undo
 * panel's per-category labels); {@code label} is the "start -> end" route; {@code canUndo} is false
 * when the engine currently blocks undoing it (e.g. a later move depends on it) — the panel
 * disables those.
 */
public record UndoableMoveInfo(int index, String units, String label, boolean canUndo) {}
