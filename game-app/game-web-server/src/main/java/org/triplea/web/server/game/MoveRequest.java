package org.triplea.web.server.game;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The payload of a {@code kind:"move"} decision request: the moving player, whether this is the
 * combat (vs non-combat) move phase, and {@code movableUnits} — for each territory the player can
 * still act in, the list of {@link MovableUnit}s available there (type, count, air/sea flags,
 * movement-left) so the browser offers only valid picks and can label them. {@code error} carries
 * back a delegate rejection of a prior submission. {@code undoableMoves} lists the moves already
 * made this phase so the browser can offer an Undo. {@code preview}, when present, is the engine's
 * best legal route for a source/destination/units the browser asked to preview (see {@link
 * MovePreview}), echoed back so it can highlight the path before the player commits.
 *
 * <p>The browser replies with one of: {@code {done:true}} (end the phase); {@code {from, to,
 * units:{type:count}}} (perform a move — the server finds the best legal route from source to
 * destination for those units); {@code {previewRoute:{from, to, units}}} (compute that route and
 * send it back as {@code preview} without moving); {@code {undo:index}} (undo the move at that
 * index); or {@code {undoAll:true}} (undo every move made this phase). A land→sea route auto-loads
 * the chosen land units onto transports in the destination sea zone.
 */
public record MoveRequest(
    String player,
    boolean combat,
    Map<String, List<MovableUnit>> movableUnits,
    List<UndoableMoveInfo> undoableMoves,
    @Nullable String error,
    @Nullable MovePreview preview) {}
