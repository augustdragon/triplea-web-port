# Web Port — TODO

See `docs/web-port/design.md` for the full rationale and architecture.

**Strategy in one line:** reuse the Java `game-core` engine headless; build a
browser-hosted React client that talks to it over WebSocket + JSON. Engine is
NOT modified. First map: World War II Pacific (Pacific 1940).

## Plan

### Phase 0 — Boot engine headless, in-process
- [x] **PREREQUISITE: JDK 21** — installed (Temurin 21.0.11). `:smoke-testing:test --tests AiGameTest` PASSED (3m12s): engine builds + plays a full AI game headless on this machine. (Note: Gradle project paths are flat, e.g. `:smoke-testing`, not `:game-app:smoke-testing`.)
- [x] Create `game-app/game-web-server` Gradle module + its `AGENTS.md` — compiles and passes `:game-web-server:check`. Deps: `:game-core` (gson/junit injected by root build). `:game-headless` to be added with the engine-host path.
- [x] Map geometry converter `MapGeometryConverter` — reads `polygons.txt`/`centers.txt` via the engine's `PointFileReaderWriter`, emits `MapGeometry` JSON. Unit-tested. **Remaining:** read `map.properties` (width/height/scroll-wrap) into the export.
- [x] Territory connections — `GameDataLoader` parses the game XML (engine's `GameParser`, no env setup), `MapConnections` extracts the adjacency graph, merged into the export. Tested on Revised; run on real Pacific.
- [x] Confirm a full game runs to completion (AI) — proven at engine level by `AiGameTest` (reused, not re-implemented).
- [x] ~~Download~~ map already local: `C:\Users\ndhay\triplea\downloadedMaps\world_war_ii_pacific-master.zip` (layout `world_war_ii_pacific-master/map/...`; Pacific 1940 = `games/ww2pac40.xml`).
- [x] Ran converter on the real Pacific 1940 map → `geometry.json` (153 territories, 149 with connections). **Finding:** `Box1/Box2/Box3` (UI decoration boxes) and `Suiyuyan` (polygons/XML name mismatch) exist in geometry but not game data; 0 playable territories lack geometry. Client must tolerate geometry-only territories.
- [x] Drive the engine **from our module** — *parse* path proven (`GameDataLoader` on real `ww2pac40.xml`). Running a `ServerGame` from our module is deferred to Phase 2 (built alongside `WebDisplay`); engine-level run already proven by `AiGameTest`.
- [x] **Exit check (met):** `geometry.json` exported from the real Pacific map with connections; engine parses Pacific 1940 from our code. (Full `ServerGame` loop from our module → Phase 2.)

### Phase 1 — Static rendering
- [x] Scaffold `web-client/` React app (Vite + TypeScript + Canvas 2D).
- [x] Export a one-shot JSON snapshot (geometry + colors + connections + initial owners) — done in Phase 0 via `exportGeometry`.
- [x] Render territory polygons from `geometry.json` on a canvas, tinted by initial owner; center dots drawn. **Verified live** on real Pacific 1940 (153 territories, correct positions/colors, no console errors). Run: `npm --prefix web-client run dev` after copying the export to `web-client/public/geometry.json`.
- [ ] Base map image (baseTiles) under the polygons — deferred (tile compositing is its own sub-task).
- [ ] Unit sprites + stack counts at centers — deferred (needs unit images + a state snapshot of units per territory).
- [ ] Pan / zoom.
- [ ] Refinement: add a `water` flag to the export so sea zones render blue instead of Neutral-tan; hover/click hit-testing.
- [x] **Exit check (polygons+ownership met):** Pacific 1940 renders correctly in the browser, read-only.

### Phase 2 — Live spectator over WebSocket ✅
- [x] `StateProjector` (GameData → `StateSnapshot`: round/step/currentPlayer/owners). No full-graph serialization.
- [x] `WebGameHost` + `WebLaunchAction` run an AI `ServerGame` in-process (in-memory prefs, temp autosaves).
- [x] `SpectatorWebSocketServer` (org.java_websocket) broadcasts snapshots with latest-snapshot catch-up; `WebSpectatorServer` main runs game + publishes per step. Gradle: `:game-web-server:runSpectator`.
- [x] Client subscribes (`ws://host:8080`), feeds live owners to `MapCanvas`, status bar shows round/step/turn.
- [x] **Exit check MET:** watched AI Pacific 1940 advance live in React (round 3→4, Japan conquering China), no console errors.
- Deferred: full `WebDisplay` (IDisplay → push for fine-grained battle events) — built in Phase 3 where it's actually needed; Phase 2 polls state after each step. Also still serving the client via Vite dev + a separate WS port; unifying HTTP+WS under one server is a later cleanup.

### Phase 3 — Hotseat playable ⭐ (first milestone)

**Feasibility confirmed (research, this session): ZERO engine changes needed.**
`PlayerTypes.Type` is an open abstract class (not an enum); `TripleA.newPlayers()`
just calls `type.newPlayerWithName(name)` with no validation, and `ServerGame`/
`startGame()` don't validate player provenance. So we hand-build the `Set<Player>`:
a custom `WebPlayer extends AbstractBasePlayer` for human seats + factory AI for the
rest. The only engine-called method we must get right is `isAi()` → `false`
(`getPlayerType()` is a deprecated default that throws and is never called in-flow).
`TripleAPlayer` is Swing-coupled in `game-headed` (we depend only on `:game-core`),
so we model `WebPlayer` on its *structure* — dispatch in `start(stepName)`, validate
every decision through the phase **delegate** — using our own WebSocket bridge
instead of `CountDownLatch`+EDT.

**Load-bearing primitive — `WebDecisionBridge`:** park the engine thread until the
browser answers. Engine thread calls `bridge.await(request)` → assigns a `requestId`,
registers a `CompletableFuture`, sends request JSON, blocks on `future.get()`. The
WebSocket thread (org.java_websocket, off the game-loop thread) receives
`{type:"decision", requestId, payload}`, completes the future by ID; engine wakes,
deserializes, returns. Invalid input is rejected by the **delegate** (error string)
→ relay to browser, re-prompt. Rules stay enforced by the engine, never the client.
The existing `game.runNextStep()` loop naturally pauses on a human turn because
`start()` blocks. ID-keyed so 3f / Phase 4 (multi-seat) extends cleanly.

#### 3a — Map foundation (view + controls) — makes hotseat usable, no engine play changes ✅
- [x] **Hit-testing (essential):** point-in-polygon → hover/click selects a territory (yellow highlight); foundation for every control. (`MapCanvas.tsx` `territoryAt`/`pointInPolygon`.)
- [x] **Units on the board (essential):** `StateSnapshot.units` (territory → `UnitStack[]` of owner/type/count, empty territories omitted) built read-only in `StateProjector.unitsByTerritory`; client renders total counts at centers + full breakdown in tooltip.
- [x] **Pan / zoom (essential):** drag-pan + wheel-zoom (cursor-anchored), fit-to-view on load. (`MapCanvas` `view` transform.)
- [x] Water flag: `TerritoryGeometry.water` from `Territory.isWater()` in the export; sea zones render blue (`WATER_COLOR`). Real Pacific export = 63 sea zones.
- [x] Hover tooltip + production/capital: export `production` (`TerritoryAttachment.getProduction`) + `capitalOf` (`getCapital`); tooltip shows name / land-or-sea / PU / capital / owner / per-type units. Capital dots drawn at centers (5 capitals on Pacific).
- [ ] Deferred (cosmetic, needs out-of-repo PNG pipeline): base relief image under polygons; real unit sprite art (typed counts suffice to test logic). Scroll-wrap edges deferred too.
- [x] **Exit check MET (verified live):** Pacific 1940 in the browser over the AI spectator runner — hover/click resolves the right territory (e.g. "Jehol — land · PU 1 · owner Japanese" with full unit breakdown), sea zones blue, capital dots, stacks update live as the game advanced (americansTech → chineseEndTurn → anzacTech), no console errors.

#### 3b — Bridge + purchase (the mechanism proof)
- [ ] `WebDecisionBridge` (ID-keyed request/response over the now-bidirectional WS server) + `WebPlayer extends AbstractBasePlayer` skeleton with **all ~30 Player methods stubbed to safe defaults** (accept default casualties, no retreat/scramble) so a full game still runs while only purchase is interactive.
- [ ] Hand-build `Set<Player>` in `WebGameHost`: `WebPlayer` for the human seat + factory AI for the rest; designate which seats are human.
- [ ] `WebPlayer.start(stepName)` dispatches like `TripleAPlayer.start()`; handle the **Purchase** step interactively → submit to `IPurchaseDelegate` (delegate validates, loops on error).
- [ ] Browser purchase panel (production rules, costs, remaining PUs) on the 3a map surface.
- [ ] **Exit check:** a human buys units in the browser, PUs deduct, the delegate rejects illegal buys, and AI plays the other seats to game end.

#### 3c — Combat move
- [ ] Route-building on the clickable 3a map (select units → click destination chain) → `MoveDescription` → `IMoveDelegate.performMove()` (delegate enforces legality; relay errors).
- [ ] **Exit check:** human performs a legal combat move; an illegal one is rejected with the engine's reason.

#### 3d — Battle resolution
- [ ] Real `selectCasualties` + `retreatQuery` panels; void notifications (`reportError`, `reportMessage`, `confirmOwnCasualties`, `confirmEnemyCasualties`).
- [ ] **Exit check:** human fights a battle to resolution, picking casualties and a retreat.

#### 3e — Non-combat move + place
- [ ] Non-combat move (reuses 3c) + Place panel → place delegate; includes `getNumberOfFightersToMoveToNewCarrier`.

#### 3f — Pacific-mandatory naval/air queries
- [ ] `scrambleUnitsQuery`, `selectKamikazeSuicideAttacks`, `selectBombardingTerritory`, `selectTerritoryForAirToLand`, `selectShoreBombard`.

#### 3g — Hotseat seat-switching + tech/politics
- [ ] Pass-and-play: route each seat's queries to the one browser (seat banner / pass screen); tech & politics panels if the map uses them.
- [ ] **Exit check:** a complete Pacific 1940 game, playable start to finish, hotseat on one machine.

### Phase 4 — LAN / ZeroTier multiplayer
- [ ] Seat-claiming / session management (multiple browsers, one server)
- [ ] Route each seat's queries to the owning client; spectators get state only
- [ ] Lightweight auth / room code
- [ ] **Exit check:** two machines play Pacific 1940 over LAN, then over ZeroTier

### Phase 5 — Breadth & durability
- [ ] Run converter across more maps; fix feature gaps (relief blending, scroll-wrap, markers)
- [ ] Tech & politics panels
- [ ] Save/load via engine's existing `.tsvg` serialization (server-side)
- [ ] **Exit check:** a second, structurally different map plays end-to-end

## Notes / decisions
- Engine stays unmodified — if a `game-core` change seems necessary, STOP and reconsider (respect save-game + `@RemoteActionCode` compatibility rules in root `AGENTS.md`).
- Pacific 1940 is a feature-heavy first map (naval/scramble/kamikaze); Phase 3 is larger than it would be for a simpler map. Accepted deliberately.
