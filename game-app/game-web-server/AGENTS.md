# game-web-server

Backend for the **web port** (see `/docs/web-port/design.md`). Hosts the reused,
unchanged Java engine headless and serves a browser-based React client over
WebSocket + JSON. Build only — no UI here.

## Hard rules
- **Do not modify `game-core`.** This module adapts the engine at its existing
  seams (`Player`, `IDisplay`, `LaunchAction`). If a change to `game-core` seems
  necessary, STOP and reconsider — respect the save-game serialization and
  `@RemoteActionCode` network-compatibility constraints in the root `AGENTS.md`.
- Local work only; never push to the remote (per the web-port initiative).

## What lives here
- `map/` — **MapGeometryConverter**: turns a TripleA map folder's geometry files
  (`polygons.txt`, `centers.txt`) into a `MapGeometry` JSON model for the client.
  Reuses the engine's `org.triplea.util.PointFileReaderWriter` so parsing matches
  the engine exactly.
- (planned) the headless engine host: a `WebLaunchAction implements LaunchAction`,
  a `WebDisplay implements IDisplay`, a `WebPlayer implements Player`, and a
  `StateProjector` (GameData → JSON). Engine-boot recipe is documented in
  `/docs/web-port/SESSION-STATE.md` (template: smoke-testing `GameTestUtils`).

## Dependencies
- `:game-core` only (engine + geometry parser). `:game-headless` will be added
  when the engine host is implemented. gson + JUnit5/Hamcrest come from the root
  build's subproject block — don't redeclare them.

## Conventions
Follow the root `AGENTS.md`: Google Java Format (`./gradlew spotlessApply`),
prefer immutability and `Optional` over null, records for plain data.
