package org.triplea.web.controlplane.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.StatementContext;
import org.triplea.web.controlplane.auth.Identity;

/** Reads and upserts {@code users} rows via JDBI (parameterized SQL, never string-concatenated). */
public final class UserDao {

  private static final String UPSERT =
      """
      INSERT INTO users (oauth_provider, oauth_subject, display_name, player_chat_id, last_login_at)
      VALUES (:provider, :subject, :displayName, :playerChatId, now())
      ON CONFLICT (oauth_provider, oauth_subject)
      DO UPDATE SET display_name = EXCLUDED.display_name, last_login_at = now()
      RETURNING id, oauth_provider, oauth_subject, display_name, player_chat_id, role,
                created_at, last_login_at
      """;

  private static final String FIND_BY_IDENTITY =
      """
      SELECT id, oauth_provider, oauth_subject, display_name, player_chat_id, role,
             created_at, last_login_at
      FROM users
      WHERE oauth_provider = :provider AND oauth_subject = :subject
      """;

  private final Jdbi jdbi;

  public UserDao(final Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  /**
   * Insert the user on first login (assigning a public player_chat_id) or update display name +
   * last_login_at on return. player_chat_id is set only on insert — it never changes once issued.
   */
  public User upsertOnLogin(final Identity identity) {
    return jdbi.withHandle(
        handle ->
            handle
                .createUpdate(UPSERT)
                .bind("provider", identity.provider())
                .bind("subject", identity.subject())
                .bind("displayName", identity.displayName())
                .bind("playerChatId", generateChatId())
                .executeAndReturnGeneratedKeys()
                .map(UserDao::mapUser)
                .one());
  }

  /** Load the user for an authenticated identity, if the row still exists. */
  public Optional<User> findByIdentity(final Identity identity) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(FIND_BY_IDENTITY)
                .bind("provider", identity.provider())
                .bind("subject", identity.subject())
                .map(UserDao::mapUser)
                .findOne());
  }

  /** A public, opaque, server-assigned handle. Collisions are astronomically unlikely. */
  private static String generateChatId() {
    return "p-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  private static User mapUser(final ResultSet rs, final StatementContext ctx) throws SQLException {
    return new User(
        rs.getLong("id"),
        rs.getString("oauth_provider"),
        rs.getString("oauth_subject"),
        rs.getString("display_name"),
        rs.getString("player_chat_id"),
        rs.getString("role"),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)),
        toInstant(rs.getObject("last_login_at", OffsetDateTime.class)));
  }

  private static Instant toInstant(final OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
