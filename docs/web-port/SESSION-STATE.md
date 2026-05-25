# Web Port — Session State / Handoff

> **Read this first when resuming the web-port work.** It captures where we are,
> the one blocker, and the hard-won details (the exact engine-boot recipe) so a
> fresh session doesn't have to re-discover them.
>
> Companion docs: `docs/web-port/design.md` (full architecture + rationale),
> `tasks/todo.md` (phased checklist), `tasks/lessons.md` (gotchas).

---

## Goal (one paragraph)

Replace TripleA's dated Java Swing UI and custom-socket networking with a
modern, **browser-hosted React client**, while **reusing the existing Java
game-rules engine (`game-app/game-core`) unchanged** as a headless server. The
React client talks to it over **WebSocket + JSON**. Connectivity target:
LAN-only, with ZeroTier for internet play. First map: **World War II Pacific
(Pacific 1940)**. We rejected rewriting the rules in TS (68K+ LOC of
battle-tested logic that defines map compatibility) — see design.md §1.

## Git state

- Branch: **`web-port`** (created off `main`). **Local only — do NOT push / do
  NOT touch the remote.** A private remote may be created later if needed.
- Committed on this branch: `docs/web-port/design.md`, `tasks/todo.md`
  (commit `docs(web-port): add design spec and phased TODO...`).
- Also committed (this handoff): `SESSION-STATE.md`, `tasks/lessons.md`, todo update.

## ⛔ The one blocker: no JDK installed

This machine has **no JDK** — `JAVA_HOME` empty, `java`/`javac` not on PATH, no
Gradle-provisioned toolchain. The only Java present is a **JRE** bundled with
VASSAL (`C:\Program Files\VASSAL-3.7.22\jre\bin\java.exe`) — useless for building
(no compiler, not v21). The project requires **JDK 21**, and `gradlew` itself
needs a JVM to even start. **Nothing builds, compiles, or runs until JDK 21 is
installed.** The user is handling the install.

### Resume sequence (do these in order once JDK 21 is present)
1. Verify: `java -version` shows 21; `JAVA_HOME` is set (may need a fresh shell).
   On Windows the bash tool may not see `java`; check via PowerShell too.
2. **Prove the premise on this machine** before writing any new code:
   `./gradlew :game-app:smoke-testing:test --tests AiGameTest`
   This boots and plays a full AI-vs-AI game headless from a parsed XML — the
   exact capability the whole reuse plan depends on. (First Gradle run is slow &
   network-heavy: downloads the dep graph + toolchain bits, one time.)
3. Then start Phase 0 coding (module + converter), verifying each step compiles.

---

## 🔑 The engine-boot recipe (most valuable finding)

`game-app/smoke-testing/.../GameTestUtils.java` shows the **minimal in-process
boot** of the engine — no lobby, no network, map-resource loading skipped. Our
`game-web-server` module will use this same path (adapted to main code; the test
uses Mockito for `HeadlessGameServer`, we'll need a real or refactored
construction). Skeleton:

```java
// One-time setup
HeadlessLaunchAction.setSkipMapResourceLoading(true);   // engine-only; skips map art
ClientSetting.setPreferences(new MemoryPreferences());
ClientSetting.aiMovePauseDuration.setValue(0);
ClientSetting.aiCombatStepPauseDuration.setValue(0);
// temp root with a ".triplea-root" marker file + an "assets" dir
ClientFileSystemHelper.setCodeSourceFolder(tempRoot);
System.setProperty(GameRunner.TRIPLEA_HEADLESS, "true");

// Per game
GameData gameData = GameParser.parse(xmlFilePath, false).orElseThrow();
Map<String, PlayerTypes.Type> playerTypes = new HashMap<>();
gameData.getPlayerList().getPlayers().forEach(p -> playerTypes.put(p.getName(), PlayerTypes.PRO_AI));
Set<Player> gamePlayers = gameData.getGameLoader().newPlayers(playerTypes);
HeadlessLaunchAction launchAction = new HeadlessLaunchAction(/* HeadlessGameServer */);
Messengers messengers = new Messengers(new LocalNoOpMessenger());
ServerGame game = new ServerGame(
    gameData, gamePlayers, new HashMap<>(), messengers,
    ClientNetworkBridge.NO_OP_SENDER, launchAction);
game.setDelegateAutosavesEnabled(false);
gameData.getGameLoader().startGame(game, gamePlayers, launchAction, null);

// Run the game loop
game.setStopGameOnDelegateExecutionStop(true);
while (!game.isGameOver()) { game.runNextStep(); }
```

