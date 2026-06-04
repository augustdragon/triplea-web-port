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
- [x] **3d-1b — Round markers + surviving forces.** `WebDisplay.gotoBattleStep` watches the battle's authoritative `getBattleRound()` (via `BattleTracker.getPendingBattle(id)`, `setGameData` wired after `startGame`) and, when it advances, emits a `kind:"round"` event with the round number + remaining `getAttackingUnits()/getDefendingUnits()` (per-type summary). Client renders a "── Round N ── attacker: … · defender: …" header. Sets up the retreat decision (player sizes up forces before the between-rounds pause). **Verified live:** "── Round 2 ── Japanese: 1 artillery, 3 infantry · Chinese: 1 infantry".
- [x] **3d-2 — Interactive `selectCasualties` DONE — verified live.** `WebPlayer.selectCasualties` prompts `bridge.await("selectCasualties", CasualtyRequest{player, location, message, count, options(type→avail), defaultKilled, allowMultipleHits})`; reply `{killed:{type:count}}` → `resolveKilled` (unit-tested) → `new CasualtyDetails(killed, [], false)`. Falls back to the engine default if the bridge is closed. Only fires on a real choice (the engine auto-resolves forced outcomes). Client `CasualtyPanel` pre-fills the default and enables submit only at exactly `count` (so the engine never rejects). **Verified:** Kwangsi→Hunan, both sides scored 1 hit; overrode the default (lose artillery, not infantry) and the log confirmed "Japanese lost 1 artillery" → Japanese win. Deferred: damaging (vs killing) a 2-hit unit when `allowMultipleHits`; trimming 0-hit log lines.
- [x] **3d-3 — Interactive `retreatQuery` DONE — verified live.** `WebPlayer.retreatQuery` prompts `bridge.await("retreat", RetreatRequest{player, battleTerritory, submerge, options[], message, attackers, defenders})` — the surviving force summaries (read from the battle via `getAttackingUnits()/getDefendingUnits()`) are included so the choice is informed. Reply `{retreatTo:name}` → the matching `Territory` (engine validates membership; submerge = the battle site); `{remain:true}`/anything else → `Optional.empty()`. Falls back to staying if the bridge is closed. Client `RetreatPanel` shows forces + a button per destination + "Stay and fight". **Verified:** attacked Hunan with 2 inf; after round 1 (1 inf each side) the panel offered "Retreat to Kwangsi"; retreated → log "Japanese retreats all units to Kwangsi", battle ended, units returned to Kwangsi.
- [x] `confirmOwn/EnemyCasualties` left non-blocking — the log + casualty events already show losses; no pause needed.
- [x] **Exit check MET:** human fought defended battles to resolution — picked casualties (3d-2, overrode the default) and chose to retreat (3d-3) — with a round-by-round log + force counts throughout.

**3d DONE.** Battle resolution is interactive end-to-end. Deferred polish: damaging (vs killing) a 2-hit unit; trimming 0-hit log lines; sub submerge not yet exercised live (Pacific naval). Next milestone → 3e place; then 3f Pacific naval/air queries, 3g hotseat.

#### 3e — Non-combat move + place ✅ verified live
- [x] Non-combat move — dispatched via the same `handleMove` path (done in 3c+).
- [x] **Place units.** `WebPlayer.handlePlace` (on `GameStep.isPlaceStepName`) loops `bridge.await("place", PlaceRequest{player, bid, toPlace=PlaceUnit[]{type,count,air,sea}, error})` over the unplaced pool (`player.getUnitCollection().getUnits()`, re-read each iteration — the delegate removes placed units) until `{done:true}` or the pool empties. Reply `{territory, units:{type:count}}` → `resolveByType` (renamed from `resolveKilled`, shared with casualties; unit-tested) → `IAbstractPlaceDelegate.placeUnits(units, at, BidMode)`; engine validates (factory, caps, sea adjacency) and the rejection relays as `error`. Client `PlacePanel` (in the sidebar): click a target territory → pick units → Place / Done; map highlights the target. `getNumberOfFightersToMoveToNewCarrier` still returns empty (safe default; interactive carrier-fighter deferred). **Verified live:** full Japan turn — bought 3 infantry, Done through combat/non-combat move, placed all 3 in Japan (pool emptied, no rejection).

#### 3e+ — Post-3d/3e polish & fixes (between 3e and 3f)
- [x] **Battle log redesign (replaces 3d-1's streaming feed).** `WebDisplay` now accumulates each battle (round, attacker, defender, location, per-side losses totalled from `casualtyNotification`) and emits ONE `{kind:"result"}` envelope at `battleEnd`; the client `BattleLog` groups results by game round → attacking nation. Compact historical record instead of per-dice/round chatter. Live in-battle force counts for a retreat decision now come from the `RetreatPanel` itself, not the log. (Note: 3d-1 / 3d-1b's `{kind:"round"}` / dice-stream events were retired.)
- [x] **UI consolidation.** All controls + info moved into one fixed right `Sidebar`: status header (round/turn/WS), a `PhaseIndicator` (engine step → canonical phase, current row highlighted), the active decision panel (purchase/move/casualty/retreat/place — plain blocks, not floating windows), and the `BattleLog` at the bottom. Replaces the old scattered upper-right move panel + lower-left battle log.
- [x] **Manual-testing helper.** `scripts/restart-web.ps1` stops the running game on the port, ensures the Vite client is up, and launches a fresh game in the foreground (Ctrl+C to stop). Params: `-Player`, `-Port`, `-MaxRounds`, `-StepDelayMs`, `-GameXml`.
- [x] **"Select all units" button** in the `MovePanel`: picks every eligible unit in the source territory at once (`movable` is already filtered server-side to units with movement left, so it never picks a spent unit; engine still validates the specific route on submit).
- [x] **Sea zones labeled "Sea Zone N"** (board convention; was "N Sea Zone"). `web-client/src/territoryName.ts` `displayTerritory`, used wherever a territory name is rendered (Route, Battle log, undo entries).
- [x] **🐛 Combat phase actually fights (critical).** `WebPlayer` never handled the battle step — the engine does NOT auto-fight; the seat must drive it. Symptom: a player's combat moves never resolved, attacker + defender sat co-located, and the turn skipped to non-combat move (user reproduced this in Chinese-held Kiangsi, Round 3). Fix: `handleBattle()` mirrors `AbstractAi.battle()` — loop `IBattleDelegate.getBattleListing().getBattlesMap()` and call `fightBattle(where, type.isBombingRun(), type)` until none remain, ignoring `BattleDelegate.isBattleDependencyErrorMessage` (dependency-order) errors; wired to `GameStep.isBattleStepName` in `start()`. Verified live: Japan combat-move into Chinese-held Hunan → Combat phase now fights it (retreat prompt + casualties + grouped battle-log entry) instead of skipping.
- [x] **Click-to-toggle territory selection.** Clicking an area that's already selected deselects it — no Clear button needed. In **place** mode, re-clicking the target clears it. In **move** mode, clicking any territory already in the route truncates the route to before it (removes it and, since a route is a path, everything after); clicking the sole start empties the route. Replaces the old "ignore re-click of the tail" behavior. Verified live: `Kiangsu → Anhwe → Hopei` clicked-on-Anhwe → `Kiangsu`, clicked-on-Kiangsu → empty.

