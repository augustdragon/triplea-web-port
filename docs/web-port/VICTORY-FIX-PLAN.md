# Plan — Host-side victory detection (Fix A)

> Status: **IMPLEMENTED & verified (2026-06-04).** Engine (`game-core`) stays **unmodified**; the fix
> lives entirely in the web host (`game-web-server`). Built per this plan: `GameDataDelegateBridge`
> (read-only proxy bridge), `HostVictoryDetector`, and detection wired into `GameController.run()`.
> Verified: detector unit test + engine-constraint test pass, and an all-AI Pacific game now ends
> `VICTORY detected at round 14 — winners: [Japanese]` instead of grinding to the round cap.
> The deferred round-cap (uncap conceded games) remains a follow-up.

## 1. Problem & root cause (recap)

In a hosted game, a satisfied **victory condition never ends the game** — it runs to the round cap
with no winner. Traced precisely:

1. The condition is correctly evaluated **satisfied** by the engine (`conditionTest = true`).
2. The victory trigger is collected and tested-satisfied.
3. `EndRoundDelegate.start()` calls `TriggerAttachment.triggerVictory(...)`.
4. `triggerVictory` resolves the victory **notification message** via the bridge's `ResourceLoader`;
   it then calls `signalGameOver(...)` **only `if (victoryMessage != null)`**
   (`TriggerAttachment.java:1489`, `:1515`).
5. Our host sets an **empty** loader — `WebLaunchAction.java:51`: `new ResourceLoader(List.of())` — so
   `getMessage(...)` returns `null`, `signalGameOver` is **skipped**, and no winner is recorded.

This reproduces in pure `game-core` with the engine's own `MockDelegateBridge` (also no loader): see
`TriggeredVictoryFiringTest`. So **victory firing is coupled to a UI/notification resource a headless
service doesn't provide.** The *condition/data side is correct*; only the engine's *acting* on it is
gated. We therefore detect victory ourselves and end the game through our own surfacing.

**Note (other victory modes):** only the **triggered-victory** path is resource-loader-gated. The
direct modes (economic, VP, victory-cities, capital-capture) call `signalGameOver` directly in
`EndRoundDelegate.start()`, so for those `getEndRoundDelegate().getWinners()` *is* populated. The fix
handles both.

## 2. Design goals

- **Map-general.** Read whatever victory the *loaded* map defines. Pacific 1940, Europe 1940, and
  Global 1940 have different triggers/territories/sides (and different victory *modes*). **No
  per-map, no hardcoded territories.**
- **Faithful.** Reuse the engine's own condition evaluation, not a reimplementation of the rules.
- **Conservative.** Never declare a victory the engine wouldn't. If a condition can't be evaluated
  headless, treat it as *not* satisfied (fail closed).
- **Engine untouched.** Read `GameData`; act in our loop.

## 3. Components

