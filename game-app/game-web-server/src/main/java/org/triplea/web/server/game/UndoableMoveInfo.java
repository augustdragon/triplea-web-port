package org.triplea.web.server.game;

/**
 * A move the player has already made this phase and may undo. {@code index} is the move's position
 * in the delegate's undo list (what {@code IMoveDelegate.undoMove(index)} expects); {@code label}
 * is a short "start -> end" description for the browser; {@code canUndo} is false when the engine
 * currently blocks undoing it (e.g. a later move depends on it) — the panel disables those.
 */
public record UndoableMoveInfo(int index, String label, boolean canUndo) {}
