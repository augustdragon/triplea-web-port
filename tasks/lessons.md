# Lessons

Entries: the pattern → what went wrong → the rule to follow.

## Verify the build environment before writing code
- **What went wrong:** Was about to scaffold a new Gradle/Java module before checking the toolchain. The machine had NO JDK (only a VASSAL-bundled JRE) — nothing could have compiled or been verified.
- **Rule:** On any build/compile task, confirm the required toolchain exists *first* (`java -version`, `JAVA_HOME`, install dirs). Surface a missing toolchain as a blocker immediately rather than producing unverifiable code. Bias toward environment/config causes on platform-specific work (per global CLAUDE.md).

## Don't use PowerShell syntax in the Bash tool
- **What went wrong:** Ran `git commit -m @'...'@` (a PowerShell here-string) inside the **Bash** tool. Bash treated `@` as a literal char, leaving a stray `@` on the commit subject and body. Fixed with `git commit --amend`.
- **Rule:** Bash tool = bash quoting (single/double quotes, `$'...'`). PowerShell tool = PowerShell syntax (`@'...'@` here-strings, `$env:`). Never mix. For multiline commit messages in bash, use multiple `-m` flags.

## winget installs aren't visible to running shells
- **What went wrong:** After `winget install` of JDK 21, the machine `JAVA_HOME`/PATH updated (registry) but the agent's spawned shells still didn't see `java`.
- **Rule:** After installing a tool mid-session, don't assume PATH updated. Read the machine env var from the registry (`[Environment]::GetEnvironmentVariable('JAVA_HOME','Machine')`), locate the install dir, and set `$env:JAVA_HOME` / prepend PATH explicitly per command until a fresh shell is available.

## Gradle project paths here are flat
- **What went wrong (potential):** AGENTS.md shows `./gradlew :game-app:game-core:test`, but `settings.gradle.kts` registers projects flat via `include(":game-core")` + `projectDir` override.
- **Rule:** Trust `settings.gradle.kts` for project paths: use `:game-core`, `:smoke-testing`, `:game-headless`, etc. — not `:game-app:...`.

## When integrating with a large codebase, copy its proven recipe — don't invent
- **What worked:** The headless engine-boot path was non-obvious, but `smoke-testing/GameTestUtils` already had the exact in-process recipe (GameParser.parse → assign AI players → build ServerGame with LocalNoOpMessenger + NO_OP_SENDER → runNextStep). Reusing `PointFileReaderWriter` (polygons), `GameParser` (rules), and that recipe saved re-deriving fragile setup.
- **Rule:** Before writing integration glue, find the existing seam — test utilities, `Headless*` classes, the interface the real UI implements — and copy the working recipe. The codebase usually already shows how.

## A minimal interface impl: stub only what's truly never called — run to find out
- **What went wrong:** `WebLaunchAction` blanket-threw `UnsupportedOperationException` on the LaunchAction methods I assumed were lobby-only. But `getAutoSaveFileUtils()` IS called mid-run (ServerGame writes a round-boundary autosave — separate from `setDelegateAutosavesEnabled(false)`), so the game crashed at the first round transition.
- **Rule:** Don't assume which interface methods the runtime invokes. Implement the obvious ones, then RUN it and let the stack trace reveal the rest. `setX(false)`-style flags often don't disable every related code path.

## Isolate an embedded/headless server from the user's real app settings
- **What worked:** Used `ClientSetting.setPreferences(new MemoryPreferences())` (not `ClientSetting.initialize()`, which uses the real on-disk prefs) and redirected autosaves to a temp dir. The server neither reads the user's GUI prefs nor persists changes back.
- **Rule:** When embedding an app's engine, run it on isolated/in-memory config and a throwaway working dir, so the server can't mutate or leak the user's real environment.

## Verify integration work by running the real artifact, not just unit tests
- **What worked:** Each web-port increment was proven by running it (smoke test, `runAiGame`, `runSpectator` + a live browser screenshot) — which surfaced real-world facts unit tests wouldn't: geometry-only territories (`Box1-3`, `Suiyuyan`), the autosave call path, live ownership changes. Pairs with the global "verify before complete" rule.
- **Rule:** For integration/porting, a green unit test isn't proof. Run the actual CLI/server/UI against real data and observe the output.

## Drive browser/canvas verification from the data, not from pixel-hunting
- **What went wrong:** Verifying a land move meant clicking a source territory then an adjacent enemy on a Canvas map. Guessing screen pixels for a named territory was slow and wrong — the live pan/zoom transform isn't known, so map→screen conversion drifts.
- **Rule:** For canvas-app verification, pick targets from the underlying data, not the picture. Read `geometry.json` (PowerShell `ConvertFrom-Json`) for adjacency/owners/centers to choose a valid move (e.g. "Kiangsu borders Anhwe, which is Chinese"), then use the in-app hover tooltip to confirm a tile's identity before clicking. Confirm the *outcome* from data too (hover Anhwe → "owner: Japanese").

## Re-send outstanding decision requests on (re)connect, not just latest state
- **What went wrong:** The WebSocket server's catch-up re-sent only the latest state snapshot. A browser that connected *after* a blocking decision request was broadcast never saw it, so the engine thread sat parked forever waiting for a reply.
- **Rule:** For a request/response bridge over a broadcast socket, the server must remember the outstanding request and re-send it on connect (clear it when any reply arrives). State catch-up alone strands a late/reloading client mid-decision.