#### 3e++ — Politics / declarations of war, relationships, air-can't-land warning, layout + e2e ✅ verified live
- [x] **Politics phase (first step of each turn).** `WebPlayer.handlePolitics` (on `GameStep.isPoliticsStepName`) offers the player's currently-legal political actions (`IPoliticsDelegate.getValidActions`) — chiefly declarations of war. **Staged-commit model for true in-phase undo:** the browser queues declarations (each individually removable) and nothing reaches the engine until "End phase"; on commit, `applyStaged` re-checks `getValidActions` before each `attemptAction` and reports any superseded by another (the engine has **no political undo** — `IPoliticsDelegate` is only `getValidActions`/`attemptAction`). DTOs `PoliticsRequest`/`PoliticalActionOption` (concise "Declare war on X" summary derived from the action's war-targets, full relationship ripple on hover, PU cost shown only when nonzero). Client `PoliticsPanel`. **The whole political *system* is already in the unchanged engine + Pacific XML** (relationship types incl. `Unprovoked`, DoW actions, mandatory US entry at end of round 3, +30 PU mobilization, movement-restriction lifts, China province liberation, and DoW→movement gating via `MoveValidator`) — and the AI seats already drive their own politics. So this is UI plumbing only; the human seat was the one gap. Verified live (Japan, 2nd ed): declared war on France; relationship flipped to War; turn advanced to purchase.
- [x] **Relationships grid.** `StateProjector` projects the full inter-player relationship matrix into `StateSnapshot` (`players` + `relationships[a][b]` = `RelationshipCell{type, category}`, category from `RelationshipTracker.isAtWar/isAllied`). Client `RelationshipsModal` (opened from a sidebar button) renders an N×N grid colored war/allied/neutral, labelled with the engine type name (War/Neutrality/Allied/Custodianship/Unprovoked…). Reads live state, so it reflects declarations as they happen. (Note: changing the `StateSnapshot` shape requires a **server restart**, not just client HMR.)
- [x] **Air-can't-land warning (non-combat move).** `WebPlayer.keepMovingToSaveAir` mirrors `TripleAPlayer.canAirLand`: on ending a move phase that removes stranded air (`GameStepPropertiesHelper.isRemoveAirThatCanNotLand`), query `IMoveDelegate.getTerritoriesWhereAirCantLand(player)`; if non-empty, send an `airWarning` request instead of ending. Client `AirWarningPanel` lists the at-risk territories as **clickable pills that pan/center the map** on them (new `MapCanvas` `focus` prop, nonce-guarded against resize re-pans), plus "Keep moving" / "End anyway (lose aircraft)".
- [x] **Layout: bottom action bar + full-bleed map.** All decision panels moved out of the right sidebar into a bottom action bar (spanning left of the sidebar); sidebar keeps status → ⚔ Relationships → `PhaseIndicator` → `BattleLog`. `MapCanvas` made responsive — fills the window and tracks resize (was a fixed 1500×920 box that left a dead band). `phase.ts` adds **Politics** as the first phase.
- [x] **Playwright e2e suite** (`web-client/e2e/politics.spec.ts`, `playwright.config.ts`, `e2e/README.md`): 5 tests — load+connect as Japan, the four declarations offered, opening relationship matrix, staging-is-reversible, commit→War→advances-to-purchase. **5 passed.** Added `data-testid`s on the relationship grid; `@playwright/test` devDep; Vite auto-start via `webServer`. Scope = DOM-driven phases only (the map is a `<canvas>`, so move/place/battle/air-warning need pixel clicks — out of scope).
- Commits: `0fb2f91df` (politics + relationships + layout), `1d68777d4` (air warning), `88ddeeba3` (Playwright).
- Deferred polish: optional "already at war with: …" note in the politics panel (offered, not built); tracing the exact US-entry/mobilization trigger conditions; `acceptAction` still returns `false` (Pacific DoW actions don't use `actionAccept`, so untested).

#### 3f — Pacific-mandatory naval/air queries
- [ ] `scrambleUnitsQuery`, `selectKamikazeSuicideAttacks`, `selectBombardingTerritory`, `selectTerritoryForAirToLand`, `selectShoreBombard`.

#### 3g — Hotseat seat-switching + tech
- [x] Politics panel — done in 3e++ (declarations of war; staged-commit with in-phase undo).
- [ ] Pass-and-play: route each seat's queries to the one browser (seat banner / pass screen). **Constraint surfaced this session:** there's currently ONE game + one human seat, and `GameWebSocketServer` broadcasts every decision to *all* connected clients (whoever replies first drives); the client has no WS auto-reconnect. Hotseat/multi-seat needs per-seat request routing (the bridge is already `requestId`-keyed).
- [ ] Tech panel if the map uses it.
- [ ] **Exit check:** a complete Pacific 1940 game, playable start to finish, hotseat on one machine.

#### 3h — Information panels (bottom tab dock + minimap)  ⟵ NEXT (planned 2026-05-30)
Mirror the base game's right-hand `JTabbedPane` (`TripleAFrame.rightHandSidePanel`), but
move the tabs to a **full-width bottom dock** for horizontal room (user request). Base-code map:
Players=`StatPanel`, Resources=`EconomyPanel`, Objectives=`ObjectivePanel`, Territory=
`TerritoryDetailPanel`, Notes=`GameNotes.loadGameNotes` (`<game>.notes.html` sibling of the XML),
Actions=`ActionButtonsPanel` (= our existing decision panels). Stat formulas live in
`game-core/.../engine/stats/` (`PuStat`/`ProductionStat`/`UnitsStat`/`TuvStat`/`VictoryCityStat`).
**Decisions (2026-05-30):** sidebar keeps minimap+status+phase+battle log, Relationships stays a
modal; six tabs go in the bottom dock. First pass = layout shell + Players/Resources/Territory/Notes.
**Feasibility note:** `TuvStat`/`UnitsStat` call `mapData.shouldDrawUnit(...)` and we run headless with
no `MapData` → reimplement the ~5 stat formulas server-side (TUV via `TuvCostsCalculator.getCostsForTuv`,
needs no MapData; count all units). ⚠ Any `StateSnapshot` shape change needs a **server restart** (not HMR).

- [x] **Step 1 — Layout shell (client-only) ✅ verified live.** `BottomDock.tsx` (tabs `Actions | Players |
  Resources | Objectives | Notes | Territory`, collapsible via ▼/▲); **Actions** renders the active decision
  panel (an idle message otherwise) and a `useEffect` auto-selects Actions + expands the dock when a request
  arrives, badged with ●. Info tabs show placeholders for now. `Minimap.tsx` (static, owner-tinted whole-map
  overview; reuses `fillColor` exported from `MapCanvas`) sits atop a narrowed (300px) `Sidebar`, which keeps
  status / Relationships / `PhaseIndicator` / `BattleLog`. `App.tsx` swapped the inline bottom action bar for
  `<BottomDock>`. `tsc --noEmit` clean. Verified in Chrome against a live Japanese game: dock renders,
  Actions held the Purchase panel, tab-switch → Players placeholder, collapse/expand works, tooltips intact.
  (Map still fills the window under the overlays; reserving map area is deferred.)
- [x] **Step 2 — Players tab ✅ verified live (exact match to base StatPanel).** `PlayerStatsProjector`
  computes `PlayerStat{player, alliances, pus, production, units, tuv, victoryCities, income}` per power and
  `StateProjector` adds `playerStats` to `StateSnapshot` (live — recomputed every snapshot, incl. mid-phase).
  Formulas reimplemented without `MapData` (TUV via `TuvCostsCalculator`; counts ALL units vs base's
  `shouldDrawUnit` filter — immaterial on Pacific, which draws all types). Client `PlayersTab.tsx` renders the
  table (Player/PUs/Production/Units/TUV/VC, faction-tinted names) + an italic per-alliance total row (>1
  member). `PlayerStatsProjectorTest` (6 tests) cross-checks each field vs an independent read. Verified live
  vs the reference screenshot: every value matched (Japan 26/26/94/667/2; Allies 55/55/105/835/6). NOTE: the
  passive trio (French/Dutch/Russians) correctly shows Production but 0 PUs/0 units — see [[pacific-passive-factions]].
- [x] **Step 3 — Resources tab ✅ verified live (exact match to base EconomyPanel).** Extended `PlayerStat`
  with `resources: List<ResourceCell{name, amount, income}>` (every resource except VPs, in engine order;
  replaced the old scalar `income`). `PlayerStatsProjector` builds it from `player.getResources()` +
  ONE `findEstimatedIncome` call per player. Client `ResourcesTab.tsx` renders a column per resource as
  `amount (+income)` + per-alliance total rows. Pacific shows `techTokens | PUs | SuicideAttackTokens`.
  Verified live vs the reference screenshot: Japan PUs `26 (+36)` (income > production = national-objective
  bonuses, exactly as base), Japan kamikaze tokens `6`, Allies PUs `55 (+61)`. Test gained a guard that
  resources exclude VPs and the PUs cell == the `pus` field. `:game-web-server:check` (7 tests) + `tsc` clean.
  - **Refinement (client-only):** the base EconomyPanel gives every resource its own column, but PUs is the
    only spendable economy; `techTokens`/`SuicideAttackTokens` are consumable counters (Japan-only, never
    increase, no income → a meaningless `(+0)`). So the tab now shows **PUs as the one column** (amount +
    income, with alliance totals) and renders any other held resource as a **count-only chip** on the owning
    row (e.g. Japan `⚡ 6`), with a glyph legend. All-zero resources (techTokens at start) show nothing. No
    server/snapshot change — the projection still sends all resources; the client chooses column vs chip.
- [x] **Dock polish (user-requested):** (1) **Static dock height** — the content area is a fixed `40vh`
  (`BottomDock`) instead of `maxHeight`, so the tab bar no longer jumps as you switch tabs (short tabs
  leave empty space, tall ones scroll). (2) **Purchase columns** — the purchase list is grouped into
  **Land | Air | Naval | Buildings** columns. Server `PurchaseOption` gained a `category` computed in
  `WebPlayer.categoryOf` (AA / canProduceUnits / infrastructure / construction → building — catches
  Pacific's mobile `aaGun` via the AA check; else sea→naval, air→air, else land); client `PurchasePanel`
  renders a column per non-empty category (unknown categories fall into an "Other" column as a safety net).
  Purchase panel widened to full width in `App`. Verified live: 4 columns correct, aaGun under Buildings.
- [x] **Step 4 — Territory tab (client-only) ✅ verified live.** `TerritoryTab.tsx` pins to the last-clicked
  territory (lifted into `App` as `selectedTerritory`, set on every map click regardless of phase; passed
  through `BottomDock`). Shows name (Sea-Zone-N convention), sea/land + production, capital, owner (faction-
  tinted), and units grouped by owner — all from the existing `units` snapshot + geometry. Verified live:
  Sea Zone 20 (16-unit Japanese fleet incl. transported cargo + air), Sea Zone 19 (0 units → "no units"),
  Anhwe (land · production 1 · owner Japanese · artillery ×1 + infantry ×3). Capital line uses the same
  `capitalOf` field the hover tooltip renders ("Japanese capital"). Battle Calculator / Add Attackers / Add
  Defenders / Find buttons = deferred. `tsc` clean. Observed: a new decision request auto-switches the dock
  back to the Actions tab (the focus-on-request effect) — fine, but interrupts info-tab browsing (see deferred).
- [x] **Step 5 — Notes tab ✅ verified live (exact match to base).** Notes for this map live in the game XML
  as `<property name="notes">` (no standalone `.notes.html`), and the parser stores non-editable properties,
  so `GameData.getProperties().get("notes", "")` returns the HTML directly — **no `:map-data` dep and no
  file-write side effect** (avoided `GameNotes.loadGameNotes`, which would migrate-write a file). `GameController`
  reads it once at `start()` and broadcasts a `{type:"notes",html}` envelope; `GameWebSocketServer` caches it
  (`notesEnvelope`) and re-sends on connect (survives `newGame`). Client: `App` stores `notesHtml`; `NotesTab.tsx`
  renders the HTML via `dangerouslySetInnerHTML` (trusted local map content — noted; sanitize if remote maps
  ever load). Verified live: full notes render (Credits/Note/Disclaimer/Rules…), scrollable, matching the base
  game's Notes tab. `:game-web-server:check` + `tsc` clean.
- [x] **Relationships → dock tab ✅ (commit `e152ea291`).** Was a sidebar button + modal; now an inline
  dock tab (`RelationshipsTab`) rendering the N×N grid from the snapshot. Modal + button removed; e2e updated.
- [x] **Objectives tab ✅ verified live (exact match to base ObjectivePanel).** `objectives.properties` lives
  in the map root (extracted from the zip; keyed by normalized game name `World_War_II_Pacific_1940_2nd_Edition.`).
  `ObjectivesProjector` replicates `ObjectivePanel.setObjectiveStats`: parse `TABLEGROUP` sections (ordered) +
  `<player>;<attachment>` objective text, resolve each to an `ICondition` via `AbstractPlayerRulesAttachment
  .getCondition`, evaluate read-only with `AbstractConditionsAttachment.testAllConditionsRecursive` +
  `ObjectiveDummyDelegateBridge` (discards changes, random→0, so no game mutation). `GameController` loads the
  props once (`gameXml.parent.parent/objectives.properties`) and publishes a `{type:objectives,items}` envelope
  at each step boundary; `GameWebSocketServer` caches + re-sends on connect. Client `ObjectivesTab` groups by
  faction-tinted section with ✓/○ markers + HTML text (dark-themed). Verified live: Japan diplomatic objective ✓,
  Chinese Burma Road ✓, all others ○ — matches the reference screenshot; server log clean (no eval errors).
- [ ] **Deferred (later pass):** Technology sub-table (`TechTracker`); Battle Calculator (large standalone
  feature). No `ObjectivesProjector` unit test (would need a map fixture with objectives.properties; verified
  live against the base game's exact output instead).
- [ ] **Deferred — interactive minimap.** Today `Minimap.tsx` is a *live* owner-tinted overview (redraws as
  ownership changes) but has NO interactivity. Add: (a) a viewport rectangle showing `MapCanvas`'s current
  pan/zoom, and (b) click/drag-to-recenter the main map. Needs lifting `MapCanvas`'s `view {scale,offsetX,
  offsetY}` up (or sharing it) and a minimap→map-space click translation; `MapCanvas` already has a `focus`
  prop for recentering. (User confirmed 2026-05-30: fine to defer as long as it's tracked.)
- [ ] **Deferred — info-tab focus stealing.** A new decision request auto-switches the dock to the Actions
  tab (intended, to surface the decision), but it interrupts browsing the Players/Resources/Territory tabs.
  Consider only auto-switching if the user hasn't manually selected an info tab, or a subtler cue.
- [x] **Exit check MET (+ exceeded).** All seven tabs (Actions / Players / Resources / Relationships /
  Objectives / Notes / Territory) render live and correct against `runPlayable`; stats + objectives
  exact-matched the base game; tabs switch under a static-height dock; map fills the window; `New game`
  reset + minimap + purchase columns added. `:game-web-server:check` + `tsc --noEmit` clean throughout.
  Deferred: Technology sub-table, interactive minimap, Battle Calculator, info-tab focus-stealing fix.

#### 3h tooling — in-browser "New game" reset ✅ verified live
- [x] **Server-side game lifecycle + in-process reset.** Extracted the game loop out of `WebPlayableServer.main`
  into a new `GameController` that runs each game on its own daemon game-loop thread behind one long-lived
  `GameWebSocketServer`. A client `{type:"control",action:"newGame"}` message stops the current session
  (`alive=false` + `WebDecisionBridge.close()` to unpark the engine thread parked in `await`, then `join`) and
  starts a fresh game on the same socket — no JVM bounce, no page reload. Deliberately does **not** call
  `ServerGame.stopGame()` (it can `ExitStatus.exit()` the JVM if it can't block delegate execution); abandons
  the old `ServerGame` to GC instead (resets are occasional, so the small retained state is fine). `main` now
  just builds the controller and parks on a `CountDownLatch`. Added `GameWebSocketServer.resetForNewGame()`
  (drops cached catch-up state + outstanding request). Client: `App.newGame()` sends the control message and
  clears local UI state (request/battle-log/move/place); a `⟳ New game` button in the sidebar.
  **Verified live:** committed a French DoW (→ Purchase, France at war), hit New game → clean Round 1 Politics
  with "Declare war on French" available again; reset-while-parked-on-a-decision works and is repeatable; server
  stays live (no JVM exit). `:game-web-server:check` + `tsc` clean.

#### Post-3h — Movement redesign + map/UI polish ✅ verified live (session 2026-05-31)
- [x] **Players + Resources tabs merged → one "Players" tab; IPC labels.** Folded Resources into Players:
  the IPC cell carries inline income (`26 (+36)`) and token chips (kamikaze/tech) ride beside the holder's
  name; passive minors (Russians/French/Dutch) collapse into one **"Other"** group via a new server-side
  `passive` flag (engine's `GamePlayer.getOptional()` — they're `optional="true"` in the map XML); the Allies
  block gets an accounting-style subtotal (flush-left label, rule above the figures). All user-facing "PU(s)"
  relabeled **IPC** (the engine resource stays `"PUs"`; display-only — `Constants.PUS` and the `PUS` match
  constant unchanged). Objectives/Notes HTML left verbatim (map-authored text). Commit `3663239da`.
- [x] **Map-data fix — `Suiyuyan` duplicate + decoration boxes.** `polygons.txt`/`centers.txt` had a
  misspelled `Suiyuyan` with the *same* shape/center as the Chinese-owned `Suiyuan`, overdrawing it as an
  empty neutral (user-reported), plus standalone corner `Box1/2/3`. `MapGeometryConverter` now (a) **drops** a
  geometry-only polygon that duplicates a real territory's center, and (b) **folds** standalone decoration into
  the nearest land territory as one bounding-box filler rectangle (→ Yukon) so the corner paints that
  territory's color instead of leaving a notch. Pure helpers, unit-tested. Commits `c8271f740`, `fc5b96a29`.
  ⚠ `web-client/public/geometry.json` is **gitignored** — re-run `:game-web-server:exportGeometry "<mapFolder>
  <absOut> <gameXml>"` (output path must be ABSOLUTE — it resolves relative to the module dir) after a pull.
- [x] **IPC value roundels on the map.** Each land territory with production draws its IPC value in a fixed
  tan circle just below center (physical-board "value in a circle" convention); sea zones / 0-value land show
  none. Unit-count badge stays at center; roundel position is **fixed** (doesn't hop as units come/go — a
  stable map element). Commit `0fcedfa6b`.
- [x] **⭐ Click-destination movement with live preview (replaces step-by-step path building).** Click a
  source → pick units → click any destination; the server finds the best legal route via the engine's
  `MoveValidator.getBestRoute(start,end,data,player,units,!isAirborneMove)` — the SAME routing the Swing
  `MovePanel` uses — and echoes it back as a `preview` the client highlights, with movement cost + a per-type
  "can't reach" warning, before the player commits. **Unit-aware** (air re-routes over water for a shorter
  path; recomputed when the unit selection changes). The preview rides the existing decision await loop as a
  **re-prompt**: a `{previewRoute:{from,to,units}}` reply → `WebPlayer.previewMove` computes → `continue` to
  re-await `"move"` with `MoveRequest.preview` set (exactly how the `error` field re-prompts) — no new bridge
  channel needed (the bridge is strictly requestId-keyed request/reply, see `WebDecisionBridge`). The move
  itself replies `{from,to,units}`; `submitMove` runs `getBestRoute` → existing `buildMove`/`performMove`
  (transport loads, validation, undo all unchanged). New record `MovePreview`. Commit `4c19cd9f5`. Verified
  live: Manchuria→Kiangsu auto-pathed 4 territories; switching to fighters re-routed Manchuria→Sea Zone 19→
  Kiangsu; move executed + undo recorded.
- [x] **🐛 Transported cargo no longer flagged "can't reach".** The preview compared each unit's *own*
  movement to the route cost; cargo aboard a transport has 0 movement (it rides along), so loaded inf/arty were
  wrongly flagged (user-reported). `previewMove` now skips `Matches.unitIsBeingTransported()` units in both the
  cost and the can't-reach check (the same matcher `movableMatch` uses to surface cargo). Commit `3f195e14b`.
  Verified live: loaded inf+arty onto a transport, moved the transport — cargo not flagged, carried to the
  destination.
- [ ] **Deferred (movement):** mixed-reachability on **Move** — if you include units that can't *all* reach,
  the engine rejects the whole move (the "can't reach" tag warns; you deselect manually). The Swing client
  silently moves only the reachable subset (`MovableUnitsFilter`) — consider matching that, or disabling Move
  while a blocked type is selected. No automated test yet for the click-destination move path or `previewMove`
  (would need a loaded-transport game fixture; verified live instead); the Playwright suite predates this.

### Phase 4 — Multiplayer hosting (server-authoritative SaaS-style)  ⟵ NEXT
> **Full design: `docs/web-port/PHASE-4-MULTIPLAYER.md`** (written this session, P4.0). Direction shifted from
> the old "LAN/ZeroTier" idea to a **true server** that replaces the legacy player-hosted headless bot:
> browser-only (zero install), the engine runs on *our* server, engineered cleanly for a private group now but
> scalable to a SaaS later. Engine stays unmodified throughout.

**Decisions locked (P4.0):**
- **One JVM per active game** — *forced*, not chosen: `game-core` has process-global static state
  (`GameData.current`, `GameState.started`, `RemoteRandom`, `ClientSetting`) that two games in one JVM would
  corrupt, and we can't fix it without editing the engine. `HeadlessGameServer` hosts one game per instance too.
- **Java control plane** + **Postgres** + per-game game JVMs; **social OAuth** (Google/Discord), httpOnly cookies.
- **Async-resumable, DB-backed** persistence: DB is source of truth; **flush on every committed turn** (our turn
  rate is glacial, so we never lose a committed move — stricter than Lichess's periodic flush); lazy-rehydrate
  the game JVM on reconnect via `GameData.toBytes()` / `GameDataManager.loadGame`.
- **Reconnection:** version every game event; client sends `lastSeenVersion`; server replays the tail (Lichess
  pattern — replaces today's crude "replay latest snapshot to anyone").
- **Abandonment:** timed-out seat → **AI takeover** (engine AI caretakes); escalating play-bans for repeat griefers.
- **Schema:** greenfield, our own Flyway history. Upstream `spitfire-server` lobby (deleted upstream mid-2024)
  has no game-state/seats/resume — its game model doesn't fit. Moderation/audit DDL is a **deferred transplant**
  (not v1 — a private allow-list has no one to moderate; the allow-list *is* the access control).
- **Save store:** Docker volume now → R2/S3 later via the storage seam. **Host:** Docker Compose on the VM.

**Build-out phases (each shippable):**
- [x] **P4.0 — Recon** (this session) — process model settled, reuse audit done, Lichess/BGA patterns captured,
      spec written + committed (`34619e2f6`).
- [x] **P4.1 — Multi-seat single game** ✅ — playable server now has a **pre-game seat-setup phase** (claim seats
      as human / assign any AI type, reusing the engine's `PlayerListing`/`PlayerTypes`) and **seat-routed,
      seat-validated** in-game decisions: each WS connection binds to a seat, requests/replies are seat-tagged,
      cross-seat & spectator replies rejected (`WebDecisionBridge`). Optional/inert minors excluded from the
      picker. Commits `5ca3d54d0` (a, server), `38f60ac9f` (b, client). Verified: 2-client probe (routing +
      rejection) + browser claim flow. Seat claims are *trusted* until auth (P4.3) — validation guards accidents,
      not impersonation.
- [x] **P4.2 — Persistence & resume** ✅ — per-step **autosave** (`SaveStore` filesystem seam, `GameData.toBytes()`)
      + **resume from the setup screen** (peek save → "Resume — round N" → `GameDataManager.loadGame` → engine
      resumes via `setUpGameForRunningSteps()`); reconnection = snapshot catch-up + **battle-log replay** (P4.2c).
      **Re-scoped:** versioned events were unnecessary for our snapshot architecture (see spec §6.2). Commits
      `98b2692a4` (a), `5f9580051` (b), `45684bec2` (c). Verified live: play→restart→resume at round 5; battle-log
      replay on reconnect. Single save slot per server; DB metadata + multi-game deferred to the control plane (P4.4).
- [x] **P4.1/P4.2 refinement — reconnect rejoin** ✅ (commit `b3605788d`) — closing the browser clears
      `sessionStorage`, so a reopened page reconnected as an unbound spectator and the seat's buffered decision
      was never delivered (user-reported). Fix: a **Rejoin prompt** in the running phase (your seat open →
      "Rejoin"; held by another → "Take over"; else picker/spectate), offering only **human** seats (server now
      flags `WebPlayer` seats in the roster); seat remembered in `localStorage` (survives a full close); setup
      still auto-reclaims. Confirm-on-takeover, never silent (user design call). Verified in-browser.
  - [ ] **Multi-human play — revisit** (from `b3605788d` code review; bounded, not blocking solo testing):
        (1) **RejoinPrompt take-over dead-end** (`RejoinPrompt.tsx:43`) — when your remembered seat is held by
        another, the prompt offers only "Take over" or "Watch"; the open-seat picker is unreachable even if a
        *different* human seat is genuinely abandoned. (2) **Optimistic-claim race** (`App.tsx:199`) — `claimSeat`
        sets `mySeat` with no revert; if two clients rejoin the same open seat at once the server binds the last
        claimer, leaving the loser seated in the UI but controlling nothing. Both only bite once real multi-human
        games run (→ P4.3). Also discretionary: extract shared seat-modal styles (RejoinPrompt/SeatSelect dup).
- [x] **P4.3 + P4.4 — Control plane (Auth + Lobby + Orchestrator)** ✅ (M1–M5 all done & verified) — planned in one pass (approved plan:
      `~/.claude/plans/lucky-sprouting-minsky.md`, machine-local). P4.3 and P4.4 build **one** new Java service,
      `:game-control-plane` (Javalin/embedded Jetty), owning OAuth, sessions, lobby, Postgres, presence,
      orchestration; the existing `:game-web-server` becomes a parameterized, multi-seat, reporting **game
      container** (engine stays unmodified). Build-strategy calls: **child-process spawn first** behind a
      `GameLauncher` seam (Docker swapped in at M5); **dev-only fake-login** seam so the lobby/orchestrator are
      buildable before real OAuth apps exist. Milestones (each independently verifiable; don't advance until its
      check passes):
  - [x] **M1** ✅ — `:game-control-plane` module (Javalin + JDBI/Hikari + Flyway-on-boot) + baseline schema
        V1.00.00–V1.04.00 (`users`/`games`/`seats`/`saves`, spec §6.1) + `/health` (200/503) + `GameLauncher`
        seam (`ProcessGameLauncher`, fails loudly until M4a). Verified via `./gradlew :game-control-plane:run`
        **and** the packaged `installDist` binary: 5 migrations apply, 4 tables created, `/health` 200 (5ms) with
        DB up / 503 (fast) with DB down / recovers. Unit test on config fail-fast. **Deviations from plan
        (deliberate):** (1) new non-destructive `.docker/web-port-db.yml` (Postgres 16) instead of trimming the
        legacy upstream `docker-compose.yml`; (2) **no shadow/fat-jar** — a fat jar merges `META-INF/services`
        lossily under shadow 9.4.1 and Flyway silently loses its SQL-migration resolver, so we run via the
        `application` plugin (`run`/`installDist`, ServiceLoader-safe; also the P4.5 container path); (3) deferred
        `:game-core`/`:domain-data` deps to M4b/M5 (M1 doesn't touch the engine). Added `hikari.connectionTimeout=5s`
        so `/health` fails fast instead of blocking 30s. Run: `docker compose -f .docker/web-port-db.yml up -d` →
        `export CONTROL_PLANE_DB_PASSWORD=triplea_web` → `./gradlew :game-control-plane:run`.
  - [x] **M2** ✅ — Session auth: JWT (HS256, auth0 java-jwt) in an httpOnly+SameSite cookie (Secure in prod),
        config-backed allow-list checked at login **and** per-request (revocation), `AuthFilter` on `/api/*`,
        `GET /api/me`, `POST /api/logout`, and a dev-only fake-login (`POST /api/dev-login`, synthetic google
        identity, registered only when `CONTROL_PLANE_DEV_LOGIN=true`; config **fails fast** if that's set under
        `profile=prod`). All login paths funnel through one `LoginService` (allow-list → `UserDao` upsert → mint)
        so pac4j drops in behind the same seam. gson `JsonMapper` wired into Javalin. **Deferred (deliberate, per
        the de-risk fork):** the real pac4j Google/Discord provider — can't verify without registered OAuth apps;
        dev-login exercises the whole flow meanwhile. Verified end-to-end (Postgres): no-cookie→401, dev-login
        listed→200+httpOnly cookie & `/me`→200, unlisted→403, tampered cookie→401, logout→204 then 401, revocation
        (valid JWT but removed from allow-list)→403, prod+dev-login→startup exit 1. Unit tests: JwtService
        round-trip/tamper/wrong-secret, ConfigAllowList, config validation. Run: add `CONTROL_PLANE_JWT_SECRET`
        (≥32 chars), `CONTROL_PLANE_DEV_LOGIN=true`, `CONTROL_PLANE_ALLOWLIST="google:<subject>"` to the M1 run env.
  - [ ] **M3** — Lobby. **Decisions:** lobby owns power-level seats (Model A; matches spec §8 + the
        `seats` table); the control plane enumerates a map's playable powers **live via game-core**
        (user's call — always correct, scales to many maps) rather than seeding a static list.
    - [x] **M3a-foundation** ✅ — game-core wired into the control plane (parse-only, never runs a game);
          `GameXmlReader` (engine init + `GameParser` + `!optional` filter) + `GameCatalog` (built at startup
          from `CONTROL_PLANE_GAME_XML`, powers cached). Verified: Pacific 1940 2E → 5 playable powers
          `[Japanese, Americans, Chinese, British, ANZAC]`; Javalin still boots clean with game-core present.
    - [x] **M3a-lobby** ✅ — `LobbyDao` (JDBI) + `LobbyController`: `GET /api/catalog`, create-table (open seat per
          power, turn order), list/get, claim/release/ready (ownership enforced in SQL), host-launch (creator-only +
          ready-up gate: all human seats ready, ≥1 human → `GameLauncher`, status→active). Migration `V1.05`
          (`seats.ready` + `seat_order`); `ProcessGameLauncher` now logs + returns a placeholder endpoint (real
          spawn M4a). **Open seats become AI at launch** (explicit AI-marking deferred). Verified via curl: full
          two-user flow incl. 409 on taken seat, 403 on not-yours/not-host, active table leaves lobby list.
    - [x] **M3a-ws** ✅ — authenticated lobby WebSocket (`/ws/lobby`, same session cookie) broadcasting the table
          list on connect + after every mutation (`LobbyBroadcaster`). Verified (node `ws`): snapshot + live
          broadcast on create; unauthenticated connection closed with no data.
    - [x] **M3b** ✅ — React multi-view: `react-router-dom` (login → lobby → game; `App.tsx` is the `/game` route),
          `routes/Login.tsx` (dev-login), `routes/Lobby.tsx` (catalog + create + live table list with
          claim/ready/leave + host launch), `api/controlPlane.ts` (fetch, `credentials:"include"`),
          `lobby/useLobbySocket.ts` (`/ws/lobby`), and a Vite proxy for `/api` + `/ws/lobby` → `:7000`. Verified:
          `tsc`+`vite build` clean; proxy/cookie/WS round-trip through `:5173`; **headless-browser e2e** (system
          chromium) drove login→create→claim→ready→launch green. Game route still dials the standalone `:8080`
          game server until M5 wires the dynamic endpoint.
  - [x] **M4a** ✅ — Parameterized `:game-web-server`: named-flag args (gameXml positional + `--port`/`--max-rounds`/
        `--step-delay-ms`/`--game-id`/`--save-ref`; back-compat defaults so a bare `<gameXml>` still works).
        `GameController` takes `gameId` (drives the `SaveStore` slot → per-game autosave isolation) + `saveRef`
        (resume source via `resumeSlot()`). `--control-plane-url` lands in M4b. Verified headlessly: `--game-id=m4a-test`
        autosaves to the `m4a-test` slot, and restart detects `savedGame` from that slot + resumes to a live state
        snapshot. Updated runPlayable comment + manual test doc (positional → flags).
  - [x] **M4b** ✅ — Game container reports lifecycle/turn events to the control plane. Game side:
        `GameReporter` seam (`HttpGameReporter` via JDK `HttpClient`, fire-and-forget async, shared `--game-token`;
        `NoOpGameReporter` in standalone dev), fired from `GameController` (`gameStarted`/`gameFinished` in the
        loop, `turnCommitted{round,power,phase,bytesRef}` from `autosave`). Control plane: `GameReportController`
        (`POST /internal/games/{id}/events`, token-auth, **outside `/api/*`** so the user filter doesn't apply) +
        `GameReportDao` (insert `saves` row + advance `games` round/power/phase/current_save_id in a tx). Verified
        end-to-end: a reporting container drove `games` to `round=1, Americans, Purchase Units` and inserted 25
        `saves` rows; a wrong token → 401. (Single save slot per game → all `bytes_ref` point at the latest; older
        `saves` rows are a history log, only the current is loadable until the store is versioned.)
  - [x] **M5** ✅ — Orchestrator (M5a image + M5b spawn/route/client + M5c reap/rehydrate). **Decisions:** real
        Docker spawn; lobby seats advisory — the browser re-selects in the container's setup phase (no game-WS auth
        changes). DockerGameLauncher via the `docker` CLI; maps + saves mounted as volumes; control-plane URL
        reached from the container via host-gateway. **Follow-ups:** an "active games" list so non-host players can
        enter a launched game; per-game tokens (vs the shared token); versioned save store (older `saves` rows are
        a log, only the latest is loadable); presence-based reaping (vs idle-timeout).
    - [x] **M5a** ✅ — Game-container Docker image. `:game-web-server` gains the `application` plugin
          (`installDist`, entrypoint `WebPlayableServer`); `Dockerfile` (FROM temurin:21-jre, COPY the dist) +
          `.dockerignore`. Map dir + `~/.triplea-web/saves` mounted at runtime (image stays map-agnostic, 526 MB).
          Verified: `docker run` starts a playable game reachable on the published port; `startGame` over the WS
          works; the autosave persists to the shared volume. NB: Pacific takes ~10-40s to load before the WS serves
          — M5b must wait for container readiness. (Logs: game-core `logback.xml` root=WARN suppresses INFO.)
    - [x] **M5b-server** ✅ — `DockerGameLauncher` (spawn container per game via `docker` CLI: `-p` published port,
          `--add-host host.docker.internal:host-gateway` callback, map folder + save volume mounts; container id =
          reap handle) + `GameRouteController` (`GET /api/games/:id/connect`: authorize via `seats`/host, lazy-spawn
          if no live container, return `ws_endpoint`). `LobbyController.launch` now just flips to active (lazy
          spawn). Verified end-to-end through real Docker: launch → /connect spawns a container → drive it → it
          reports back via host-gateway → games row advances (round 1, Japanese) + 23 saves rows.
    - [x] **M5b-client** ✅ — `/game/:id` route: `App.tsx` resolves the container endpoint from
          `/api/games/:id/connect` and dials it, **retrying every 2s** while the container loads (~10-40s); bare
          `/game` keeps the `ws://:8080` standalone fallback. Lobby launch navigates the host to `/game/:id`.
          Verified in a real (headless) browser: login → create → claim → ready → launch → `/game/:id` → the
          container's setup screen renders over the dynamic endpoint. (Non-host players reaching a launched game —
          an "active games" list — is a follow-up; M5b proves the host loop.)
    - [x] **M5c** ✅ — Reaping + lazy rehydration. `GameReaper` (reap container + clear `container_id`/`ws_endpoint`
          so the next connect respawns); reap on `game-finished` (in `GameReportController`); `IdleReaper`
          (scheduled sweep, reaps games with a container and no committed turn within `CONTROL_PLANE_GAME_IDLE_SECONDS`,
          default 1800). Rehydration falls out of M5b's `/connect` (spawns with `--save-ref` = latest `bytes_ref`).
          Verified end-to-end: finished → container reaped + status `finished`; played → reaped → reconnect
          **respawns and resumes from the save** (roster `savedGame={round 1, Combat}` via the shared volume); an
          idle setup game is swept and reaped.
    - [x] **Bid-phase fast-forward** ✅ (from manual testing, session 2026-06-02) — a new game appeared to "let the
          AI act before Japan." Root cause: the Pacific sequence runs game-init + a `bid`/`placeBid` step for *every*
          power before the first turn; bidding is a tournament balancing house-rule that is **off by default** (all
          `<power> bid` properties = 0), so the steps are no-ops, but the loop published a state per step, flashing
          the AI powers' "Bid" phases. `GameController.isSilentStep` now fast-forwards `initDelegate` + zero-bid
          `bid`/`placeBid` steps without publishing, so a fresh game opens on the first power's real turn. Conditioned
          on `BidPurchaseDelegate.doesPlayerHaveBid`, so a real bid still shows — see the Phase 5 "bidding as a setup
          option" item. Verified: first surfaced phase is `Japanese | japanesePolitics`, no bid steps. (`053fa8abd`)
  - [x] **M6 — Lobby↔container identity handoff** ✅ (session 2026-06-03). Wired the lobby's authenticated seat
        assignments to the container and made the game-WS seat binding authenticated, replacing the old trusted
        client-supplied-name claim flow for lobby games. Pieces:
        - **M6a (control plane):** `ConnectTicket` (HMAC-SHA256 over a JSON payload, JDK-only, signed with the
          shared game token); internal `GET /internal/games/{id}/seats` (game-token auth, `InternalSeatsController`);
          `/api/games/{id}/connect` now returns `{wsEndpoint, ticket, seat, isHost}` (`LobbyDao.seatForUser` +
          `created_by`); `GET /api/my-games` (`LobbyDao.gamesForUser`) for non-host entry. **Route gotcha:** had to
          use `/api/my-games`, not `/api/games/mine` — the latter is captured by the `/api/games/{id}` param route.
        - **M6b (container):** `LobbySeatClient` fetches the roster at boot; `SeatPlan.applyAssignments` pre-seats
          humans by display name; new `waiting` phase + per-seat `connected` flag; auto-start when all human seats
          connect (or host starts).
        - **M6c (container):** first-message `{type:"auth", ticket}` verified by HMAC (`ConnectTicket` mirror),
          single-use nonce, gameId match, ~15s auth-timeout close; `bindSeat` on success (reconnect resumes the
          pending decision); claim-by-name disabled in lobby mode. Standalone `/game` hotseat untouched.
        - **M6d (client):** sends the ticket as the first WS message (re-fetches a fresh one on every reconnect —
          tickets are single-use); drops the `localStorage` name for lobby games; `WaitingRoom` UI with connected
          flags + host Start; "Your games in progress" list from `/api/my-games`.
        - **Verified e2e** (`/tmp/m6-verify.mjs`, two users alice+bob): launch → roster fetch → both auth with
          tickets → waiting 2/2 → auto-start to **round 1 | Japanese | japanesePolitics**; tampered/replayed/no-auth
          tickets all rejected (close 4401); bob enters via my-games. 13/13 checks pass.
        - **Follow-up (not M6):** a freshly-spawned container's WS read path isn't immediately stable — the first
          connection(s) can have inbound messages silently dropped before `onStart` settles, and a half-dead socket
          isn't caught by the auth-timeout (`conn.isOpen()` reads false on the container side). `waitForReady` (M5)
          only probes TCP. Add a WS ping/pong heartbeat + a real readiness handshake so the host's first connect to a
          new container can't hang. The browser's reconnect-on-close loop covers the common case today.
  - [x] **WS heartbeat + readiness hardening** ✅ (2026-06-03). Game WS: `setConnectionLostTimeout(20s)` on the
        org.java_websocket server → ping/pong + active close of dead/half-dead sockets (the M6 readiness-race fix —
        the browser's reconnect loop then recovers). Lobby WS: a 25s keepalive ping that also prunes dead sessions.
        Verified: idle game-WS connections survive the ping cycle (turn-timer harness held them 15–45s with no
        regression); a lobby WS idle 28s stayed open + received a ping.
  - [x] **Presence (connected seats → control plane)** ✅ (2026-06-03). The container reports its connected seat set
        (`GameReporter.presence`, deduped, on every connect/disconnect via `publishSeats`) → CP writes `seats.connected`
        (`GameReportDao.setPresence`), surfaced on `SeatView.connected` (a lobby presence dot) and **cleared on reap**.
        The `IdleReaper` now **won't reap a game with a connected player** (`idleGameIds` excludes it). Foundation for
        "your turn" Web Push (target only *absent* players). Verified e2e (`/tmp/pr-verify.mjs`): both seats →
        `connected=true`; a disconnect flips that seat to `false` while the other stays connected.
  - [x] **Deployment v1 (single VM, Docker-free, single HTTPS origin)** ✅ (built + verified 2026-06-03). Scoped to the
        always-on single VM (Hostinger KVM2: 2 vCPU / 8 GB / 100 GB NVMe, x86, month-to-month to validate before
        committing; Oracle Always-Free and Hetzner ARM were earlier targets but neither had ARM capacity available)
        + dev-login (allow-list) over HTTPS — the lighter path than the original
        Docker-Compose idea. Pieces: **`ProcessGameLauncher`** (`CONTROL_PLANE_LAUNCHER=process`) spawns one child JVM
        per game (no Docker daemon/image; handle=PID; `reap`=`destroyForcibly`; `-Xmx` cap via `GAME_WEB_SERVER_OPTS` is
        the resource knob) — default stays `docker` so dev/e2e harnesses are unchanged. **Single origin**: the per-game WS
        is now a same-origin path (`/game/{id}/ws`); `GameWsProxy` (JDK `java.net.http.WebSocket`, no new dep) pipes
        browser↔game's internal `ws://localhost:<port>` with session auth + close-code relay (4502 upstream-unreachable
        vs the game's 4401), so **no game port faces the internet**. Caddy (`deploy/Caddyfile`) terminates TLS, serves the
        SPA, reverse-proxies `/api/*` `/ws/lobby` `/game/*/ws` → `:7000`. Artifacts: `deploy/triplea-control-plane.service`,
        `deploy/control-plane.env.example`, runbook `docs/web-port/DEPLOY.md`. Verified e2e via `/tmp/px-build-run.sh`:
        **both** launchers pass the full lobby flow through the proxy pipe (auth→roster→running→state); process mode
        confirmed 1 child JVM, 0 Docker containers, heap respects `-Xmx`.
        **LIVE 2026-06-04** at **https://triplea.prototypeandpray.com** (Hostinger KVM2, Ubuntu 26.04 LTS, PG 18). Full
        on-box run done: Flyway applied all 8 migrations, Caddy got a Let's Encrypt cert, and a real Pacific 1940 2e game
        **launched and played over the public internet** (process launcher spawned the child JVM; WSS proxy carried play).
        Runbook fixes found live (now in `DEPLOY.md`): Caddy needs **`handle` blocks** (flat `reverse_proxy`+`try_files`
        rewrites `/api/*`→`index.html` before the proxy runs); `useradd` must omit `-m`; clone needs `git checkout web-port`
        (default branch is `main`); geometry.json is generated via `:game-web-server:exportGeometry` into `dist/`; git ops
        on `/opt/triplea-web` must be `sudo -u triplea git`. More user testing to follow.
  - [ ] **Real Google/Discord OAuth (Phase-4 tail).** Switch `CONTROL_PLANE_PROFILE=prod` + `DEV_LOGIN=false`; register
        OAuth apps; set client id/secret + callback (the `LoginService` seam is provider-agnostic; prod profile marks
        cookies Secure). Replaces dev-login for a public audience.
  - [ ] **"Your turn" Web Push (Phase-4 tail, now unblocked).** VAPID: service worker + push subscription + send on a
        turn transition to the *disconnected* seat-holder (uses presence above + the `turn` report's current power →
        `seats.user_id`). Needed HTTPS — now satisfied by the deploy above.
  - [x] **End-game: victory surfacing, end-reason, no round cap for human games** ✅ (built + verified 2026-06-03).
        `GameEndReason` recorded on loop exit; `{type:"gameOver", reason, winners, message}` WS envelope (cached +
        re-sent on connect) + a client `GameOverScreen` that halts the reconnect loop; reason + winner persisted to
        `games.end_reason`/`winner` (migration `V1.06.00`), reported on the `finished` event; `/connect` on a finished
        game returns an end-summary instead of respawning. Human games run uncapped (`effectiveMaxRounds` =
        MAX_VALUE when any seat is human; AI-only keeps the configured cap). Progress/hang **watchdog** (no progress +
        no pending decision → `STUCK`) is the real guard; `STEP_SAFETY_LIMIT` kept as the failsafe. Verified e2e:
        AI-only `--max-rounds=1` → `gameOver reason=ROUND_CAP`; a simulated `finished` report persisted
        `VICTORY`/winner and `/connect` returned the end-summary. VICTORY path shares the same code (manual browser
        check pending — hard to script a real win). (Original design notes below retained for reference.)
  - [x] **Host-side victory detection** ✅ (built + verified 2026-06-04; **corrects a wrong assumption above**). User
        testing exposed it: a met victory condition (Pacific 1940 2e, Japan ≥6-of-8 trigger VCs + Japan) **never ended
        the game** — it ran to the round cap with no winner. Root-caused (full trail in `docs/web-port/VICTORY-FIX-PLAN.md`):
        the engine's `EndRoundDelegate` *evaluates* the trigger condition as satisfied, but `TriggerAttachment.triggerVictory`
        only calls `signalGameOver` `if (victoryMessage != null)`, and that message is resolved via the bridge's
        `ResourceLoader` — which our host sets **empty** (`WebLaunchAction`), so the message is null and victory silently
        never fires. (So the earlier "victory DOES halt our loop" note was wrong — it was never tested with a real win;
        reproduced in pure `game-core` via `TriggeredVictoryFiringTest`.) **Fix (Fix A, engine unmodified):** detect
        victory ourselves in the host. `HostVictoryDetector` (run each round boundary in `GameController.run()`) unions
        **direct-mode** winners (`getEndRoundDelegate().getWinners()` — economic/VP/victory-cities/capital) with
        **triggered-mode** winners found by replaying the engine's own generic `collectForAllTriggersMatching` +
        `collectTestsForAllTriggers` via a read-only proxy bridge (`GameDataDelegateBridge`); winners = beneficiary's
        alliance; **map-general (no hardcoded territories)** so Europe/Pacific/Global 1940 all work; **fail-closed**.
        Verified: `HostVictoryDetectorTest` + the engine-constraint test pass; an all-AI Pacific game now ends
        `VICTORY at round 14 — winners: [Japanese]`. **Follow-up:** uncap conceded (formerly-human) games so they have
        the rounds to *reach* a win (deferred concede-recap fix).
        ~~The engine
        already DETECTS every end condition in `EndRoundDelegate` (VP, victory cities, economic, capital-loss,
        triggered victories), records the `winners`, and writes a victory message into history via `signalGameOver(...)`.
        **Confirmed (traced):** a victory DOES halt our loop — in-process (non-websocket) `signalGameOver` →
        `bridge.stopGameSequence()` → `delegateExecutionStopped` → next `runNextStep()` → `stopGame()` →
        `isGameOver()` true → `Session.run` exits (we set `setStopGameOnDelegateExecutionStop(true)`). The winner is
        recoverable live and from a save: `gameData.getEndRoundDelegate().getWinners()` + `.gameOver` (persisted in
        `saveState`/`loadState`), with the reason text in history. **The gap is purely surfacing:** there's no
        game-over/winner WS envelope — on end the container is reaped and the WS just closes, so the browser treats it
        as a dropped connection; and a real victory is **indistinguishable** from the `maxRounds` cap (default 20) or
        the 10k-step safety limit — all three call `reporter.gameFinished()` and the DB only flips `status=finished`
        (no winner, no reason). Work:
        - **Record an explicit `GameEndReason`** at loop exit / graceful end — `VICTORY`(+winners), `CONCEDED`,
          `ABANDONED`, `ERROR`, `STUCK`, `ROUND_CAP` (AI-only), `HOST_ENDED` — report it on the `finished` event and
          add `end_reason` + `winner` to `games`; push `{type:"gameOver", reason, winners, message}` and show an end
          screen. (Answers "always know why a game ended, especially before victory.")
        - **No round cap for games with a human seat** — run unbounded until victory/concede/host-end. Keep a
          *configurable* cap only for AI-only demo/spectator runs (no human to end them). `GameController` already
          knows the human seats.
        - **Replace the blunt step cap as the primary guard with a progress/hang watchdog**, keeping
          `STEP_SAFETY_LIMIT` (+ a wall-clock bound) as a last-resort failsafe (per user). Distinguish the two
          "runs-forever" modes cheaply: loop blocked in `runNextStep` **with a decision request pending** = a normal
          human turn (governed by the turn deadline, see concede/abandonment item); blocked **with no pending request**
          for > T, or the same step re-executing without the round/sequence advancing = an **engine hang/loop** → abort
          `STUCK`. (`STEP_SAFETY_LIMIT` can't catch an abandoned turn anyway — the loop is *parked* in `await`, not
          iterating, so `steps` never increments.)~~
  - [x] **Concede — resign-to-AI** ✅ (built + verified 2026-06-03). `{type:"control", action:"concede"}` from the
        authenticated seat → `doResign`: stop session → `SeatPlan.release(seat)` → resume from the latest autosave with
        the seat now AI (fresh start if nothing committed). The game **continues**; the conceder's client drops to
        spectator (Concede button + confirm in `App.tsx`). The container reports `seatResigned` → control plane marks
        the seat `kind=ai` (`GameReportDao.resignSeat`) so the conceder's reconnect returns no seat (spectator).
        Verified e2e: alice concedes → her seat shows AI, the game keeps committing turns, her next `/connect` has no
        seat. **Shares the "seat → AI mid-game" primitive** that abandonment will reuse.
  - [x] **Turn timers + abandonment (AI takeover, reclaimable)** ✅ (built + verified 2026-06-03). Research-grounded
        (BGA/Chess.com/Lichess/OGS): the **host picks a per-turn timer** at table creation from presets
        (`Unlimited · 30 min · 1 hr · 1/2/3/5/7/14 days`, default 3 days → `games.turn_limit_seconds`, migration
        `V1.07.00`). The deadline is **durable & absolute** (`seats.turn_deadline_at`, set/cleared by the container via
        a `deadline` report; read back on (re)boot) so a correspondence clock survives a container reap. The container
        watchdog (now ~5s cadence, doubling as the hang/`STUCK` guard) sets the active human seat's deadline per prompt
        ("days-per-move"), and on expiry hands the seat to AI via the shared `doSeatToAi(seat, permanent=false)`
        primitive — **reclaimable**: the takeover keeps DB ownership, so the player's `/connect` still returns their
        seat and a **Reclaim** button (when their seat shows AI in the running roster) swaps it back. `Unlimited`
        (0) never sets a deadline. Verified e2e (`/tmp/tt-verify.mjs`): 5s timer → AI takeover → `/connect` still
        returns the seat → reclaim restores it human; `Unlimited` → no takeover. **v1 is lazy** (enforced on container
        liveness/boot). Follow-ups: reserve time-bank (Fischer/BGA), vacation/quiet-hours, a **proactive CP sweep**
        that wakes idle overdue games to advance them (pairs with Web Push), and a client **countdown** display.
  - [x] **BUGFIX (2026-06-04): human seats instantly surrendered to AI on turn one.** Live on the Hostinger box: a
        human-claimed seat (correct in the DB) was played by AI the whole game, running unattended to a victory.
        Root cause was NOT the seat handoff but a JDBC `wasNull()` ordering bug in `LobbyDao.seatAssignments`:
        `rs.wasNull()` for `deadline_epoch` was read **after** `getString("display_name")`, so for a human seat
        (non-null display name) a NULL `turn_deadline_at` mis-mapped to `0` (epoch 1970). The container loaded that as
        `bootDeadlines[seat]=0` → an already-expired turn → instant `doSeatToAi(seat, false)` on the first prompt. The
        `"AI takeover"` log is `INFO`, suppressed by game-core's `root level="warn"`, which hid the evidence for hours.
        Fix: extracted `LobbyDao.mapSeatAssignment`, reading each nullable numeric's `wasNull()` immediately after its
        getter; added `LobbyDaoSeatMappingTest` (Mockito ResultSet that models JDBC's last-read `wasNull()` — proven to
        fail on the old ordering). Read-path only; no data migration. **Follow-up: raise the game container's log level
        so an `AI takeover` is never invisible.** **Redeploy needed** for the box (control plane rebuild + restart).
- [ ] **Exit check:** a private group plays a full Pacific 1940 game over the internet, browser-only, resumable
      across days, with an abandoned seat caretaken by AI, **ending with a winner announced**.
- Related: 3g hotseat (pass-and-play on one machine) shares the seat-routing mechanism — falls out of P4.1.
- Deferred to "when we open up": moderation/ban/audit tooling (schema known, see spec §4/§10.4); R2/S3 save store.

### Phase 5 — Breadth & durability
- [ ] **Action log / game history visible to all players (+ replay).** Give every player a live, textual feed of
      what others are doing (purchases, moves, battles, politics, tech, placement) instead of inferring it from the
      map. **Mostly a surfacing task — the engine already records this.** `game-core` writes a high-fidelity history
      tree (`Round → Step → Event → EventChild`) with human-readable strings (delegates call
      `getHistoryWriter().startEvent("Russia buy 5 infantry…")`), it is **already serialized in the save**
      (`GameDataManager.forSaveGame()` → `withHistory=true`) and restored on load, and there's a ready-made text
      exporter (`HistoryLog.printFullTurn()`). Work: on the server, project new history entries to a
      `{type:"log", ...}` WS envelope as steps commit (cache + replay on connect, like the battle log); on the client,
      a scrollable log/history panel. **Replay** falls out of the same data — the history is seekable (desktop
      `HistoryPanel.gotoNode`), so a web replay steps through the saved history at any point. Overhead is modest (the
      data already exists); cost is the WS fan-out + UI. Builds on the per-step publish already in
      `GameController.Session.run`.
- [ ] **Post-game review session (end-game lifecycle).** When a game ends, keep the table alive in a read-only
      **review** state so players can read the full log / replay and discuss — rather than reaping immediately. The
      **host** explicitly ends/closes the session → the game (and its save) is deleted/archived. Needs the
      `{type:"gameOver"}` signal and a `review` status distinct from today's `finished`+immediate-reap (see the Phase 4
      end-game item) so the `IdleReaper`/`GameReaper` don't tear the container down while players are still reviewing.
- [ ] **Saved game history & export (premium).** Let players retain N completed games and export the game log/replay
      (the engine's `HistoryLog` already renders history as text; saves already carry the full history). Storage +
      retention is resource-heavy → gate as a **premium** feature; ties into the R2/S3 save-store seam already noted
      ("when we open up").
- [ ] Run converter across more maps; fix feature gaps (relief blending, scroll-wrap, markers)
- [ ] **Unit icon assets (render unit images, not text counts).** `MapCanvas` draws each stack as a centered count
      badge + a text-only hover list; the desktop draws each unit type's PNG. The map's images
      (`map/units/*.png`, plus `flags/`, relief `baseTiles/`) are **gitignored** (`.gitignore` line 18: `*.png`) —
      like the desktop, maps and their art are distributed/downloaded separately, not committed. So this needs an
      **asset pipeline**: export/serve the active map's unit images (keyed by unit type + owner) to the client
      alongside the (also gitignored) `geometry.json` export, and have `MapCanvas` draw the icons in stacks. Shares
      the converter/export work above (flags + relief blending come from the same gitignored PNGs). Keep the assets
      out of git — regenerate/serve them from the source map.
- [ ] Tech panel (politics done in 3e++); generalize politics beyond Pacific's free DoW actions (cost/dice/`actionAccept` paths)
- [ ] Save/load via engine's existing `.tsvg` serialization (server-side)
- [ ] **Bidding as a game setup option.** Bids are hardcoded to 0 today, so the game loop fast-forwards the
      no-op bid/`placeBid` steps (`GameController.isSilentStep`, conditioned on `BidPurchaseDelegate.doesPlayerHaveBid`).
      Expose a per-power bid amount as a lobby/launch setup option (writes the `<power> bid` game property before
      launch); a non-zero bid then runs and is surfaced normally with no further code change. Competitive A&A
      balances sides this way (winner of the bid plays the weaker side with extra IPCs) — out of scope until we
      support tournament-style play.
- [ ] **Admin console (service-owner operations).** A control-plane-only UI for the server owner to view and
      manage the whole service: **all games** (list/inspect/force-reap/delete, jump into any game's state, see
      container + save status), **all players** (the allow-list — invite/revoke, last-login, current presence,
      reassign/AI-take-over a seat), and **service settings** (idle-reap threshold, launch defaults, allow-list
      management — today these are env vars: `CONTROL_PLANE_*`). Gate behind the `admin` role (the `role` column
      already planned in `PHASE-4-MULTIPLAYER.md`); back it with the existing `games`/`seats`/`users` tables — no
      new game-state, mostly read + lifecycle actions over data the control plane already owns. Distinct from the
      deferred **moderation/ban/audit** tooling (line 475): that polices abusive players "when we open up"; this is
      operator tooling useful from day one for a private group. Build on top of M6's authenticated identity layer.
- [ ] **Exit check:** a second, structurally different map plays end-to-end

## Notes / decisions
- Engine stays unmodified — if a `game-core` change seems necessary, STOP and reconsider (respect save-game + `@RemoteActionCode` compatibility rules in root `AGENTS.md`).
- Pacific 1940 is a feature-heavy first map (naval/scramble/kamikaze); Phase 3 is larger than it would be for a simpler map. Accepted deliberately.
