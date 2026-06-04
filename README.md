# TripleA Web Port

A **browser-based, server-authoritative port of [TripleA](https://github.com/triplea-game/triplea)** —
the open-source Axis & Allies–style strategy engine. This fork replaces TripleA's desktop Java/Swing
client and custom-socket networking with a **React single-page app over WebSocket + JSON** and a Java
**control plane** for accounts, lobby, and per-game orchestration — while **reusing the `game-core`
engine completely unchanged.**

> **Status: work in progress / experimental.** The engine, web client, multiplayer control plane,
> reconnect/async play, turn timers, and reclaimable AI takeover are working; a single-VM deployment
> (Docker-free) is being stood up. This is a personal project, not an official TripleA release.

## Why this exists

The TripleA *engine* is excellent — ~68K lines of faithful wargame rules and ~295 community maps. The
*delivery* is the friction: it must be installed, multiplayer is self-serve or play-by-email, and it's
desktop-bound. A&A games run for hours to days across time zones and devices, so this fork keeps the
engine and replaces **only the transport and the client** — adding the operational niceties a modern
board-game service provides: accounts, a lobby, **resumable asynchronous games**, host-set **turn
timers**, and **AI cover for absent players**, all from a link in the browser with nothing to install.

Full rationale: [`docs/web-port/WHY-WEB-PORT.md`](docs/web-port/WHY-WEB-PORT.md).

## What's the same vs. what's new

**Identical to desktop TripleA** (because `game-core` is reused unmodified — a hard rule): the rules,
turn sequence, combat, politics, tech, economy, the AI opponents, the maps, the dice/RNG, and the
victory conditions. A move that's legal on the desktop is legal here, adjudicated by the same code.
This is fidelity, not a re-balance.

**New in the web port:**

| Aspect | Desktop TripleA | Web port |
|---|---|---|
| Client | Java/Swing desktop app (installed) | **React in the browser**, over **WebSocket + JSON** |
| Networking | Custom sockets / `@RemoteActionCode` RPC | `WebPlayer` / `WebDecisionBridge` over WebSocket+JSON; engine untouched |
| Hosting | Self-hosted lobby/bots, or PBEM/PBF | **Control plane** (accounts, lobby, orchestration) + **one isolated JVM per active game** |
| Identity | Lobby accounts | **OAuth login + invite allow-list**; each game seat is **cryptographically bound** to the signed-in account |
| Game state | Local save files / PBEM mailbox | **Postgres-backed**, autosaved after every committed step; **resumes on reconnect** by re-spawning a game process from the latest save |
| Absent players | Handled manually by the host | **Host-set turn timer → automatic, reclaimable AI takeover** |
| Game end | Shown in-client | **Surfaced + recorded** (winner + reason); plus a **concede** option |

Architecture in one line: the unchanged engine runs in a **per-game process**; a **control plane**
authenticates players, runs the lobby, spawns/reaps games, and proxies each game's WebSocket so the
whole app is a single HTTPS origin.

## Repository layout

This fork keeps the entire upstream TripleA tree (engine, maps tooling, build) and adds:

- **`game-app/game-web-server/`** — the per-game container: hosts one `game-core` game and bridges it to the browser (`WebPlayer` / `WebDecisionBridge`).
- **`game-app/game-control-plane/`** — accounts, lobby, orchestration (Javalin + JDBI + Postgres + Flyway).
- **`web-client/`** — the React SPA (Vite + TypeScript).
- **`deploy/`** — deployment artifacts (Caddyfile, systemd unit, env template).
- **`docs/web-port/`** — fork docs (see below).

## Documentation

| Doc | What it covers |
|-----|----------------|
| [`docs/web-port/CHARTER.md`](docs/web-port/CHARTER.md) | Source of truth: what the fork is and the rules that govern it |
| [`docs/web-port/WHY-WEB-PORT.md`](docs/web-port/WHY-WEB-PORT.md) | Why it exists, how it differs, the improvements, what's left |
| [`docs/web-port/design.md`](docs/web-port/design.md) | Living architecture spec |
| [`docs/web-port/DEPLOY.md`](docs/web-port/DEPLOY.md) | Single-VM, Docker-free deployment runbook |
| [`tasks/todo.md`](tasks/todo.md) | Current milestones and status |

The root [`AGENTS.md`](AGENTS.md) and nested `AGENTS.md` files document the underlying engine and
Java/build conventions; they predate the fork but remain accurate for the engine.

## Relationship to upstream TripleA

This is an independent fork of [`triplea-game/triplea`](https://github.com/triplea-game/triplea),
created to explore a browser-hosted delivery of the engine. **All credit for the game engine, rules,
AI, and maps belongs to the TripleA project and its community.** This fork does not modify the engine
and is not affiliated with or endorsed by the upstream project. For the official desktop game, see
[triplea-game.org](http://triplea-game.org/download/) and the
[upstream repository](https://github.com/triplea-game/triplea).

## License

Like upstream TripleA, this project is licensed under the **GNU General Public License v3.0** — see
[`LICENSE`](LICENSE).

### Additional Permissions

Under GNU GPL version 3 section 7, we grant additional permission to convey the resulting work when
combining or linking the Program with the following libraries (or modified versions of these
libraries):

| Library | Group ID | Artifact ID | SPDX License ID |
|:--------|:---------|:------------|:----------------|
| Jakarta Mail | com.sun.mail | jakarta.mail | GPL-2.0-only |

## Acknowledgments

The [TripleA project](https://github.com/triplea-game/triplea) and its contributors and map-makers,
whose engine and content make this port possible.