**For a human seat (Phase 3):** instead of `PlayerTypes.PRO_AI`, the engine
needs our `WebPlayer implements games.strategy.engine.player.Player`. Each of its
~25 decision methods parks the engine thread on a future/queue until the browser
answers, then returns the result — the delegate validates it against real rules.

---

## Key file paths (verified this session)

| What | Path |
|---|---|
| Engine-boot template | `game-app/smoke-testing/src/test/java/games/strategy/engine/data/GameTestUtils.java` |
| AI-game end-to-end test | `.../smoke-testing/.../AiGameTest.java` |
| XML → GameData parser | `game-app/game-core/.../engine/data/gameparser/GameParser.java` (`parse(Path, boolean)`) |
| Player interface (~25 decision methods) | `game-app/game-core/.../engine/player/Player.java` |
| Display interface (~14 methods) | `game-app/game-core/.../engine/display/IDisplay.java` |
| No-op display to model WebDisplay on | `game-app/game-core/.../triplea/ui/display/HeadlessDisplay.java` |
| Server game loop | `game-app/game-core/.../engine/framework/ServerGame.java` |
| Headless host (heavy; has lobby/net baggage) | `game-app/game-headless/.../HeadlessGameServer.java` |
| Map geometry reader (reuse for converter) | `game-app/game-core/.../triplea/ui/mapdata/MapData.java` |
| Tiny geometry test fixture | `game-app/map-data/src/test/resources/map_description_yml_generator/example-map/` (has `polygons.txt`; no `centers.txt`) |
| Module registration | `settings.gradle.kts` (use `include(...)` + `project(...).projectDir` pattern) |
| Minimal module build template | `game-app/game-relay-server/build.gradle.kts` |
| Smoke-testing build (has download task pattern) | `game-app/smoke-testing/build.gradle.kts` |
| Map catalog (295 maps, URLs) | `triplea_maps.yaml` |
| Pacific map repo | `github.com/triplea-maps/world_war_ii_pacific` (mapName "World War II Pacific"; game "Pacific 1940") |

## Build facts
- JDK 21, Gradle Kotlin DSL, config cache on. Convention plugin: `triplea-java-library`.
- New module pattern: add to `settings.gradle.kts`, create `game-app/game-web-server/build.gradle.kts` depending on `:game-core` (and `:game-headless`, `:java-extras` as needed).
- Format: `./gradlew spotlessApply`. Full check: `./verify`.

## Gotchas / constraints (also in tasks/lessons.md)
- **`*.png`, `*.gif`, `*.mp3` are gitignored repo-wide.** Map art CANNOT be committed here — it must live outside the repo / be served from a separate asset location. (Fine: design serves assets statically anyway; folders are 100–500 MB.)
- **`CLAUDE.local.md` is gitignored** and auto-read at session start — used as the local pickup pointer.
- This is Windows. The **Bash tool here uses PowerShell-incompatible syntax** — do NOT use PowerShell here-strings (`@'...'@`) inside the Bash tool (it mangled a commit message this session; fixed via amend). Use bash quoting in Bash, PowerShell syntax in PowerShell.
- Save `.env`/shell scripts with **LF**; git warns CRLF on these md files (harmless).
- `pacific_incomplete_test.xml` in testFixtures is INCOMPLETE — target the real map repo XML, not that fixture.

