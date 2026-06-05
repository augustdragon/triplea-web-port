package org.triplea.web.server.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.player.Player;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the lobby→container seat handoff against the class of bug where a human-claimed seat is
 * silently played by AI. Two seams are covered:
 *
 * <ol>
 *   <li><b>Wire → {@link LobbySeat}</b> (Gson, the consumer end of {@code
 *       /internal/games/{id}/seats}): every seat state round-trips, and crucially a human seat with
 *       <b>no</b> turn deadline deserializes to a {@code null} {@code turnDeadlineEpoch} — never 0.
 *       (A 0 here is epoch 1970, which the container reads as an already-expired turn and hands the
 *       seat to AI; that was the live bug, rooted on the producer side in {@code LobbyDao} — see
 *       {@code LobbyDaoSeatMappingTest}.)
 *   <li><b>{@link LobbySeat} → engine player</b> ({@link SeatPlan#applyAssignments} then {@link
 *       SeatPlan#buildPlayers}): a human assignment builds a {@link WebPlayer} (a human-driven
 *       seat), an open/AI assignment builds an engine AI. If this regresses, the engine silently
 *       runs a human's seat as AI.
 * </ol>
 */
class LobbySeatBindingTest {
  private static final Gson GSON = new Gson();
  private GameData data;
  private SeatPlan plan;
  private List<String> seats;
  // A real bridge with no-op sinks; SeatPlan only needs it to construct WebPlayers.
  private final WebDecisionBridge bridge = new WebDecisionBridge((seat, env) -> {}, snapshot -> {});

  @BeforeEach
  void setUp() {
    data = TestMapGameData.REVISED.getGameData();
    plan = new SeatPlan(data);
    seats =
        data.getPlayerList().getPlayers().stream()
            .filter(p -> !p.getOptional())
            .map(GamePlayer::getName)
            .toList();
  }

  @Test
  void wireRosterDeserializesEverySeatStateAndNeverTurnsANullDeadlineIntoZero() {
    final String human = seats.get(0);
    final String humanWithDeadline = seats.get(1);
    final String open = seats.get(2);
    // Mirrors the control plane's GET /internal/games/{id}/seats output. The first seat omits
    // turnDeadlineEpoch entirely (a human who hasn't been put on the clock yet) — the bug case.
    final String json =
        "["
            + "{\"powerName\":\""
            + human
            + "\",\"kind\":\"human\",\"userId\":1,\"displayName\":\"Nathan\"},"
            + "{\"powerName\":\""
            + humanWithDeadline
            + "\",\"kind\":\"human\",\"userId\":2,"
            + "\"displayName\":\"Bob\",\"turnDeadlineEpoch\":1780000000},"
            + "{\"powerName\":\""
            + open
            + "\",\"kind\":\"open\"}"
            + "]";

    final List<LobbySeat> parsed =
        GSON.fromJson(json, new TypeToken<List<LobbySeat>>() {}.getType());

    final LobbySeat noDeadline = parsed.get(0);
    assertTrue(noDeadline.isHuman(), "kind=human must deserialize as human");
    assertEquals("Nathan", noDeadline.displayName());
    assertNull(
        noDeadline.turnDeadlineEpoch(),
        "an absent/null deadline must stay null — a 0 here is epoch 1970 and triggers instant AI takeover");

    assertEquals(
        1780000000L, parsed.get(1).turnDeadlineEpoch(), "a real deadline survives the round-trip");
    assertFalse(parsed.get(2).isHuman(), "kind=open is not human");
  }

  @Test
  void humanAssignmentsBuildWebPlayersAndOpenSeatsBuildAi() {
    final String humanSeat = seats.get(0);
    final String openSeat = seats.get(1);
    // The human seat has no deadline (the live-bug shape); it must still build a human WebPlayer.
    plan.applyAssignments(
        List.of(
            new LobbySeat(humanSeat, "human", 1L, "Nathan", null),
            new LobbySeat(openSeat, "open", null, null, null)));

    assertEquals(Set.of(humanSeat), plan.claimedSeatNames(), "only the human seat is claimed");

    final Set<Player> players = plan.buildPlayers(bridge);
    assertTrue(
        playerNamed(players, humanSeat) instanceof WebPlayer,
        "a human-claimed seat must build a WebPlayer (human-driven), never an AI");
    assertFalse(playerNamed(players, humanSeat).isAi());
    assertFalse(
        playerNamed(players, openSeat) instanceof WebPlayer, "an open seat builds an engine AI");
  }

  private static Player playerNamed(final Set<Player> players, final String name) {
    return players.stream()
        .filter(p -> p.getName().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no player named " + name));
  }
}
