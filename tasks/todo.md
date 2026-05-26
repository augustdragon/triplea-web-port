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

#### 3b — Bridge + purchase (the mechanism proof) ✅ verified live
- [x] `WebDecisionBridge` — ID-keyed request/response: engine thread parks on a `CompletableFuture`, WS thread completes it. `close()` unblocks on game stop.
- [x] Bidirectional WS: `GameWebSocketServer` (replaces the spectator-only one) carries a `{type}` envelope — `state`/`request` out, `decision` in. Both runners use it. **Request catch-up**: a client joining mid-decision is re-sent the outstanding request (else the engine parks forever).
- [x] `WebPlayer extends AbstractBasePlayer` — `isAi()→false`, all ~30 Player methods stubbed to safe defaults (engine-default casualties, no retreat/scramble/kamikaze, etc.); `start()` handles purchase/bid.
- [x] Hand-build `Set<Player>` in `WebGameHost.startGame(...)`: `WebPlayer` for named human seats + factory AI for the rest. (No engine change, as predicted.)
- [x] `WebPlayer.start()` purchase: read PUs + production frontier (rule name/cost/produces), send to browser, reconstruct `IntegerMap<ProductionRule>` from the name-keyed reply, submit to `IPurchaseDelegate`, validate-loop relaying the delegate's error.
- [x] Browser `PurchasePanel` (per-rule +/- steppers, running cost vs. budget, error banner, Buy / Buy nothing) on the 3a map surface; `App` handles the envelope protocol; new `:game-web-server:runPlayable --args="<gameXml> <humanPlayer> [port] [maxRounds] [stepDelayMs]"`.
- [x] **Exit check MET (verified live):** Japan as a browser human on Pacific 1940 — panel showed the real production list + 26-PU budget; bought 3 infantry + armour + fighter (25 PUs); **Buy** advanced the engine `japanesePurchase → japaneseCombatMove` (delegate accepted). Two bugs caught & fixed in verification: player label must be a single token (engine builds `whoAmI="Human:<label>"`, so `"Human:Web"` → 3 colon-parts crash → use `"Web"`); and the request catch-up above.
- [ ] Not exercised live: delegate **rejection** path (client disables Buy when over-budget; the server validate-loop + error banner are implemented but a rejection wasn't triggered). Note: human seat's move/place auto-pass in 3b, so units bought are not placed (lost) — expected; 3c+ adds those phases.

#### 3c — Combat move (land) ✅ verified live
- [x] `WebPlayer.handleMove` (combat step): loop sending a `kind:"move"` request (carrying `movableUnits` = territory → type → count for units with movement left) until the browser says `{done:true}`; each `{route:[names], units:{type:count}}` reply → resolve `Unit`s from the route's first territory + build `Route` → `IMoveDelegate.performMove` (`Optional<String>`), relay errors. Mirrors `TripleAPlayer.move`.
- [x] Client move mode: click a source (must have movable units) → pick unit counts → click adjacent territories to extend the path → Move / Clear / Done. `MapCanvas` gains a `highlight` prop (orange route outline); click handler ignores re-clicking the tail and only extends to a neighbor (via `geometry.connections`).
- [x] **Exit check MET (verified live, 2nd-ed Pacific):** Japan moved 2 infantry Kiangsu → adjacent **Anhwe** (Chinese, undefended); delegate accepted; on Done the engine resolved combat and Anhwe flipped to **Japanese with 2 infantry**. Adjacency guard confirmed live (Hunan rejected, Anhwe accepted).
- [ ] Deferred to 3d+: multi-unit-from-multiple-sources in one submission, air range/landing UX hints (engine still enforces). (Sea/air/transport-load all done in 3c+ below.)

#### 3c+ — Full movement system (sea, air, transport load) ✅ verified live
- [x] Non-combat move wired: `WebPlayer.start` dispatches `handleMove(false)` on the non-combat step too (same path; place still pending in 3e).
- [x] Enriched move payload: `MoveRequest.movableUnits` is now territory → `List<MovableUnit>` (`{type, count, air, sea, movementLeft}`); `WebPlayer.movableUnits` reports air/sea flags + max movement-left (engine-authoritative, so base/airfield bonuses show automatically) and **includes transported land cargo** (`unitIsBeingTransported`) so it can unload.
- [x] `WebPlayer.buildMove` (extracted, package-visible, unit-tested): resolves route + units from the reply; for a land→sea (`Route.isLoad()`) route builds `unitsToSeaTransports` via `TransportUtils.mapTransports` (transports drawn from the destination sea zone). Sea→land unloads / amphibious assaults and plain moves use the 2-arg `MoveDescription` (engine infers carrying transports). Submitted to `IMoveDelegate.performMove`; rejections relayed.
- [x] Client `MovePanel` consumes the array: ⚓ sea / ✈ air / ▮ land badge + per-type movement-left. Route building unchanged (already crosses sea zones via `geometry.connections`).
- [x] **Hit-test fix (`MapCanvas.territoryAt`)** — iterate territories in **reverse** (topmost-drawn first). Large sea-zone polygons overlap coastal/island land; forward iteration matched the sea zone first, leaving **29/90 land territories (every Pacific island) unclickable**. Required for amphibious/island play.
- [x] **Per-move state broadcast:** the runner only published per engine step, but a move phase runs inside one step, so units looked frozen mid-phase. `WebPlayer.handleMove` now re-projects + broadcasts after each accepted move/undo via a second `Consumer<String>` (`server::publishState`) on the bridge.
- [x] **Undo (history of all moves; any one, or all):** `MoveRequest.undoableMoves` lists this phase's full move history (`UndoableMoveInfo{index,label,canUndo}` from `IMoveDelegate.getMovesMade()`). Each entry includes a `units` summary ("2 infantry, 1 armour", grouped by type from `UndoableMove.getUnits()` via `WebPlayer.summarizeUnits` — mirrors the Swing `AbstractUndoableMovesPanel`'s per-category labels) plus the route. Reply `{undo:index}` → `delegate.undoMove(index)` (any move, not just the last); `{undoAll:true}` → `WebPlayer.undoAll` unwinds last-first. Each undo re-broadcasts so units snap back. `MovePanel` shows a per-move Undo button (disabled when a later move depends on it, `canUndo=false`) + an "Undo all". Works in combat and non-combat phases; matches the original game. Zero engine change. Verified live: 2 moves made, undid the older one individually (cruiser stayed), then Undo all.
- [x] **JUnit** `WebPlayerMoveTest` (8 tests) — guards `buildMove` against the engine's real `MoveValidator` (plain land, land→sea load + validates, no-transport rejection, naval routing, input guards) and `toUndoInfos` mapping. Map geography discovered from the graph (robust across maps).
- [x] **Exit check MET (verified live, 2nd-ed Pacific, Japan):** enriched panel renders badges + movement (battleship move 3 = base 2 +1 naval base, surfaced from the engine); naval move destroyer 6 Sea Zone → 16 Sea Zone (count 2→1 confirmed); transport load 2 infantry Japan → 6 Sea Zone onto a transport (Japan infantry 6→4 confirmed); island selection works after the hit-test fix.
- See `docs/web-port/state-model.md` for how the engine stores/tracks all this state.

