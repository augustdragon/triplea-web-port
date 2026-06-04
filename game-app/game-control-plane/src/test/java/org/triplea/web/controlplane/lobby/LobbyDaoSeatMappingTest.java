package org.triplea.web.controlplane.lobby;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for {@link LobbyDao#mapSeatAssignment}'s {@link ResultSet#wasNull()} ordering. A
 * NULL turn deadline on a HUMAN seat (which carries a non-null {@code display_name}) must map to a
 * null {@code turnDeadlineEpoch}, not 0: a game container reads 0 as epoch 1970 — an
 * already-expired turn — and instantly surrenders the human seat to AI on turn one. The earlier bug
 * read {@code wasNull()} after {@code getString("display_name")} (always non-null for a human), so
 * the null deadline was mis-read as 0.
 */
class LobbyDaoSeatMappingTest {

  /**
   * A {@link ResultSet} stub that faithfully models JDBC's contract that {@code wasNull()} reflects
   * only the most recently read column — so a regression in read ordering is actually caught.
   */
  private static ResultSet rowWith(
      final String powerName,
      final String kind,
      final Long userId,
      final String displayName,
      final Long deadlineEpoch)
      throws SQLException {
    final ResultSet rs = mock(ResultSet.class);
    final boolean[] lastNull = {false};
    when(rs.getString("power_name"))
        .thenAnswer(
            i -> {
              lastNull[0] = powerName == null;
              return powerName;
            });
    when(rs.getString("kind"))
        .thenAnswer(
            i -> {
              lastNull[0] = kind == null;
              return kind;
            });
    when(rs.getString("display_name"))
        .thenAnswer(
            i -> {
              lastNull[0] = displayName == null;
              return displayName;
            });
    when(rs.getLong("user_id"))
        .thenAnswer(
            i -> {
              lastNull[0] = userId == null;
              return userId == null ? 0L : userId;
            });
    when(rs.getLong("deadline_epoch"))
        .thenAnswer(
            i -> {
              lastNull[0] = deadlineEpoch == null;
              return deadlineEpoch == null ? 0L : deadlineEpoch;
            });
    when(rs.wasNull()).thenAnswer(i -> lastNull[0]);
    return rs;
  }

  @Test
  void humanSeatWithNoDeadlineMapsToNullDeadline() throws SQLException {
    // Americans claimed by user 1 ("Nathan"), no turn deadline set yet — the exact bug case.
    final SeatAssignment seat =
        LobbyDao.mapSeatAssignment(rowWith("Americans", "human", 1L, "Nathan", null));

    assertThat(seat.turnDeadlineEpoch(), is(nullValue())); // must NOT be 0 (epoch 1970)
    assertThat(seat.userId(), is(1L));
    assertThat(seat.displayName(), is("Nathan"));
    assertThat(seat.kind(), is("human"));
  }

  @Test
  void humanSeatWithDeadlinePreservesIt() throws SQLException {
    final SeatAssignment seat =
        LobbyDao.mapSeatAssignment(rowWith("Americans", "human", 1L, "Nathan", 1_900_000_000L));

    assertThat(seat.turnDeadlineEpoch(), is(1_900_000_000L));
  }

  @Test
  void openSeatHasNullOwnerAndDeadline() throws SQLException {
    final SeatAssignment seat =
        LobbyDao.mapSeatAssignment(rowWith("Japanese", "open", null, null, null));

    assertThat(seat.userId(), is(nullValue()));
    assertThat(seat.displayName(), is(nullValue()));
    assertThat(seat.turnDeadlineEpoch(), is(nullValue()));
  }
}