## Pacific 1940 caveat
Feature-heavy first map: sea zones, carriers, **scramble** (`scrambleUnitsQuery`),
**kamikaze** (`selectKamikazeSuicideAttacks`), AA, shore bombardment, strategic
bombing, likely scroll-wrap rendering. Makes Phase 3 (and Phase 1 rendering)
larger — several `Player` methods that could otherwise be stubbed are mandatory.
Architecture unchanged. Accepted deliberately (user's choice).

## Pacific map is already local (no download needed)
TripleA is installed here; the map is at
`C:\Users\ndhay\triplea\downloadedMaps\world_war_ii_pacific-master.zip` (2597
entries, kept zipped). Internal layout: `world_war_ii_pacific-master/map/` holds
`polygons.txt`, `centers.txt`, `map.properties`, and `games/ww2pac40.xml`
(Pacific 1940 Original), `ww2pac40_2nd_edition.xml`, `ww2pac40_balanced_veqryn.xml`.
Saved games dir: `C:\Users\ndhay\triplea\savedGames`. To run the converter on
real data, extract the `map/` geometry files (or read straight from the zip).

## Progress (updates as we go)
- ✅ JDK 21 installed; `:smoke-testing:test --tests AiGameTest` PASSED — engine builds + plays a full AI game headless here.
- ✅ `game-app/game-web-server` module created, wired to `:game-core`, passes `:game-web-server:check`.
- ✅ `MapGeometryConverter` (polygons.txt/centers.txt via engine's `PointFileReaderWriter`) + `GameDataLoader`/`MapConnections` (parse game XML → adjacency). Unit-tested.
- ✅ `GeometryExportCli` + `:game-web-server:exportGeometry` Gradle task. Run on the **real Pacific map**: `geometry.json` = 153 territories, 149 with connections, at `C:\Users\ndhay\triplea-webport-work\world_war_ii_pacific\geometry.json`.
- ⚠️ Finding: `Box1/Box2/Box3` (UI decoration boxes) + `Suiyuyan` (polygons/XML name mismatch) are in geometry but not game data; 0 playable territories lack geometry. The web client must tolerate geometry-only territories.
- Build note: set `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'` before `gradlew` until shells pick up the machine var. Work dir for extracted Pacific files: `C:\Users\ndhay\triplea-webport-work\world_war_ii_pacific\`.

## Phase 1 status — static render WORKING
- ✅ `web-client/` (Vite + React + TS + Canvas 2D). `MapCanvas` renders `geometry.json` polygons tinted by initial owner; center dots drawn. **Verified live** on real Pacific 1940 — 153 territories, correct positions and faction colors, no console errors. `Suiyuyan`/`Box1-3` fall back to gray as designed.
- Run it: copy `C:\Users\ndhay\triplea-webport-work\world_war_ii_pacific\geometry.json` → `web-client\public\geometry.json`, then `npm --prefix web-client run dev` (or from `web-client/`: `npm run dev`). Open `http://localhost:5173/`. `node` v22 / `npm` 10 already installed. `public/geometry.json` is gitignored (regenerate via `:game-web-server:exportGeometry`).
- Tooling note: `npm --prefix <dir> install` misbehaved on Windows (looked for repo-root package.json); use `Push-Location web-client; npm install` instead.

## Phase 2 status — live spectator WORKING
- ✅ `WebGameHost`/`WebLaunchAction` run an AI `ServerGame` in-process (in-memory prefs isolate the user's TripleA settings; autosaves redirected to a temp dir). `:game-web-server:runAiGame` smoke-runs it.
- ✅ `StateProjector` → `StateSnapshot` (round/step/currentPlayer/owners). `SpectatorWebSocketServer` (org.java_websocket, already on classpath via root build) broadcasts; `WebSpectatorServer` main runs the game and publishes per step. `:game-web-server:runSpectator --args="<gameXml> [port=8080] [maxRounds] [stepDelayMs]"`.
- ✅ Client connects to `ws://<host>:8080`, live-updates owners + a round/step/turn status bar. Verified live on Pacific 1940.
- To demo: terminal 1 `:game-web-server:runSpectator --args="...\map\games\ww2pac40.xml 8080 6 350"`; terminal 2 `npm --prefix web-client run dev`; open http://localhost:5173/.

## Immediate next action when resuming
Two tracks, pick per priority:
- **Phase 3 (playable)**: implement `WebPlayer implements Player` + full `WebDisplay implements IDisplay`, wire human seats so the browser submits purchase/move/battle/place decisions (delegates enforce rules). Pacific 1940 forces naval/scramble/kamikaze `Player` methods early.
- **Phase 1/2 polish**: base map tiles under polygons; pan/zoom; `water` flag (sea zones blue); units-per-territory in `StateSnapshot` + unit sprites; unify HTTP+WS under one server (currently Vite dev + separate WS port).