#### 3d — Battle resolution
- [x] **3d-1 — Battle log (display forwarding).** `WebDisplay extends HeadlessDisplay` forwards battle events (`showBattle`/`notifyDice`/`casualtyNotification`/`notifyRetreat`/`battleEnd`) as `{type:"battle"}` envelopes via `GameWebSocketServer.publishBattleEvent`; client `BattleLog` renders a live log. `WebPlayableServer` now passes `WebDisplay` instead of `HeadlessDisplay`. Note: the battle's `isHeadless()` is false in a real `ServerGame` (only the odds `BattleCalculator` sets it true), so these callbacks fire. **Verified live:** Kwangsi(4 units) → Hunan(2 Chinese inf); log showed start → dice/hits → "Chinese lost 2 infantry" / "Japanese lost nothing" → "Japanese win"; Hunan flipped Japanese.
- [x] **3d-2 — Interactive `selectCasualties` DONE — verified live.** `WebPlayer.selectCasualties` prompts `bridge.await("selectCasualties", CasualtyRequest{player, location, message, count, options(type→avail), defaultKilled, allowMultipleHits})`; reply `{killed:{type:count}}` → `resolveKilled` (unit-tested) → `new CasualtyDetails(killed, [], false)`. Falls back to the engine default if the bridge is closed. Only fires on a real choice (the engine auto-resolves forced outcomes). Client `CasualtyPanel` pre-fills the default and enables submit only at exactly `count` (so the engine never rejects). **Verified:** Kwangsi→Hunan, both sides scored 1 hit; overrode the default (lose artillery, not infantry) and the log confirmed "Japanese lost 1 artillery" → Japanese win. Deferred: damaging (vs killing) a 2-hit unit when `allowMultipleHits`; trimming 0-hit log lines.
- [ ] **3d-3 — Interactive `retreatQuery`.** `bridge.await("retreat", {battleId, submerge, battleTerritory, options[], message})` → reply territory-name or remain → `Optional`. Attacker general retreat; subs submerge (return battle site).
- [ ] `confirmOwn/EnemyCasualties` stay non-blocking (log already shows casualties).
- [ ] **Exit check:** human fights a *defended* battle to resolution, picking casualties and choosing whether to retreat.

#### 3e — Non-combat move + place
- [x] Non-combat move — dispatched via the same `handleMove` path (done in 3c+).
- [ ] Place panel → place delegate; includes `getNumberOfFightersToMoveToNewCarrier`.

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
