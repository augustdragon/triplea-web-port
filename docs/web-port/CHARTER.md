# Web-Port Charter — what governs this fork

This repository is a **web-port fork** of TripleA. This file is the **source of truth** for what
the project is and the rules that govern the work. The root [`AGENTS.md`](../../AGENTS.md) and the
nested module `AGENTS.md` files document the underlying engine and Java conventions (still
accurate), but they predate the fork; **this charter takes precedence on anything fork-specific.**

## What this fork is

Replace TripleA's dated Swing UI and custom-socket networking with a modern, **browser-hosted
React client over WebSocket + JSON**, plus a Java **control plane** for accounts, lobby, and
per-game orchestration — **reusing the `game-core` engine unchanged**. The engine's ~68K lines of
game rules and ~295 community maps keep working because the rules are not touched; only the
transport and client are new.

- Detailed rationale and architecture: [`design.md`](design.md) (living spec).
- Multiplayer build-out (auth, lobby, orchestrator): [`PHASE-4-MULTIPLAYER.md`](PHASE-4-MULTIPLAYER.md).
- Current milestones and status: [`../../tasks/todo.md`](../../tasks/todo.md).

## Hard rules

1. **Prefer to leave the engine unmodified — change it deliberately, never casually.** The default
   is to adapt `game-core` at its existing seams (`Player`, `IDisplay`, `LaunchAction`,
   `GameDataManager`/`GameData.toBytes()`) and to keep new browser/control-plane concerns in the web
   modules. This is a **strong default, not a prohibition**: modifying `game-core` is allowed when it
   is genuinely the right call — but it is a considered decision that weighs real costs, not a reflex.

   Before changing `game-core`, weigh:
   - **Upstream mergeability (the big one).** Every engine edit diverges from
     `triplea-game/triplea` and erodes our ability to pull upstream rule/map fixes. Prefer changes
     that could be contributed *upstream* over fork-only divergence.
   - **Save-game serialization.** `GameData` and its object graph use Java serialization for saves;
     renaming, moving, or removing serialized fields/classes can break existing saves. Treat the
     on-disk format as a compatibility contract.
   - **`@RemoteActionCode` / remote contracts.** `IDisplay`, `IDelegateBridge`, and peers carry the
     **legacy networked-desktop-play** remote API (this is upstream code, not web-port leakage). The
     web port does **not** use that path — it uses `WebPlayer`/`WebDecisionBridge` over
     WebSocket+JSON — so touching those interfaces is risk without web-port benefit. Avoid unless the
     desktop/remote path itself is the goal.

   Rule of thumb: refactor **freely** in the web modules (`:game-web-server`, `:game-control-plane`,
   `web-client/`); in `game-core`, prefer seams and adapters and reserve direct edits for cases where
   the benefit clearly outweighs the divergence cost. When unsure, surface the trade-off rather than
   silently editing the engine.

2. **The web port is the active workstream.** The desktop client (`:game-headed`) still builds, but
   it is not the focus. New work targets `:game-web-server` (game container), `:game-control-plane`
   (control plane), and `web-client/` (React).

3. **Work lives on this fork.** `origin` is `augustdragon/triplea-web-port` (this fork). Pushing to
   this fork's `origin` is expected (it is the cross-machine sync point). **Never push to the
   upstream `triplea-game/triplea` repository.**

## How the docs relate

| Doc | Scope |
|-----|-------|
| **This charter** | Fork-specific governance + orientation (source of truth) |
| Root `AGENTS.md` | Underlying engine architecture + Java/build conventions (upstream; still accurate) |
| Module `AGENTS.md` (`game-web-server/`, `game-control-plane/`, …) | Per-module guidance; takes precedence over root within its directory |
| `docs/web-port/*` | Design rationale, state model, phase plans, manual test script |
| `tasks/todo.md`, `tasks/lessons.md` | Current milestones; accumulated working lessons |

## Build & run quick reference

- Java 21 (SDKMAN), Node 22 (nvm); Gradle project paths are **flat** (`:game-core`,
  `:game-web-server`, `:game-control-plane` — not `:game-app:game-core`).
- Game container (browser plays a map): `./gradlew :game-web-server:runPlayable --args="<gameXml>"`.
- Control plane (P4.3+): `docker compose -f .docker/web-port-db.yml up -d`, then export the required
  env and run. Minimum: `CONTROL_PLANE_DB_PASSWORD=triplea_web` and `CONTROL_PLANE_JWT_SECRET=<≥32 chars>`.
  For local auth: also `CONTROL_PLANE_DEV_LOGIN=true` and `CONTROL_PLANE_ALLOWLIST="google:<subject>,…"`,
  then `POST /api/dev-login {"subject":"…"}` to get a session cookie. Run with `./gradlew :game-control-plane:run`.
  (`profile=prod` forbids dev-login and marks cookies Secure; startup fails fast if misconfigured.)

## Decision log (ADR-style)

Material changes to the rules above are recorded here with their reasoning, so the governance isn't
a set of unexplained commands. Newest first.

### ADR-001 — Hard Rule #1: engine modification goes from *prohibited* to *strong default* (2026-06-04)

**Status:** Accepted.

**Context.** Hard Rule #1 originally **banned** any `game-core` change ("STOP and reconsider"). A
refactoring assessment then proposed cleaning up "web-port concerns that crossed into engine
interfaces" — chiefly `IDelegateBridge.sendMessage(WebSocketMessage)` and the `WebSocketMessage`
DTOs nested in `IDisplay`. Investigation found two things:
- Those web-socket references are **upstream legacy networking**, not web-port leakage. `git blame`
  attributes them to the upstream maintainer (Dan Van Atta, 2020 and 2022) — years before this fork.
  They are part of TripleA's own networked-desktop-play remote API, which the web port does not use.
- The absolute ban was **self-imposed, not required**. The GPLv3 license explicitly grants the right
  to modify the engine; "don't touch `game-core`" was our strategy, not a legal or technical law.

So the real reason to be cautious with `game-core` is **strategic** (upstream mergeability) plus two
genuine **technical hazards** (save-game serialization; the `@RemoteActionCode` remote contracts) —
not an absolute rule. An unconditional STOP both overstated the constraint and, ironically, pointed
refactoring energy at upstream legacy code we have no reason to touch.

**Decision.** Downgrade "the engine stays unmodified" to a **strong default with an informed
exception**: `game-core` changes are permitted as deliberate decisions that weigh upstream
divergence, serialization compatibility, and the remote contracts. Web-module refactoring
(`:game-web-server`, `:game-control-plane`, `web-client/`) is explicitly free.

**Consequences.**
- We can now *consider* engine changes on their merits instead of reflexively stopping — "handcuffs
  off while we think," with the costs kept visible rather than hidden behind a ban.
- The genuine hazards (mergeability, save format, remote contracts) are now stated as costs to weigh,
  so relaxing the rule doesn't lose the protections that actually mattered.
- Near-term refactoring nonetheless stays **in the web modules** (decompose `GameController` and
  `WebPlayer`; add a guardrail that blocks web-port types from leaking *into* `game-core`). Touching
  the engine remains available but unused for now — see the refactor plan in `docs/web-port/`.
