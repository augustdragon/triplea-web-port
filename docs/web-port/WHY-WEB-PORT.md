# Why the Web Port — and how it differs from base TripleA

This document explains **why** this fork exists, **how** it differs from the desktop TripleA game,
which of those differences are genuine **improvements** (not merely changes), and **what remains** on
the roadmap. It is orientation, not governance — see [`CHARTER.md`](CHARTER.md) for the rules and
[`design.md`](design.md) for the architecture.

## The one-sentence version

We are putting the **exact** TripleA game engine behind a **browser client and a hosted, account-based
service** so a group of friends can play a full Axis & Allies game online, across many days, with no
installation — and with the operational niceties (accounts, a lobby, resumable async games, turn
timers, AI cover for absent players) that a modern board-game service provides and the desktop app
does not.

## Why we're building it

Base TripleA is a mature, faithful, and deep wargame engine — ~68K lines of rules and ~295 community
maps — wrapped in a **desktop Java/Swing application** with **custom-socket networking**. That
wrapper is the friction:

- **It must be installed.** A new player needs a Java desktop install before they can click anything.
  That alone loses most casual invitees.
- **Multiplayer is self-serve or manual.** You either run/host a lobby + bots yourself, or you play
  **by email / by forum (PBEM/PBF)**, where the *humans* manage everything: whose turn it is, chasing
  no-shows, finding substitutes, and posting save files back and forth.
- **It is desktop-bound.** No phone, no tablet, no "open a link and you're in."

A&A games run for **hours to days** with friends scattered across time zones and devices. The engine
is excellent; the *delivery and lifecycle* are where a modern service helps. So we replace **only the
transport and the client**, and we keep the engine **unmodified** — same rules, same AI, same maps,
same dice, same victory conditions. The game you play is the real TripleA game; what's new is how you
reach it and how a long, asynchronous, multi-player game is hosted and kept moving.

## What is deliberately the *same*

Because `game-core` is reused unchanged (a hard rule — see the charter), the **gameplay is identical**
to desktop TripleA:

- the rules, turn sequence, combat, politics, tech, and economy;
- the AI opponents;
- the maps and their data;
- dice/RNG and the victory conditions.

This is a feature, not a limitation: it's **fidelity**. We are not "rebalancing" or "reinventing"
A&A. A move that's legal on the desktop is legal here, adjudicated by the same code.

## How it differs (architecture)

| Aspect | Desktop TripleA | Web port |
|---|---|---|
| Client | Java/Swing desktop app (installed) | **React in the browser**, over **WebSocket + JSON** |
| Networking | Custom sockets / `@RemoteActionCode` RPC | `WebPlayer` / `WebDecisionBridge` over WebSocket+JSON; engine untouched |
| Hosting | Self-hosted lobby/bots, or PBEM/PBF | **Control plane** (accounts, lobby, orchestration) + **one container per active game** |
| Identity | Lobby accounts (for the lobby) | **OAuth login + invite allow-list**; the game seat is **cryptographically bound** to the signed-in account (connect-tickets) |
| Game state | Local save files; PBEM mailbox | **Postgres-backed**, autosaved after every committed step; **resumes on reconnect** from the latest save, lazily re-spawning a container |
| Absent players | Handled **manually** by the host (sub or game dies) | **Host-set turn timer → automatic, reclaimable AI takeover** |
| Game end | Shown in-client | **Surfaced + recorded** (winner + reason); plus a **concede** option |

Architecture in one line: the unchanged engine runs inside a **per-game container**; a **control
plane** owns auth, the lobby, the database, and spawning/reaping containers; the **browser** talks to
the lobby (REST/WS) and to its game's container (WS). Details in [`design.md`](design.md) and
[`PHASE-4-MULTIPLAYER.md`](PHASE-4-MULTIPLAYER.md).

## Genuine improvements over the base game

