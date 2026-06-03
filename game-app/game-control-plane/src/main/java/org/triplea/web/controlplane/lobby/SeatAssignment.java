package org.triplea.web.controlplane.lobby;

/**
 * A seat's resolved assignment as a launched game container needs it: the power, its kind
 * (human/ai/open), and — for a human seat — the claimer's user id and display name (null for
 * open/AI seats). Served at {@code GET /internal/games/{id}/seats} so the container builds its seat
 * plan from the lobby's authenticated assignments instead of trusting client-supplied names.
 */
public record SeatAssignment(String powerName, String kind, Long userId, String displayName) {}
