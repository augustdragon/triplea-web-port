# Refactor Plan — web-module decomposition (first slice)

> Status: **planned, not started (2026-06-04).** Scoped from the refactoring-assessment review (see
> the attached assessment + the discussion). Charter Hard Rule #1 is now a strong default rather than
> a ban (ADR-001), but **this plan deliberately stays out of `game-core`** — the high-leverage,
> low-risk work is in our own boundary code, and every engine edit costs upstream mergeability.

## Why this slice

The assessment's structural facts were accurate, but its headline ("clean web concerns out of engine
interfaces first") was misdirected: the web-socket coupling in `IDisplay`/`IDelegateBridge` is
**upstream legacy**, not web-port leakage, so touching it would diverge from upstream for no web-port
gain. The genuinely valuable, charter-safe targets are the two god-classes **we own**, plus a
guardrail. None of this changes game behavior, the save format, or the remote contracts.

Grounding evidence: every bug this session (victory firing, `wasNull` seat poisoning, the seat
handoff, invisible logging) lived in *web-module* boundary code — `GameController` (986 lines) and the
control-plane↔container seam — not in the engine. That's where the modularity pressure actually is.

## Goal

Decompose the two highest-pressure web-adapter classes into focused, unit-testable pieces, and add a
guardrail that keeps web-port types out of `game-core` — all behavior-preserving.

## Non-goals (this slice)

- No `game-core` edits (permitted now, but not needed here).
- No game-rule, save-serialization, or `@RemoteActionCode` changes.
- No Gradle module-graph split.
- No new browser features — pure internal decomposition.

## Safety net

Each extraction is behavior-preserving and lands on top of the regression net we already have:
`SeatPlanTest`, `LobbySeatBindingTest`, `SeatAssignmentJsonTest`, `LobbyDaoSeatMappingTest`,
`HostVictoryDetectorTest`, and the Playwright politics e2e. Discipline: **one extraction per commit**,
`:game-web-server:check` + `:game-control-plane:check` green after each, and a manual playable smoke
before merging the sequence. New seams get focused unit tests as they're created.

---

## Item 1 — Decompose `GameController` by responsibility (986 lines)

Today `GameController` owns setup, lifecycle, auth, broadcasting, save, reporting, and the run loop.
Responsibility clusters (from the current method map) and their proposed homes:

| Cluster | Current methods | Extract to |
|---|---|---|
| **Run loop + watchdog + turn deadline** | inner `Session.run()`, `manageTurnDeadline`, `bootDeadlines` | keep as `Session`/`GameRunLoop` (already semi-separate) — pull it to its own file |
| **Connection auth** | `handleAuth`, `armAuthTimeout`, `closeUnauthorized`, `pruneClosedConns`, `usedNonces`, `authedConns`/`hostConns` | `ConnectionAuthenticator` (lobby ticket gate) |
| **Seat lifecycle** | `doStartGame`, `doResumeGame`, `doSeatToAi`, `doReclaim`, `doLobbyStart`, `maybeAutoStart`, `doReturnToSetup`, `onSeatVacated` | `GameLifecycle` (the start/resume/seat→AI/reclaim state machine) |
| **Broadcast/publish** | `publishSeats`, `publishNotes`, `publishObjectives`, `reportPresenceIfChanged` (+ `StateProjector` use) | `GameBroadcaster` |
| **Save persistence** | `autosave`, `peekSave`, `resumeSlot`, `slotFor` | `SaveStoreFacade` |
| **Control-plane reporting** | `reportTurn`, `reporter.*` calls, turn-deadline reports | `ControlPlaneReporting` wrapper |

`GameController` shrinks to a coordinator: own routing (`onClientMessage`/`handleControl`) and hold
the collaborators above. **Sequence** (lowest-risk first, each its own commit):

1. Extract `SaveStoreFacade` (pure, no shared mutable state) — easiest, proves the pattern.
2. Extract `ControlPlaneReporting` (thin wrapper over `reporter`).
3. Extract `GameBroadcaster` (publish/presence).
4. Extract `ConnectionAuthenticator` (auth + ticket + nonces).
5. Move `Session`/run-loop to its own file; inject the collaborators.
6. Extract `GameLifecycle` (start/resume/seat→AI/reclaim) — most state, do last with the seams stable.

**Done when** `GameController` no longer directly owns save/auth/broadcast/reporting, each collaborator
has a narrow constructor and a focused test, and all run modes still work
(`runPlayable`, `runSpectator`, AI/smoke runner).

## Item 2 — Decompose `WebPlayer` by decision workflow (994 lines)

`WebPlayer` implements the engine `Player`; `start(stepName)` routes each phase, and the engine also
calls per-decision hooks (`selectCasualties`, `retreatQuery`, air/carrier queries, confirmations).
Split into workflow handlers; `WebPlayer` stays the `Player` implementation and delegates.

Candidate handlers (each: build request DTO → `WebDecisionBridge.await` → reconstruct engine objects
from stable names/ids → submit to the delegate → re-prompt on validation error):

- `PurchaseDecisionHandler`, `MoveDecisionHandler`, `PlaceDecisionHandler`
- `PoliticsDecisionHandler`, `TechDecisionHandler`
- `BattleDecisionHandler` (`selectCasualties`, attack-subs/transports/units, shore bombard)
- `RetreatDecisionHandler` (`retreatQuery`, `scrambleUnitsQuery`, `selectUnitsQuery`)
- `AirPlacementHandler` (`selectTerritoryForAirToLand`, carrier/fighter moves, AA confirmations)

**Sequence:** one workflow per commit, starting with the self-contained ones (purchase, place) before
the entangled battle/retreat group. Each handler gets a unit test that drives a fake `WebDecisionBridge`
(reply in → engine submission out) **without a live WebSocket** — the testability win the assessment
called out, and the natural home for the per-workflow tests we started.

**Done when** `WebPlayer` is orchestration + engine overrides only, and each handler is independently
unit-tested.

## Item 3 — Guardrail: keep web-port types out of `game-core` (reversed from the assessment)

The assessment's guardrail pointed the wrong way (it would fail on existing upstream imports). Reverse
it: **fail the build if `game-core` imports `org.triplea.web.server.*`** (our browser-protocol/JSON
types). This prevents the violation the assessment imagined had already happened, and it's the
charter's "prefer seams/adapters" rule made executable.

Implementation: a focused check in the existing `.build/code-convention-checks` path or a small Gradle
task; documented as **web-port boundary protection**, not a style preference. Acceptance: passes today;
adding `import org.triplea.web.server.*` to any `game-core` file fails it.

## PR sequence

1. Guardrail (Item 3) — small, independent, protects everything that follows.
2. `GameController` extractions (Item 1), in the 6 steps above — one per commit.
3. `WebPlayer` handlers (Item 2) — one workflow per commit.

## Out of scope / later (now permitted, still deferred)

- Any `game-core` modularization (extract Swing/AI/legacy-networking; typed rule models). Real debt,
  but it forfeits upstream mergeability — if pursued, prefer contributing it **upstream**, not forking.
- Trimming the headless container's runtime dependency on desktop/lobby jars (a packaging concern,
  not correctness). Worth a separate look if container size/startup becomes a problem.