### 3.1 `GameDataDelegateBridge` (minimal `IDelegateBridge` adapter) — NEW
The engine's condition evaluator (`collectTestsForAllTriggers` → `RulesAttachment.isSatisfied(map,
bridge)`) needs a bridge, but for ownership/VP conditions it only calls `bridge.getData()`. So:

- `getData()` → the live `GameData`.
- `getResourceLoader()` → `Optional.empty()` (we deliberately don't fire the engine's UI path).
- All other ~14 methods (`getRandom`, `getHistoryWriter`, `addChange`, `stopGameSequence`,
  `enter/leaveDelegateExecution`, displays/sounds, `sendMessage`, remote players) → throw
  `UnsupportedOperationException`.

Rationale: if a victory condition needs anything beyond `getData()` (e.g. a `chance`/dice condition —
not used by victory triggers), the throw is caught by the detector (§3.2) and that trigger is treated
as unevaluable → not fired. Fail-closed, never a false win.

### 3.2 `HostVictoryDetector` — NEW
`Optional<Winners> detect(GameData data)` — returns winners + a human label, or empty. Run once per
round boundary (§3.3). Two independent sources, unioned:

- **Direct modes:** read `data.getEndRoundDelegate().getWinners()`. Non-empty ⇒ the engine already
  signalled a winner (economic/VP/victory-cities/capital). Covers any map; also the safety net for
  the never-verified case where the engine signals over but our custom loop didn't auto-stop.
- **Triggered mode:** replicate `EndRoundDelegate`'s collection with the engine's own helpers (no
  hardcoding):
  ```
  match = availableUses AND whenOrDefaultMatch(null,null) AND (activateTriggerMatch OR victoryMatch)
  toFire   = TriggerAttachment.collectForAllTriggersMatching(allPlayers, match)   // bridge-free
  tested   = TriggerAttachment.collectTestsForAllTriggers(toFire, gameDataBridge) // §3.1 bridge
  satisfied victory triggers = toFire ∩ isSatisfiedMatch(tested) ∩ victoryMatch
  ```
  Wrapped in try/catch so an unevaluable condition drops that trigger (fail-closed).

**Winner derivation (triggered):** `TriggerAttachment.getPlayers()` is **private**, so winners =
the **alliance of the satisfied trigger's beneficiary** (the player the trigger/condition is attached
to), via `data.getRelationshipTracker()` (allies + self). Map-general, no private access. (For the
maps checked this equals the trigger's `players` list; minor over-inclusion is acceptable for
surfacing "the Axis won." — verify the attached-to accessor during build.)

### 3.3 Loop integration — `GameController.Session.run()` (CHANGED)
At each **round boundary** (we already detect round increments for diagnostics):

1. After the `endRound` step for round *N* completes, call `HostVictoryDetector.detect(data)`.
2. If winners present and `endReason == null`: set `endReason = GameEndReason.VICTORY`, stash the
   detected winners, and break the loop (`alive` stays true so the existing `finally` reports it).
3. The existing surfacing path publishes `{type:"gameOver", reason:VICTORY, winners}` and
   `reporter.gameFinished("VICTORY", winners)` → DB `games.end_reason`/`winner` → `/connect`
   end-summary. **Use the detector's winners** (for triggered maps `winnerNames()` / engine
   `getWinners()` is empty, so the existing classifier must defer to the detector).

Cadence matches the engine (victory evaluated at end of round), so no mid-round surprises.

## 4. Correctness & edge cases

- **Round cap interaction.** Detection runs every round boundary, so a real game ends on victory the
  round it's achieved — *before* any cap. The deferred "uncap conceded games" change (separate) is
  still wanted so a conceded game has the rounds to *reach* a win.
- **Both sides / multiple triggers.** Union all satisfied victory triggers' winners; if somehow both
  alliances satisfy in the same round, report all (engine would fire the first; we won't be *less*
  correct, and it's vanishingly rare).
- **Idempotency.** Once `endReason` is set we stop; detection won't double-fire.
- **Non-victory maps / no triggers.** `collectForAllTriggersMatching` returns empty, `getWinners()`
  empty ⇒ detector returns empty ⇒ no behavior change.
- **Fail-closed.** Any evaluation exception ⇒ that trigger ignored ⇒ never a spurious win.

## 5. Testing

- **`TriggeredVictoryFiringTest` (game-core, exists as diagnostic):** refocus into a permanent
  regression that documents the engine constraint — condition tests `true`, yet
  `EndRoundDelegate.getWinners()` stays empty **without a resource loader**. Guards the assumption the
  whole fix rests on. (Drop the noisy `DIAG` prints.)
- **`HostVictoryDetectorTest` (game-web-server) — NEW, the core regression:** load `victory_test.xml`
  (or Global/Pacific), seed a player's alliance into a victory condition via
  `ChangeFactory.changeOwner`, assert `detect(...)` returns the expected winners; assert it returns
  empty before seeding. Uses `testFixtures(":game-core")` for `TestMapGameData`.
- **Host-loop integration (game-web-server) — NEW:** drive a seeded game through the loop (or
  `AiGameRunnerCli`-style), assert it ends with `endReason == VICTORY` and the right winners.
- **Regression net:** an AI-only `--max-rounds` game still ends `ROUND_CAP` with no winner (unchanged
  for no-victory cases).

## 6. Files

**New:** `game-web-server/.../game/GameDataDelegateBridge.java`,
`game-web-server/.../game/HostVictoryDetector.java`,
`game-web-server/src/test/.../game/HostVictoryDetectorTest.java` (+ loop integration test).

**Changed:** `game-web-server/.../game/GameController.java` (detect + end on victory).

**Cleanup:** retire the diagnostic scaffolding — the `AiGameRunnerCli` per-round probe and the
verbose parts of `VictoryCityLog` (keep a trimmed victory-progress logger only if useful);
de-`DIAG` the engine test.

## 7. Risks / open questions

- **Beneficiary accessor.** Confirm how to read a trigger/condition's attached-to player
  (`getAttachedTo()` or equivalent) for winner derivation; fallback is the condition's player.
- **`collectTestsForAllTriggers` breadth.** Verify it only touches `getData()` for ownership victory
  conditions on our maps; the fail-closed catch covers the rest but we want the common path clean.
- **Winner list vs. alliance.** Alliance-of-beneficiary may slightly over-list vs the trigger's
  `players`; acceptable for surfacing, revisit if a map needs exactness.

## 8. Out of scope (tracked follow-ups)

- **Round cap:** uncap games that have ever had a human (the deferred concede-recap fix).
- **Diagnostic cleanup** (above) lands with this change.
- Real OAuth, "your turn" Web Push (unrelated Phase-4 tail).
