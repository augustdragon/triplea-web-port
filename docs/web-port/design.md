# Web Port — Design (living spec)

> **Status:** Draft / Phase 0 not yet started.
> **Goal:** Replace TripleA's dated Swing UI and custom-socket networking with a
> modern, browser-hosted React client — **without** rewriting the game-rules
> engine. The existing Java engine (`game-app/game-core`) is reused unchanged as
> a headless server. The React client talks to it over WebSocket + JSON.
>
> This document explains *why* the architecture is shaped this way, not just
> *what* to build, so a new contributor (or future us) can pick it up cold.

---

## 1. Why reuse the engine instead of rewriting it

The instinct is "rewrite it in TypeScript so it all runs in the browser." We
deliberately rejected that. The evidence:

- `game-app/game-core` contains **~68,000 lines of pure game-rules logic** —
  24 delegates, ~8,000 lines of battle resolution, and an attachments system
  with ~18 types and 130+ tunable properties (`UnitAttachment` alone has 41
  setters).
- **Every one of the ~295 community maps is authored against those exact
  attachment semantics.** "Map compatibility" *is* "behaves identically to the
  current engine." Rewriting the rules means re-deriving a 20-year-old corpus
  and then re-validating it against hundreds of maps. The success criterion of
  that effort is literally "matches the thing we already have."
- The genuinely dated parts — the **~38,000 lines of Swing UI** and the
  **custom RMI-over-TCP networking** — are exactly the parts that are cleanly
  separable. Delegates and the data model have **zero** `javax.swing` /
  `java.awt` imports.

The clinching detail: the engine already drives players and displays through
**remote-capable interfaces**. `engine.player.Player` even has a method
annotated *"should not be called over the network"* — proving the rest *are*
called over the network today. The engine already routes player decisions to
remote clients over a socket. **We are adding a new transport (WebSocket+JSON)
and a new client (browser) for interfaces that were built to be driven
remotely.** That is what makes this low-risk.

### Decisions locked
| Decision | Choice | Rationale |
|---|---|---|
| Rules engine | **Reuse Java `game-core` headless** | Map compatibility for free; reuse 68K LOC of correct rules + AI |
| Client delivery | **Browser, server-hosted** | Players open a URL (LAN IP / ZeroTier IP); nothing to install |
| First milestone | **One map playable hotseat, end-to-end** | Proves asset pipeline + state projection + action routing before multiplayer |
| First map | **World War II Pacific (Pacific 1940)** | User's choice; see §6 for the complexity caveat |
| Connectivity | **LAN-only, ZeroTier for internet** | A server on an IP:port — no lobby/relay infra needed |

---

## 2. Target architecture

```
┌─────────────────────────────┐         ┌──────────────────────────────────────┐
│   Browser (React client)    │         │   Host machine (one JVM)               │
│                             │  HTTP   │                                        │
│  • Map renderer (canvas/SVG)│◄────────┤  game-web-server  (NEW thin module)    │
│  • Action panels            │  assets │   ├─ serves React bundle + map assets  │
│  • WebSocket client         │◄═══════▶│   ├─ WebSocket endpoint (state ⇄ acts) │
│                             │  JSON   │   ├─ WebDisplay   implements IDisplay   │
└─────────────────────────────┘  events │   ├─ WebPlayer    implements Player     │
                                         │   └─ StateProjector (GameData→JSON DTO)│
   players: just a browser               │                                        │
   LAN IP or ZeroTier IP                 │   embeds UNCHANGED game-core engine     │
                                         │   (delegates, battle, attachments, AI)  │
                                         └──────────────────────────────────────┘
```

**Hard rule:** `game-core` is not modified. All new Java lives in a new
`game-app/game-web-server` module; all new frontend lives in `web-client/`.
We adapt at existing seams; we do not fork the engine. If we ever *need* a
change in `game-core`, that's a flag to stop and reconsider — and to respect
the save-game / `@RemoteActionCode` compatibility rules in the root `AGENTS.md`.

---

## 3. The integration seams (the real backend work)

Three new classes are ~90% of the backend. All three plug into interfaces that
already exist in `game-core`:

| New class | Implements | Job | Surface |
|---|---|---|---|
| `WebPlayer` | `games.strategy.engine.player.Player` | One human seat. Each decision method parks the engine thread on a queue until the browser submits an answer, then returns it to the delegate — which validates it against the real rules. | ~25 methods, filled in per phase |
| `WebDisplay` | `games.strategy.engine.display.IDisplay` | Receives "show this" callbacks from delegates; translates them to JSON push messages. | ~14 methods |
| `StateProjector` | (new) | Read-only snapshot of `GameData` → a compact JSON DTO (territory ownership, units per territory, resources, current step/player). **Never** serializes the whole object graph. | grows with UI needs |

### Key `Player` decision methods (the ones the client must answer)
`selectCasualties`, `retreatQuery`, `scrambleUnitsQuery`,
`selectKamikazeSuicideAttacks`, `pickTerritoryAndUnits`,
`selectBombardingTerritory`, `selectAttackSubs/Transports/Units`,
`selectShoreBombard`, `whereShouldRocketsAttack`,
`getNumberOfFightersToMoveToNewCarrier`, `selectTerritoryForAirToLand`,
`confirmMoveInFaceOfAa`, `acceptAction`, plus the lifecycle `start(step)` /
`stopGame()`. Movement and purchase are submitted *to* the move/purchase
delegates rather than asked; the rest are pull-style queries the seat answers.

### Threading model
The engine runs its game loop on its own thread and **blocks** on player input.
WebSocket is async. Bridge with a future/queue per pending decision: the engine
thread parks on `WebPlayer.retreatQuery(...)`, the client posts the answer, the
future completes, the engine resumes. This is the same pattern the existing
network player uses — we're not inventing it.

