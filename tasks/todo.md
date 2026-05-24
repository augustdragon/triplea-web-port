# Web Port — TODO

See `docs/web-port/design.md` for the full rationale and architecture.

**Strategy in one line:** reuse the Java `game-core` engine headless; build a
browser-hosted React client that talks to it over WebSocket + JSON. Engine is
NOT modified. First map: World War II Pacific (Pacific 1940).

## Plan

### Phase 0 — Boot engine headless, in-process
- [ ] Create `game-app/game-web-server` Gradle module (depends on game-core, game-headless); add its own `AGENTS.md`
- [ ] Download/cache the `world_war_ii_pacific` map repo (art + polygons + centers) locally
- [ ] Programmatically load the Pacific 1940 game XML and start a `ServerGame` with AI players, no UI
- [ ] Confirm a full game runs to completion in-process (driven by AI), logging step transitions
- [ ] Build map-folder → `geometry.json` converter, reusing the engine's `MapData` reader (polygons + centers + connections + anchors)
- [ ] **Exit check:** engine runs a full Pacific 1940 game from our code; `geometry.json` exported

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
