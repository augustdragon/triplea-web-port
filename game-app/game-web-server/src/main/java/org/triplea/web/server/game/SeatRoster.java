package org.triplea.web.server.game;

import java.util.List;
import javax.annotation.Nullable;

/**
 * The seat-assignment roster broadcast to every client (server → client {@code {type:"seats",
 * roster}}). Modeled on the engine's {@code PlayerListing}: for each nation it reports the current
 * human owner (a connected claimer, or {@code null} = open) and the AI type that will run the seat
 * if it is not human-claimed at launch. {@code aiTypes} is the catalog of selectable AI labels for
 * the per-seat dropdown.
 *
 * <p>{@code phase} is {@code "setup"} (assigning seats, no game yet) or {@code "running"} (the game
 * has launched) — the client switches between the seat-select screen and the game UI on it.
 */
public record SeatRoster(
    String phase,
    String gameName,
    List<Seat> seats,
    List<String> aiTypes,
    @Nullable SavedGame savedGame) {

  /** A resumable autosave for this game (its round + current step), or absent if none exists. */
  public record SavedGame(int round, String step) {}

  /**
   * One nation/seat's assignment.
   *
   * @param name the nation name (the engine seat id).
   * @param owner the connected human controlling it, or {@code null} if open (runs as {@code
   *     aiType} at launch).
   * @param aiType the AI label used if the seat is not human-claimed at launch.
   * @param enabled whether the seat participates (disabling is not yet wired; always true for now).
   * @param canDisable whether the engine permits this seat to be disabled.
   * @param optional whether this is a passive/optional minor power (e.g. Pacific's French/Dutch).
   * @param alliances the alliance groups this seat belongs to (for client grouping).
   */
  public record Seat(
      String name,
      @Nullable String owner,
      String aiType,
      boolean enabled,
      boolean canDisable,
      boolean optional,
      List<String> alliances) {}
}
