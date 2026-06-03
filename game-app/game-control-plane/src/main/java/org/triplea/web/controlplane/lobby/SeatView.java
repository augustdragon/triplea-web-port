package org.triplea.web.controlplane.lobby;

/**
 * One seat in a lobby table: the power (nation), its kind (open/human/ai), ready state, and — when
 * claimed — the owner's display name and public handle. {@code ownerName}/{@code ownerChatId} are
 * null for open/AI seats.
 */
public record SeatView(
    String power, String kind, boolean ready, String ownerName, String ownerChatId) {}
