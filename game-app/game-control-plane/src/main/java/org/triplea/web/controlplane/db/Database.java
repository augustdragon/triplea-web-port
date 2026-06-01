package org.triplea.web.controlplane.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.triplea.web.controlplane.config.ControlPlaneConfig;

/**
 * The Postgres source-of-truth seam: a pooled {@link javax.sql.DataSource} (HikariCP, never a raw
 * connection per request) fronted by {@link Jdbi} for parameterized SQL. {@link #create} also runs
 * Flyway migrations on boot, so the schema is current before any route serves traffic.
 */
@Slf4j
public final class Database implements AutoCloseable {

  private final HikariDataSource dataSource;
  private final Jdbi jdbi;

  private Database(final HikariDataSource dataSource, final Jdbi jdbi) {
    this.dataSource = dataSource;
    this.jdbi = jdbi;
  }

  /** Open the pool, migrate the schema to the latest version, and return a ready handle. */
  public static Database create(final ControlPlaneConfig config) {
    final HikariConfig hikari = new HikariConfig();
    hikari.setJdbcUrl(config.dbUrl());
    hikari.setUsername(config.dbUser());
    hikari.setPassword(config.dbPassword());
    hikari.setPoolName("control-plane-pool");
    // Fail fast instead of blocking the default 30s when the DB is unreachable — keeps /health (and
    // every real request) responsive enough to gate traffic. Hikari's initializationFailTimeout
    // default (>0) also means a DB that is down at boot aborts startup, which is what we want.
    hikari.setConnectionTimeout(5_000);
    final HikariDataSource dataSource = new HikariDataSource(hikari);

    final var migrateResult =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
    log.info(
        "Schema at version {} ({} migration(s) applied this run)",
        migrateResult.targetSchemaVersion,
        migrateResult.migrationsExecuted);

    return new Database(dataSource, Jdbi.create(dataSource));
  }

  /** The JDBI handle for parameterized queries. */
  public Jdbi jdbi() {
    return jdbi;
  }

  /** True if a trivial query round-trips — backs the {@code /health} DB check. */
  public boolean isReachable() {
    try {
      return jdbi.withHandle(handle -> handle.createQuery("SELECT 1").mapTo(Integer.class).one())
          == 1;
    } catch (final RuntimeException e) {
      log.warn("Database health check failed: {}", e.getMessage());
      return false;
    }
  }

  @Override
  public void close() {
    dataSource.close();
  }
}
