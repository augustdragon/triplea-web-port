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
 * made this phase so the browser can offer an Undo. The browser replies with one of: {@code
 * {done:true}} (end the phase); {@code {route:[territoryNames], units:{type:count}}} (one move to
 * perform; units are drawn from the first territory in the route; a land→sea route auto-loads the
 * chosen land units onto transports in the destination sea zone); or {@code {undo:index}} (undo the
 * move at that index).
 */
public record MoveRequest(
    String player,
    boolean combat,
    Map<String, List<MovableUnit>> movableUnits,
    List<UndoableMoveInfo> undoableMoves,
    @Nullable String error) {}
