# Web Port — TODO

See `docs/web-port/design.md` for the full rationale and architecture.

**Strategy in one line:** reuse the Java `game-core` engine headless; build a
browser-hosted React client that talks to it over WebSocket + JSON. Engine is
NOT modified. First map: World War II Pacific (Pacific 1940).

## Plan

### Phase 0 — Boot engine headless, in-process
- [x] **PREREQUISITE: JDK 21** — installed (Temurin 21.0.11). `:smoke-testing:test --tests AiGameTest` PASSED (3m12s): engine builds + plays a full AI game headless on this machine. (Note: Gradle project paths are flat, e.g. `:smoke-testing`, not `:game-app:smoke-testing`.)
- [x] Create `game-app/game-web-server` Gradle module + its `AGENTS.md` — compiles and passes `:game-web-server:check`. Deps: `:game-core` (gson/junit injected by root build). `:game-headless` to be added with the engine-host path.
- [x] Map geometry converter `MapGeometryConverter` — reads `polygons.txt`/`centers.txt` via the engine's `PointFileReaderWriter`, emits `MapGeometry` JSON. 3 unit tests pass against a synthetic fixture. **Remaining:** merge territory connections from parsed `GameData`; read `map.properties` (width/height/scroll-wrap); validate on a real map.
- [x] Confirm a full game runs to completion (AI) — proven at engine level by `AiGameTest` (reused, not re-implemented).
- [ ] Download/cache the `world_war_ii_pacific` map repo (art + polygons + centers) locally — NEXT
- [ ] Run the converter on the real Pacific 1940 map folder; export `geometry.json`
- [ ] Drive the engine **from our module** (not just engine-level): a minimal `WebLaunchAction`/reuse `HeadlessLaunchAction`, load Pacific 1940 XML, start `ServerGame`, step it
- [ ] **Exit check:** engine runs a full Pacific 1940 game from our code; `geometry.json` exported from the real map

### Phase 1 — Static rendering
- [ ] Scaffold `web-client/` React app (Vite + TypeScript)
- [ ] Export a one-shot JSON state snapshot from the running engine
- [ ] Render base map image + territory polygon overlay from `geometry.json`
- [ ] Tint territories by owner; draw unit sprites + stack counts at centers
- [ ] Pan / zoom
- [ ] **Exit check:** Pacific 1940 renders correctly in the browser (read-only)

### Phase 2 — Live spectator over WebSocket
- [ ] Implement `StateProjector` (GameData → JSON DTO; no full-graph serialization)
- [ ] Implement `WebDisplay` (IDisplay → JSON push messages)
- [ ] Add WebSocket endpoint to `game-web-server`; serve React bundle + assets over HTTP
- [ ] Client subscribes, re-renders on each state push
- [ ] **Exit check:** watch an AI-vs-AI Pacific 1940 game advance live in React

### Phase 3 — Hotseat playable ⭐ (first milestone)
- [ ] Implement `WebPlayer` (Player) with future/queue bridge for blocking decisions
- [ ] Purchase panel → submit to purchase delegate
- [ ] Combat-move panel → submit to move delegate (rules enforced by delegate)
- [ ] Battle / casualty-selection panel (`selectCasualties`, `retreatQuery`)
- [ ] Naval/air decision methods required by Pacific 1940 (`scrambleUnitsQuery`, `selectKamikazeSuicideAttacks`, shore bombard, fighters-to-carrier, air-to-land)
- [ ] Non-combat move + place panels
- [ ] **Exit check:** a complete Pacific 1940 game, playable start to finish, hotseat on one machine

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
