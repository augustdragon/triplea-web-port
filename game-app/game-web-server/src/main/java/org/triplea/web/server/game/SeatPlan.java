package org.triplea.web.server.game;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.player.Player;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * The mutable seat-assignment plan for one game, modeled on the engine's {@code PlayerListing}: for
 * each nation (seat) it tracks the current human owner (a connected claimer, or {@code null}) and
 * the AI type to use if the seat is not human-claimed at launch. Built from {@link GameData} during
 * the setup phase; turned into the engine's {@code Set<Player>} at launch via {@link
 * #buildPlayers}.
 *
 * <p>Reuses the engine's {@link PlayerTypes} catalog as-is for the selectable AI types, and its
 * {@code Type.newPlayerWithName} factory to instantiate AI seats — so "assign any AI to any seat"
 * needs no new AI machinery. Human seats become {@link WebPlayer}s bound to the decision bridge.
 *
 * <p><b>Not thread-safe.</b> The controller serializes all mutations (claims arrive on WebSocket
 * threads) behind its own lock.
 */
final class SeatPlan {
  private final String gameName;
  private final PlayerTypes aiCatalog = new PlayerTypes(PlayerTypes.getBuiltInPlayerTypes());
  private final List<String> aiLabels =
      PlayerTypes.getBuiltInPlayerTypes().stream().map(PlayerTypes.Type::getLabel).toList();
  private final String defaultAiLabel = PlayerTypes.FAST_AI.getLabel();
  // Insertion order = engine turn order (PlayerList.getPlayers()).
  private final Map<String, SeatState> seats = new LinkedHashMap<>();

  private static final class SeatState {
    @Nullable private String owner;
    private String aiType;
    private boolean enabled = true;
    private final boolean canDisable;
    private final boolean optional;
    private final List<String> alliances;

    private SeatState(
        final String aiType,
        final boolean canDisable,
        final boolean optional,
        final List<String> alliances) {
      this.aiType = aiType;
      this.canDisable = canDisable;
      this.optional = optional;
      this.alliances = alliances;
    }
  }

  SeatPlan(final GameData gameData) {
    this.gameName = gameData.getGameName();
    for (final GamePlayer p : gameData.getPlayerList().getPlayers()) {
      seats.put(
          p.getName(),
          new SeatState(
              defaultAiLabel,
              p.getCanBeDisabled(),
              p.getOptional(),
              List.copyOf(gameData.getAllianceTracker().getAlliancesPlayerIsIn(p))));
    }
  }

  boolean hasSeat(final String seat) {
    return seats.containsKey(seat);
  }

  /** Mark a seat as controlled by {@code owner} (a connected human's display label). */
  boolean claim(final String seat, final String owner) {
    final SeatState s = seats.get(seat);
    if (s == null) {
      return false;
    }
    s.owner = owner;
    return true;
  }

  /**
   * Release a seat back to open (runs as its AI type at launch). Used by release and disconnect.
   */
  void release(final String seat) {
    final SeatState s = seats.get(seat);
    if (s != null) {
      s.owner = null;
    }
  }

  /** Set the AI type label a seat runs as if not human-claimed at launch. */
  boolean setAiType(final String seat, final String label) {
    final SeatState s = seats.get(seat);
    if (s == null || !aiLabels.contains(label)) {
      return false;
    }
    s.aiType = label;
    return true;
  }

  /**
   * Copy the current owner + AI-type of each like-named seat from {@code prior} (used on reset).
   */
  void carryOver(final SeatPlan prior) {
    for (final Map.Entry<String, SeatState> e : seats.entrySet()) {
      final SeatState old = prior.seats.get(e.getKey());
      if (old != null) {
        e.getValue().owner = old.owner;
        e.getValue().aiType = old.aiType;
      }
    }
  }

  /** Build the engine player set: human-owned seats → {@link WebPlayer}; the rest → their AI. */
  Set<Player> buildPlayers(final WebDecisionBridge bridge) {
    final Set<Player> players = new LinkedHashSet<>();
    for (final Map.Entry<String, SeatState> e : seats.entrySet()) {
      final String name = e.getKey();
      final SeatState s = e.getValue();
      players.add(
          s.owner != null
              ? new WebPlayer(name, "Web", bridge)
              : aiCatalog.fromLabel(s.aiType).newPlayerWithName(name));
    }
    return players;
  }

  /** Names of seats currently owned by a human — the {@code WebPlayer} seats at launch time. */
  Set<String> claimedSeatNames() {
    final Set<String> names = new HashSet<>();
    for (final Map.Entry<String, SeatState> e : seats.entrySet()) {
      if (e.getValue().owner != null) {
        names.add(e.getKey());
      }
    }
    return names;
  }

  SeatRoster toRoster(final String phase) {
    return toRoster(phase, null, Set.of());
  }

  SeatRoster toRoster(final String phase, final @Nullable SeatRoster.SavedGame savedGame) {
    return toRoster(phase, savedGame, Set.of());
  }

  /**
   * @param humanSeats names of seats that are human-driven in the running game (empty in setup), so
   *     the client knows which open seats are rejoinable.
   */
  SeatRoster toRoster(
      final String phase,
      final @Nullable SeatRoster.SavedGame savedGame,
      final Set<String> humanSeats) {
    final List<SeatRoster.Seat> list = new ArrayList<>();
    for (final Map.Entry<String, SeatState> e : seats.entrySet()) {
      final SeatState s = e.getValue();
      // Optional/inert minors (e.g. Pacific's Russians/French/Dutch) aren't selectable seats —
      // they never produce/move/fight, so they're hidden from the picker. buildPlayers still
      // creates an (inert) AI player for them; the engine needs a Player for every nation.
      if (s.optional) {
        continue;
      }
      list.add(
          new SeatRoster.Seat(
              e.getKey(),
              s.owner,
              s.aiType,
              s.enabled,
              s.canDisable,
              s.optional,
              humanSeats.contains(e.getKey()),
              s.alliances));
    }
    return new SeatRoster(phase, gameName, list, aiLabels, savedGame);
  }
}
