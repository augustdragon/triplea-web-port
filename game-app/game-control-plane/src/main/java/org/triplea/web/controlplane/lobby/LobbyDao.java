package org.triplea.web.controlplane.lobby;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.PreparedBatch;
import org.triplea.web.controlplane.game.GameCatalog.AvailableGame;

/**
 * Lobby persistence over JDBI (parameterized SQL throughout). Seat mutations enforce their rules in
 * the WHERE clause — claim only an open seat, release/ready only your own — and return the affected
 * row count so the controller can map 0 to a 409/403. The launch gate reads the creator + seat
 * readiness so only the host can start, and only once every human seat is ready.
 */
public final class LobbyDao {

  /** Minimal game facts the launch path needs. */
  public record GameForLaunch(long createdBy, String status) {}

  /** Human-seat tallies for the ready-up gate. */
  public record SeatCounts(long humans, long unready) {}

  private final Jdbi jdbi;

  public LobbyDao(final Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  /** Create a lobby table: a games row plus one open seat per playable power, in turn order. */
  public UUID createTable(final AvailableGame game, final long creatorUserId) {
    return jdbi.inTransaction(
        handle -> {
          final UUID id =
              handle
                  .createUpdate(
                      "INSERT INTO games (map_xml, edition, created_by)"
                          + " VALUES (:mapXml, :name, :creator) RETURNING id")
                  .bind("mapXml", game.mapXml())
                  .bind("name", game.name())
                  .bind("creator", creatorUserId)
                  .executeAndReturnGeneratedKeys("id")
                  .mapTo(UUID.class)
                  .one();
          final PreparedBatch batch =
              handle.prepareBatch(
                  "INSERT INTO seats (game_id, power_name, kind, seat_order)"
                      + " VALUES (:gid, :power, 'open', :ord)");
          final List<String> powers = game.playablePowers();
          for (int i = 0; i < powers.size(); i++) {
            batch.bind("gid", id).bind("power", powers.get(i)).bind("ord", i).add();
          }
          batch.execute();
          return id;
        });
  }

  /** All tables still in the lobby phase, each with its seats in turn order. */
  public List<LobbyTable> listTables() {
    return jdbi.withHandle(
        handle -> {
          final Map<UUID, List<SeatView>> seatsByGame =
              groupSeats(
                  handle
                      .createQuery(
                          "SELECT s.game_id, s.power_name, s.kind, s.ready,"
                              + " ho.display_name AS owner_name, ho.player_chat_id AS owner_chat"
                              + " FROM seats s"
                              + " JOIN games g ON g.id = s.game_id AND g.status = 'lobby'"
                              + " LEFT JOIN users ho ON ho.id = s.user_id"
                              + " ORDER BY s.game_id, s.seat_order")
                      .map((rs, ctx) -> new SeatRow(rs.getObject("game_id", UUID.class), seat(rs)))
                      .list());
          return handle
              .createQuery(
                  "SELECT g.id, g.edition AS name, g.status,"
                      + " hg.display_name AS host_name, hg.player_chat_id AS host_chat"
                      + " FROM games g JOIN users hg ON hg.id = g.created_by"
                      + " WHERE g.status = 'lobby' ORDER BY g.created_at")
              .map(
                  (rs, ctx) -> {
                    final UUID id = rs.getObject("id", UUID.class);
                    return new LobbyTable(
                        id.toString(),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getString("host_name"),
                        rs.getString("host_chat"),
                        seatsByGame.getOrDefault(id, List.of()));
                  })
              .list();
        });
  }

  /** A single table (any status), with its seats. */
  public Optional<LobbyTable> getTable(final UUID gameId) {
    return jdbi.withHandle(
        handle -> {
          final List<SeatView> seats =
              handle
                  .createQuery(
                      "SELECT s.power_name, s.kind, s.ready,"
                          + " ho.display_name AS owner_name, ho.player_chat_id AS owner_chat"
                          + " FROM seats s LEFT JOIN users ho ON ho.id = s.user_id"
                          + " WHERE s.game_id = :gid ORDER BY s.seat_order")
                  .bind("gid", gameId)
                  .map((rs, ctx) -> seat(rs))
                  .list();
          return handle
              .createQuery(
                  "SELECT g.id, g.edition AS name, g.status,"
                      + " hg.display_name AS host_name, hg.player_chat_id AS host_chat"
                      + " FROM games g JOIN users hg ON hg.id = g.created_by WHERE g.id = :gid")
              .bind("gid", gameId)
              .map(
                  (rs, ctx) ->
                      new LobbyTable(
                          rs.getObject("id", UUID.class).toString(),
                          rs.getString("name"),
                          rs.getString("status"),
                          rs.getString("host_name"),
                          rs.getString("host_chat"),
                          seats))
              .findOne();
        });
  }

  /** Claim an open seat for the user. Returns true if a seat was actually claimed. */
  public boolean claimSeat(final UUID gameId, final String power, final long userId) {
    return jdbi.withHandle(
            handle ->
                handle
                    .createUpdate(
                        "UPDATE seats SET user_id = :uid, kind = 'human'"
                            + " WHERE game_id = :gid AND power_name = :power AND kind = 'open'")
                    .bind("uid", userId)
                    .bind("gid", gameId)
                    .bind("power", power)
                    .execute())
        == 1;
  }

  /** Release the user's own seat back to open. Returns true if it was theirs to release. */
  public boolean releaseSeat(final UUID gameId, final String power, final long userId) {
    return jdbi.withHandle(
            handle ->
                handle
                    .createUpdate(
                        "UPDATE seats SET user_id = NULL, kind = 'open', ready = false"
                            + " WHERE game_id = :gid AND power_name = :power AND user_id = :uid")
                    .bind("gid", gameId)
                    .bind("power", power)
                    .bind("uid", userId)
                    .execute())
        == 1;
  }

  /** Set the ready flag on the user's own seat. Returns true if it was theirs to set. */
  public boolean setReady(
      final UUID gameId, final String power, final long userId, final boolean ready) {
    return jdbi.withHandle(
            handle ->
                handle
                    .createUpdate(
                        "UPDATE seats SET ready = :ready"
                            + " WHERE game_id = :gid AND power_name = :power AND user_id = :uid")
                    .bind("ready", ready)
                    .bind("gid", gameId)
                    .bind("power", power)
                    .bind("uid", userId)
                    .execute())
        == 1;
  }

  /** The creator + status, for host/launch checks. */
  public Optional<GameForLaunch> gameForLaunch(final UUID gameId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT created_by, status FROM games WHERE id = :gid")
                .bind("gid", gameId)
                .map(
                    (rs, ctx) ->
                        new GameForLaunch(rs.getLong("created_by"), rs.getString("status")))
                .findOne());
  }

