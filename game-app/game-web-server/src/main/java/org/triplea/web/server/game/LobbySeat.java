package org.triplea.web.server.game;

import javax.annotation.Nullable;

/**
 * One seat's assignment as fetched from the control plane's {@code GET /internal/games/{id}/seats}
 * (the container-side mirror of the control plane's {@code SeatAssignment} — field names must match
 * for Gson). A human seat carries the claimer's display name; AI/open seats have a null owner and
 * run as AI at launch.
 */
record LobbySeat(
    String powerName,
    String kind,
    @Nullable Long userId,
    @Nullable String displayName,
    @Nullable Long turnDeadlineEpoch) {

  boolean isHuman() {
    return "human".equals(kind);
  }
}
