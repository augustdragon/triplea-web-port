package org.triplea.web.server.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.player.Player;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards {@link SeatPlan} — the seat-assignment model (modeled on the engine's {@code
 * PlayerListing}) that turns per-seat human/AI choices into the engine's {@code Set<Player>}.
 * Asserts claim/release/AI-type transitions, validation, carry-over across a reset, and that
 * human-claimed seats build a {@link WebPlayer} while the rest build the chosen engine AI.
 */
class SeatPlanTest {
  private GameData data;
  private SeatPlan plan;
  private String seatA;
  private String seatB;
  // A real bridge with no-op sinks; SeatPlan only needs it to construct WebPlayers.
  private final WebDecisionBridge bridge = new WebDecisionBridge((seat, env) -> {}, snapshot -> {});

  @BeforeEach
  void setUp() {
    data = TestMapGameData.REVISED.getGameData();
    plan = new SeatPlan(data);
    final List<String> names =
        data.getPlayerList().getPlayers().stream().map(GamePlayer::getName).toList();
    seatA = names.get(0);
    seatB = names.get(1);
  }

  @Test
  void rosterListsEverySeatDefaultedToOpenWithFastAi() {
    final SeatRoster roster = plan.toRoster("setup");
    assertEquals("setup", roster.phase());
    assertEquals(data.getPlayerList().getPlayers().size(), roster.seats().size());
    final SeatRoster.Seat a = find(roster, seatA);
    assertNull(a.owner(), "seats start open (no human owner)");
    assertEquals(PlayerTypes.FAST_AI.getLabel(), a.aiType(), "default AI is Fast");
    assertTrue(roster.aiTypes().contains(PlayerTypes.WEAK_AI.getLabel()));
    assertTrue(roster.aiTypes().contains(PlayerTypes.PRO_AI.getLabel()));
  }

  @Test
  void claimMakesSeatHumanAndBuildsWebPlayerForItAndAiForTheRest() {
    assertTrue(plan.claim(seatA, "Nate"));
    assertEquals("Nate", find(plan.toRoster("setup"), seatA).owner());

    final Set<Player> players = plan.buildPlayers(bridge);
    final Player claimed = playerNamed(players, seatA);
    final Player unclaimed = playerNamed(players, seatB);
    assertTrue(claimed instanceof WebPlayer, "claimed seat → WebPlayer");
    assertFalse(claimed.isAi());
    assertFalse(unclaimed instanceof WebPlayer, "unclaimed seat → AI");
  }

  @Test
  void setAiTypeChangesAnUnclaimedSeatAndRejectsUnknownLabels() {
    assertTrue(plan.setAiType(seatB, PlayerTypes.PRO_AI.getLabel()));
    assertEquals(PlayerTypes.PRO_AI.getLabel(), find(plan.toRoster("setup"), seatB).aiType());
    assertFalse(plan.setAiType(seatB, "Bogus (AI)"), "unknown AI label rejected");
  }

  @Test
  void releaseReturnsASeatToOpen() {
    plan.claim(seatA, "Nate");
    plan.release(seatA);
    assertNull(find(plan.toRoster("setup"), seatA).owner());
  }

  @Test
  void carryOverPreservesOwnersAndAiTypesAcrossAReset() {
    plan.claim(seatA, "Nate");
    plan.setAiType(seatB, PlayerTypes.PRO_AI.getLabel());

    final SeatPlan fresh = new SeatPlan(TestMapGameData.REVISED.getGameData());
    fresh.carryOver(plan);

    assertEquals("Nate", find(fresh.toRoster("setup"), seatA).owner());
    assertEquals(PlayerTypes.PRO_AI.getLabel(), find(fresh.toRoster("setup"), seatB).aiType());
  }

  @Test
  void unknownSeatIsRejected() {
    assertFalse(plan.hasSeat("Atlantis"));
    assertFalse(plan.claim("Atlantis", "Nate"));
  }

  private static SeatRoster.Seat find(final SeatRoster roster, final String name) {
    return roster.seats().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
  }

  private static Player playerNamed(final Set<Player> players, final String name) {
    return players.stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
  }
}
