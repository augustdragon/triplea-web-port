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

1. **The engine stays unmodified.** Adapt it only at existing seams (`Player`, `IDisplay`,
   `LaunchAction`, `GameDataManager`/`GameData.toBytes()`). If a `game-core` change seems necessary,
   **STOP and reconsider** — respect the save-game serialization and `@RemoteActionCode`
   constraints documented in the root `AGENTS.md`. (Note: the web port's own networking does **not**
   travel over the `@RemoteActionCode` path; it uses `WebPlayer`/`WebDecisionBridge` over
   WebSocket+JSON. That constraint matters here only as a "don't modify game-core" guardrail.)

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