## Engine `whoAmI` label must be a single colon-free token
- **What went wrong:** Constructed `WebPlayer` with label `"Human:Web"`. `ServerGame` sets `whoAmI = (isAi?"AI":"Human") + ":" + playerLabel`, and `GamePlayer.setWhoAmI` requires *exactly two* colon-separated parts — `"Human:Human:Web"` threw and killed the game on the first step.
- **Rule:** A player's label is a bare token (e.g. `"Web"`), never `"Type:Label"`. When feeding a value the engine will compose into a delimited string, don't pre-include the delimiter.

## `implementation` deps are not transitive — add the module you import directly
- **What went wrong:** `game-web-server` depends on `:game-core`, whose own deps are `implementation` (not `api`). Using `org.triplea.java.collections.IntegerMap` directly failed to compile — it lives in `:java-extras`, which `game-core` doesn't re-export.
- **Rule:** If you `import` a type, declare a direct dependency on the module that *defines* it (here `implementation(project(":java-extras"))`); don't rely on transitive visibility through `implementation` edges.

## Hit-test overlapping polygons in reverse draw order (topmost wins)
- **What went wrong:** `MapCanvas.territoryAt` returned the *first* territory whose polygon contained the click, iterating the array forward. TripleA sea-zone polygons are large and overlap coastal/island land; since sea zones come earlier in the array, a click on a visible island resolved to the sea zone *beneath* it. A diagnostic showed **29 of 90 land territories** (≈ every Pacific island) had their centroid inside a water polygon — all unclickable. This silently blocked amphibious/island movement entirely.
- **Rule:** When polygons overlap and you render in array order (later = painted on top), hit-test in **reverse** so a click resolves to what the user actually sees. "First match" is wrong for any overlapping-shape map; match the draw order. Diagnose "can't select X" UI bugs by checking polygon containment + array order against the render order, not by pixel-nudging.

## Broadcast state at the granularity the user acts, not just at phase boundaries
- **What went wrong:** the playable runner published a `StateSnapshot` only after each `runNextStep()`, but a whole move phase runs *inside* one step (the `handleMove` loop blocks the game thread), so the browser's `units` map didn't change until the phase ended — units appeared frozen while the player moved them. The engine's `UnitCollection` was correct all along; only the *projection cadence* was wrong.
- **Fix:** `WebPlayer.handleMove` now re-projects and broadcasts (`bridge.publishState(StateProjector.project(data))`) after each accepted move, via a second `Consumer<String>` (`server::publishState`) on the bridge. Map updates per-move.
- **Rule:** Push state updates at the granularity the user *acts*, not the engine's coarser loop boundaries. When a UI feed looks stale, check the publish cadence vs. the mutation cadence before suspecting the data. (Verification corollary: confirm a canvas action from the freshest authoritative feed — re-select and read the count delta — knowing which feed updates when.)

## Don't hand-wave a user's bug report — investigate the live state first
- **What went wrong:** User reported combat didn't trigger when Japanese units entered Chinese-held Kiangsi. I dismissed it twice ("Kiangsi is friendly / originally Japanese, you're mistaken") on reasoning alone. The user pushed back with the correct game logic (opposing units can't co-exist without combat) and a screenshot proving co-located Japanese + Chinese units with "No battles yet." It was a real bug.
- **Rule:** A user reporting a behavioral bug has observed something. Before concluding they're mistaken, reproduce or inspect the actual state (live snapshot, logs, the engine's own data). Their domain intuition about how the game *should* behave is evidence, not noise. Per global rule 6 (autonomous bug fixing): point at the live state, then resolve — don't argue from assumptions.

## The engine does not auto-fight battles — the seat must drive the combat phase
- **What went wrong:** `WebPlayer` handled purchase/move/place but not the battle step, assuming combat resolved itself once units were moved in. It doesn't: a `ServerGame` parks on the human seat during the combat step waiting for the player to fight each battle. With no handler, battles were silently skipped and attacker+defender sat co-located — exactly the reported bug.
- **Fix:** `handleBattle()` mirrors `AbstractAi.battle()` / `TripleAPlayer.battle()`: loop `IBattleDelegate.getBattleListing().getBattlesMap()`, call `fightBattle(where, type.isBombingRun(), type)` until none remain, ignoring `BattleDelegate.isBattleDependencyErrorMessage` (dependency-order) errors. Wired to `GameStep.isBattleStepName`.
- **Rule:** When porting a seat that implements the `Player` RPC interface, every interactive *phase* needs a handler — purchase, move, **battle**, place, etc. The combat step is interactive (casualties + retreat) and must actively call `fightBattle`; moving units in does not resolve combat. Cross-check coverage against `TripleAPlayer`/`AbstractAi` step dispatch.

## Match the test game-XML to the rulebook edition; the engine spans variants
- **What went wrong:** Built/verified 3a–3b against `ww2pac40.xml` (1st-ed "Original"), but the saved rules PDF is **2nd edition** — they differ in real combat stats (e.g. aaGun cost 6 vs 5). Cross-checking battle logic against the PDF would have surfaced phantom "bugs" that are just edition differences. Also: the 2nd-ed XML existed in the map zip but wasn't in the first extraction.
- **Rule:** A printed rulebook describes *one* ruleset; the engine is parameterized across many (multiple game XMLs + ~130 properties). Pick the target edition explicitly and point the runner/export at the matching XML. The engine XML governs behavior on any divergence; the rulebook is a cross-check, not the authority.