Reference seams in the existing code:
- `game-app/game-core/.../engine/player/Player.java`
- `game-app/game-core/.../engine/display/IDisplay.java`
- `game-app/game-headless/.../HeadlessGameServer.java` (template for booting a game with no UI)
- `game-app/game-core/.../triplea/ui/display/HeadlessDisplay.java` (no-op `IDisplay` to model `WebDisplay` on)

---

## 4. Asset pipeline

A map is a folder downloaded from `github.com/triplea-maps/<map>` (only the game
XMLs live in this repo, as test fixtures). Relevant files:

- `polygons.txt` — territory shapes (clickable/renderable regions)
- `centers.txt` — territory centroids (unit/label anchors)
- `baseTiles/*.png`, optional `reliefTiles/*.png` — map background
- `units/<nation>/*.png`, `flags/`, `PUs/` — sprites
- `map.properties` — dimensions, relief flag, scroll-wrap flags
- marker files (`capitols.txt`, `vc.txt`, `convoy.txt`, …) — overlay positions

**Converter** (Java, in `game-web-server`; reuses the engine's existing
`MapData` reader rather than re-parsing): emits `geometry.json`
(polygons + centers + connections + anchors). PNG art is served as static files
— tiles as-is to start; pre-stitching into one image is a later optimization.

**Browser rendering layers** (bottom → top):
1. Base map image (tiles, optionally relief-blended)
2. Territory polygon overlay (SVG or canvas) — hit-testing, selection, ownership tint
3. Unit sprites stacked at `centers.txt` anchors, with stack counts
4. Markers / labels

---

## 5. Phased roadmap

Each phase produces something runnable. We are never in a "correct only when
100% done" hole, because the rules engine is reused, not rewritten.

### Phase 0 — Boot the engine headless, in-process
New `game-app/game-web-server` module. Programmatically load the Pacific 1940
game XML, start a `ServerGame`, run the sequence with AI/scripted players, no UI.
Build the map-folder → `geometry.json` converter. Pull the real
`world_war_ii_pacific` map repo so we have art + geometry locally.
**Exit:** engine runs a full game in-process from our code; map geometry exported.

### Phase 1 — Static rendering
React app renders Pacific 1940 from a one-shot exported JSON snapshot: base
image, polygons, ownership, units. No interactivity.
**Exit:** the real map looks correct in the browser.

### Phase 2 — Live spectator over WebSocket
Wire `WebDisplay` + `StateProjector` + WebSocket. Run a game with AI players;
browser renders live state as it advances on its own.
**Exit:** watch an AI-vs-AI Pacific 1940 game play out in React.

### Phase 3 — Hotseat playable ⭐ (first milestone)
Implement `WebPlayer`. Build one action panel per phase: purchase → combat move
→ battle/casualty selection → non-combat move → place. Client submits decisions;
**real delegates enforce every rule for free.** One machine, all seats in one
browser, full turn cycle. This is the bulk of frontend work and where the ~25
`Player` methods get filled in. (Pacific 1940 forces the naval/scramble/kamikaze
methods here — see §6.)
**Exit:** a complete game of Pacific 1940, playable start to finish, hotseat.

### Phase 4 — LAN / ZeroTier multiplayer
Multiple browsers connect; seat claiming; route each seat's decisions to the
right client. Transport is already WebSocket, so this is mostly session/seat
management + lightweight auth. ZeroTier needs nothing special.
**Exit:** two machines play over LAN/ZeroTier.

### Phase 5 — Breadth & durability
Run the converter across more maps; handle features incrementally (relief
blending, scroll-wrap world maps, territory-effect/decoration markers, tech &
politics panels). Save/load **reuses the engine's existing `.tsvg` serialization
server-side** — no new save format.

---

## 6. First-map caveat: Pacific 1940 is not the "easy" map

Pacific 1940 was chosen deliberately, but it exercises advanced rules that a
smaller map (e.g. A&A Revised) would let us defer:

- Sea zones, carriers + carrier-capacity, naval combat
- **Scramble** (`scrambleUnitsQuery`) — defenders launch interceptors
- **Kamikaze** (`selectKamikazeSuicideAttacks`)
- AA fire, shore bombardment, strategic bombing
- Likely scroll-wrap / large map rendering

**Consequence:** several `Player` methods that the plan hoped to stub become
*required* in Phase 3, and Phase 1 rendering must handle a large (possibly
scroll-wrapping) map. This makes Phase 3 larger; it does not change the
architecture. Recorded here so the scope is honest going in.

---

## 7. Risks & unknowns (ranked)

1. **`Player` interface breadth** — ~25 decision methods, some fiddly. Mitigation: fill in per-phase; the chosen map dictates which are mandatory.
2. **State-projection completeness** — getting every bit the UI needs into JSON without serializing the whole graph. Mitigation: grow the DTO driven by what each panel actually reads.
3. **Threading bridge** — engine blocks; WebSocket is async. Mitigation: future/queue per pending decision (proven by existing net player).
4. **Map rendering edge cases** — relief blending, scroll-wrap, markers (Pacific 1940 hits these early). Mitigation: tackle in Phase 1/5; lean on the engine's `MapData` for geometry.
5. **Asset volume** — map folders are 100–500 MB. Fine for a host serving over LAN; never bundle into the client.

---

## 8. Sizing & honesty

This is a months-long effort, not a weekend. Its virtue is that it's
incremental and verifiable: every phase is runnable, and we never throw away the
battle-tested rules. The new code is a thin adapter layer plus a React app — the
hard, correct part (the rules) is borrowed intact.
