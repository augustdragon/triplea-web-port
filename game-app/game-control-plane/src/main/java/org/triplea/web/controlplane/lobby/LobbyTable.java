package org.triplea.web.controlplane.lobby;

import java.util.List;

/**
 * A lobby table as the client sees it: the game id (used in URLs), its display name, status, the
 * host's public handle, and its seats in turn order. The client decides "is this my seat / am I the
 * host" by comparing its own {@code playerChatId} (from {@code /api/me}) to {@code hostChatId} and
 * each seat's {@code ownerChatId} — the server stays identity-agnostic in the payload.
 */
public record LobbyTable(
    String id,
    String name,
    String status,
    String hostName,
    String hostChatId,
    int turnLimitSeconds,
    List<SeatView> seats) {}
