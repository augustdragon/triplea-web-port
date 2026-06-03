package org.triplea.web.controlplane.lobby;

/**
 * A seat's resolved assignment as a launched game container needs it: the power, its kind
 * (human/ai/open), and — for a human seat — the claimer's user id and display name (null for
 * open/AI seats). {@code turnDeadlineEpoch} is the absolute deadline (epoch seconds) if this seat
 * is currently on the clock — read on (re)boot so the turn timer survives a container reap rather
 * than resetting each rehydrate; null otherwise. Served at {@code GET /internal/games/{id}/seats}
 * so the container builds its seat plan from the lobby's authenticated assignments instead of
 * trusting client-supplied names.
 */
public record SeatAssignment(
    String powerName, String kind, Long userId, String displayName, Long turnDeadlineEpoch) {}