  /** Human-seat counts for the ready-up gate. */
  public SeatCounts humanSeatCounts(final UUID gameId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT count(*) FILTER (WHERE kind = 'human') AS humans,"
                        + " count(*) FILTER (WHERE kind = 'human' AND NOT ready) AS unready"
                        + " FROM seats WHERE game_id = :gid")
                .bind("gid", gameId)
                .map((rs, ctx) -> new SeatCounts(rs.getLong("humans"), rs.getLong("unready")))
                .one());
  }

  /** Flip a lobby table to active, recording the game's WS endpoint. True if it was launched. */
  public boolean markActive(final UUID gameId, final String wsEndpoint) {
    return jdbi.withHandle(
            handle ->
                handle
                    .createUpdate(
                        "UPDATE games SET status = 'active', ws_endpoint = :ep, updated_at = now()"
                            + " WHERE id = :gid AND status = 'lobby'")
                    .bind("ep", wsEndpoint)
                    .bind("gid", gameId)
                    .execute())
        == 1;
  }

  private static SeatView seat(final java.sql.ResultSet rs) throws java.sql.SQLException {
    return new SeatView(
        rs.getString("power_name"),
        rs.getString("kind"),
        rs.getBoolean("ready"),
        rs.getString("owner_name"),
        rs.getString("owner_chat"));
  }

  private static Map<UUID, List<SeatView>> groupSeats(final List<SeatRow> rows) {
    final Map<UUID, List<SeatView>> byGame = new LinkedHashMap<>();
    for (final SeatRow row : rows) {
      byGame.computeIfAbsent(row.gameId(), k -> new ArrayList<>()).add(row.view());
    }
    return byGame;
  }

  private record SeatRow(UUID gameId, SeatView view) {}
}
