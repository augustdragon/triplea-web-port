package org.triplea.web.controlplane.lobby;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.triplea.web.controlplane.json.GsonJsonMapper;

/**
 * Locks the wire contract that the game container reads from {@code GET /internal/games/{id}/seats}
 * (served by {@link org.triplea.web.controlplane.game.InternalSeatsController} via {@link
 * GsonJsonMapper}). The container deserializes this JSON into its own {@code LobbySeat} record with
 * Gson, so the field names here must match it and a NULL turn deadline must not become 0.
 *
 * <p>A {@code turnDeadlineEpoch} of 0 is epoch 1970 — an already-expired turn — which the container
 * uses to instantly hand a human seat to AI. The live bug produced that 0 upstream in {@link
 * LobbyDao} (see {@link LobbyDaoSeatMappingTest}); this test guards the serialization seam so a
 * null stays null (Gson omits it) all the way to the wire.
 */
class SeatAssignmentJsonTest {
  private static final Type SEAT_LIST = new TypeToken<List<SeatAssignment>>() {}.getType();
  private final GsonJsonMapper mapper = new GsonJsonMapper();

  @Test
  void serializesSeatMatrixWithMatchingFieldNamesAndNullDeadlineNeverZero() {
    final List<SeatAssignment> seats =
        List.of(
            new SeatAssignment(
                "Americans", "human", 1L, "Nathan", null), // the bug case: no deadline
            new SeatAssignment("Japanese", "open", null, null, null),
            new SeatAssignment("British", "human", 2L, "Bob", 1_780_000_000L));

    final JsonArray arr =
        JsonParser.parseString(mapper.toJsonString(seats, SEAT_LIST)).getAsJsonArray();

    final JsonObject americans = arr.get(0).getAsJsonObject();
    assertThat(americans.get("kind").getAsString(), is("human"));
    assertThat(americans.get("displayName").getAsString(), is("Nathan"));
    // Field names must match the container's LobbySeat record, or Gson silently drops them.
    assertThat(americans.keySet(), hasItems("powerName", "kind", "userId", "displayName"));
    // A null deadline must be absent or null — never a 0 that would expire the turn at epoch 1970.
    final boolean hasNonNullDeadline =
        americans.has("turnDeadlineEpoch") && !americans.get("turnDeadlineEpoch").isJsonNull();
    assertThat(
        "null deadline must not serialize as a value (0 = epoch 1970)",
        hasNonNullDeadline,
        is(false));

    // A real deadline round-trips intact.
    assertThat(
        arr.get(2).getAsJsonObject().get("turnDeadlineEpoch").getAsLong(), is(1_780_000_000L));
  }
}