These are places where the web port does something **better**, not just differently. (Where it only
differs, or is currently *behind*, see "Current limitations" below — we're keeping ourselves honest.)

1. **Zero-install, any-device access.** Open a link in a browser on a laptop, desktop, or tablet —
   no Java, no install. This is the single biggest practical win for getting friends to actually play.

2. **A hosted, account-based lobby.** Invited players log in with Google/Discord (allow-list), form
   tables, claim seats, ready-up, and launch — and the service spawns and reaps the per-game container
   automatically. No one has to run a server or babysit a bot.

3. **Automatic, *reclaimable* cover for absent players.** The host sets a per-turn timer at table
   creation (real-time minutes or correspondence days). If a seat's turn isn't taken in time, AI takes
   it over so the game keeps moving — and the original player can **reclaim** their seat from AI when
   they return. Base TripleA PBF/PBEM has **no** turn timer and **no** automated takeover; a no-show is
   resolved by hand or the game stalls. This is the feature most aligned with how long async A&A games
   actually fail, and it directly fixes it. (Timer presets and the AI-takeover model were chosen after
   surveying BoardGameArena, Chess.com, Lichess, and OGS.)

4. **Durable, async-by-design persistence.** Every committed step autosaves to the database; a
   reconnecting player (even days later, on a different device) is rehydrated from the latest save, and
   an idle game's container is reaped and re-spawned on demand. The desktop app has save files and
   PBEM, but not a managed, always-resumable, multi-device hosted state.

5. **Authenticated seat ↔ identity.** A seat is bound to the signed-in account via a short-lived,
   single-use signed ticket — you are routed straight into *your* seat, and a connection can't answer
   for a seat it doesn't own. This closes the desktop/PBF "trust the name people type" gap.

6. **Clear, recorded end-of-game in the hosted flow.** When a game ends, the winner and the *reason*
   (victory / round cap / concede / error) are surfaced to every player and recorded — and players can
   **concede** gracefully rather than abandoning. In a hosted, async setting this matters more than on
   a single desktop where everyone is watching the same screen.

> Honest framing: improvements 1–6 are about **access, hosting, identity, and game lifecycle** — not
> about changing the game. The rules and AI are the engine's, unchanged.

## Current limitations (where we are *behind* or not yet at parity)

The web port is an in-progress fork; today it does **not** match every desktop capability:

- **One map wired end-to-end.** Only **World War II Pacific 1940 (2nd edition)** is fully playable;
  the map converter needs to run across more maps and some rendering features (relief blending,
  scroll-wrapping, markers) are not done.
- **Some decisions use safe defaults.** A few less-common prompts (e.g. certain naval/air queries,
  the tech panel) aren't fully interactive yet and fall back to engine defaults.
- **No in-game chat or history/replay panel yet.** The engine records a full textual history; the web
  client doesn't surface it as a live action log or a replay yet (roadmap).
- **Single, generous timer model.** Turn timers are per-turn presets only; reserve time-banks and
  vacation/quiet-hours (common on other services) are roadmap.
- **Not yet deployed as a public service.** It runs locally / on a dev box; VM deployment, presence,
  and "your turn" push notifications are pending.

## Roadmap — what remains

Tracked in detail in [`../../tasks/todo.md`](../../tasks/todo.md). Summary:

**Phase 4 — Multiplayer hosting (in progress; most of it shipped)**
- ✅ Done: control plane + OAuth lobby + per-game orchestration; lobby↔container **authenticated seat
  handoff**; **end-game/victory surfacing**; **concede**; **host-set turn timers + reclaimable AI
  takeover**.
- ⏳ Remaining: **deployment** (Docker Compose on a VM — control plane + Postgres + on-demand game
  containers); **presence** + **"your turn" Web Push** (the responsiveness mechanism for multi-day
  turns); a **WebSocket heartbeat / readiness** hardening; a **proactive deadline sweep** that wakes
  idle overdue games to advance them.
- 🎯 Exit check: a private group plays a **full** Pacific 1940 game over the internet, browser-only,
  resumable across days, with an abandoned seat caretaken by AI, **ending with a winner announced**.

**Phase 5 — Breadth & durability**
- Run the **map converter across more maps** and close rendering gaps (relief blending, scroll-wrap,
  markers) so a second, structurally different map plays end-to-end.
- **Tech panel** + generalized politics beyond Pacific's free declarations.
- **Action log / game history visible to all players + replay** (the engine already records and saves
  it — mostly a surfacing task).
- **Post-game review session** (stay in a read-only game to review/discuss; host closes → archive).
- **Saved game history & export** (premium — storage-heavy).
- **Admin console** for the service owner (view/manage all games, players, and settings).
- **Richer timers**: reserve time-bank (Fischer/BGA-style) and vacation / quiet-hours.
- **Bidding** as a game-setup option (competitive A&A balancing).

**Deferred until the service opens to the public**
- Moderation / ban / audit tooling (schema known); R2/S3 save store (the storage seam is already in
  place).

## Where to read more

- [`CHARTER.md`](CHARTER.md) — what governs the fork (source of truth).
- [`design.md`](design.md) — the living architecture/spec.
- [`PHASE-4-MULTIPLAYER.md`](PHASE-4-MULTIPLAYER.md) — the auth/lobby/orchestrator build-out and the
  reuse audit against the engine and upstream lobby.
- [`../../tasks/todo.md`](../../tasks/todo.md) — current milestones and status.
