# game-control-plane

The **web-port control plane** (see `/docs/web-port/PHASE-4-MULTIPLAYER.md`, phases
P4.3 + P4.4, and the build plan referenced from `tasks/todo.md`). One Java service on
Javalin/embedded Jetty that owns OAuth + sessions, the lobby, the Postgres
source-of-truth, presence, and orchestration of per-game JVM containers. It runs
**separately** from the game containers (`:game-web-server`), which it spawns/reaps.

## Hard rules
- **Do not modify `game-core`.** When this module needs the engine (lazy rehydration in
  M5), it uses only the public `GameDataManager.loadGame` / `GameData.toBytes()` API. If a
  new engine hook seems necessary, STOP and re-plan (root `AGENTS.md` save-game rules).
- Pushing to this fork's own `origin` (`augustdragon/triplea-web-port`) is fine — it's the
  cross-machine sync point. **Never push to the upstream `triplea-game/triplea` repo.**
- **Config from env vars only**, validated at startup — fail fast if a secret is missing.
  Never hardcode secrets.

## What lives here (built incrementally, M1→M5)
- `ControlPlaneMain` — Javalin bootstrap; loads + validates config, runs migrations, wires routes.
- `config/` — `ControlPlaneConfig`: typed env-var config with startup validation.
- `db/` — `Database`: HikariCP `DataSource` + JDBI `Jdbi`; runs Flyway `migrate()` on boot.
  Migrations are pure SQL under `src/main/resources/db/migration/` (`V<compat>.<feature>.<patch>__desc.sql`).
- `http/` — REST controllers (`HealthController` now; auth/lobby/game routes later).
- `orchestrator/` — `GameLauncher` seam: `ProcessGameLauncher` (local child process, dev)
  now; `DockerGameLauncher` (container per game) in M5.
- (later) `auth/`, `user/`, `lobby/`, `game/` per the milestone plan.

## Dependencies
- Javalin (HTTP/WS), HikariCP + JDBI 3 + PostgreSQL driver + Flyway (Postgres source-of-truth).
- The root build's subproject block injects gson/guava/lombok/logback. The legacy
  `dropwizard-websockets` (Jetty 9) stack is **excluded** in `build.gradle.kts` — this module
  embeds Jetty (via Javalin); two Jetty versions must not share the classpath.
- **No shadow/fat-jar here** (unlike `:game-headless`): a fat jar merges `META-INF/services`
  lossily under the current shadow version and Flyway silently loses its SQL-migration resolver.
  Run via the `application` plugin (`run` / `installDist`), which keeps each dependency as its own
  jar so ServiceLoader works; that distribution is also what we containerize in P4.5.
- `:game-core` / `:domain-data` are added in M4b/M5 (lazy rehydration), not before — M1–M3
  don't touch the engine.

## Conventions
Follow the root `AGENTS.md`: Google Java Format (`./gradlew spotlessApply`), prefer
immutability and `Optional` over null, records for plain data, `@Slf4j` for logging.
Tests use **JUnit 5 + Hamcrest `assertThat`** (not AssertJ), matching the rest of the codebase.
