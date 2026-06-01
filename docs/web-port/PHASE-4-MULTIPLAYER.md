# Phase 4 — Multiplayer Hosting (Design Spec)

**Status:** Design / pre-implementation. No code written yet.
**Date:** 2026-05-31
**Supersedes:** the "LAN/ZeroTier multiplayer" placeholder in `tasks/todo.md`.

---

## 1. Vision & framing

We are replacing TripleA's legacy networking model with a **true server**.

In legacy TripleA, a multiplayer game is **hosted on a player's own machine** (or on a
player-run "headless bot"). Everyone installs a desktop client, the host opens a port (or
relays through a fallback server), and the lobby is just a directory that points peers at
each other's IP addresses. The compute that runs the game engine lives on a participant's PC.

The web port inverts this:

- **Zero install** — players join from a browser. No desktop client, no Java on the player's machine.
- **Server-authoritative** — the engine runs on *our* server, not a participant's PC.
- **Modernize the plumbing, preserve the logic** — we keep `game-core` (20+ years of correct,
  battle-tested rules) **completely unmodified** and replace everything around it: Swing UI,
  the custom P2P/NIO socket protocol, the desktop lobby client, and the headless-bot host.
  All of that becomes **browser + WebSocket + JSON + a control plane**.

**Target (this phase):** internet-hosted for a private group (no public signup), but
engineered cleanly so it can become a SaaS supporting dozens of users and multiple
concurrent games without a rewrite.

### Hard constraints (carried from earlier phases)

- **`game-core` stays unmodified.** Preserves save-game compatibility and `@RemoteActionCode`
  wire contracts. If a change seems to require editing the engine, **stop and re-plan.**
- **Canonical game:** `ww2pac40_2nd_edition.xml` (see `canonical-pacific-game-2nd-edition` memory).

### Cost note (conscious tradeoff)

Eliminating the player-hosted bot means **we** become the compute host for every game.
Each active game is a JVM holding a full `GameData` (a Pacific game is a few hundred MB
resident). "Dozens of users / multiple games" is fine on a single VM; the cost that used to
be externalized onto players' PCs now lands on our infra. **On-demand spawn + idle-reap**
(P4.4/P4.5) is what keeps it bounded.

---

## 2. The core technical decision: one process per game (forced, not chosen)

We evaluated **container-per-game** vs **shared multi-game JVM**. The engine settles it.

`game-core` carries **process-global static state** that two concurrent games in one JVM
would corrupt — and we cannot fix it, because the engine stays unmodified:

| Static state | Location | Effect if two games share a JVM |
|---|---|---|
| `GameData.current` (settable singleton) | `GameData.java:122` | New game overwrites the "current" pointer; AI/delegate code reading it sees the wrong board |
| `GameState.started` | `GameState.java:7` | Latches `true` forever; second game's state checks break |
| `RemoteRandom.verifiedRandomNumbers` (global list) | `RemoteRandom.java:13` | Dice audit trails from all games intermix |
| `ClientSetting.*` (hundreds of static settings) | `ClientSetting.java:53+` | Timeouts/config shared across all games |

TripleA's own `HeadlessGameServer` hosts **one game per instance** for exactly this reason
(`HeadlessGameServer.java:29,60`).

**Decision: one JVM (one container) per active game.** Each game already gets its own
`ServerGame` + game-loop thread + `DelegateExecutionManager` (`ServerGame.java:78`,
`ServerLauncher.java:196`), so per-process isolation is clean and needs no engine changes.

**Enabling fact — save/load is fully public:** `GameData.toBytes()` (`GameData.java:154`)
and `GameDataManager.loadGame(InputStream)` (`GameDataManager.java:64`) — GZIP'd Java
serialization, lock-protected mid-game save (`ServerGame.saveGame`, `GameDataWriter`).
This is the foundation for durable, resumable persistence with **no engine changes**.

---

## 3. Architecture

```
                    ┌─────────────────────────────────────────┐
   Browser (React)  │              CONTROL PLANE (Java)        │
   ┌───────────┐    │  ┌────────────────────────────────────┐ │
   │  Lobby UI │◄───┼──┤  HTTP API + Lobby WebSocket         │ │
   │  Game UI  │    │  │  • Social OAuth (Google/Discord)    │ │
   └─────┬─────┘    │  │  • httpOnly session cookies         │ │
         │          │  │  • game registry / matchmaking      │ │
         │          │  │  • presence + "your turn" push      │ │
         │          │  │  • spawns + reaps game containers   │ │
         │          │  └──────────┬──────────────┬───────────┘ │
         │ game WS  │             │              │             │
         │ (routed  │      ┌──────▼─────┐  ┌─────▼──────┐      │
         └──────────┼─────►│ Postgres   │  │ Save store │      │
         to a       │      │ users,     │  │ (volume    │      │
         specific   │      │ games,seats│  │  now → R2) │      │
         game proc) │      │ bans, log  │  └────────────┘      │
                    │      └────────────┘                      │
                    │   ┌───────────┐  ┌───────────┐           │
                    │   │ Game JVM  │  │ Game JVM  │  …one per  │
                    │   │ (game A)  │  │ (game B)  │  active    │
                    │   │ :WS port  │  │ :WS port  │  game      │
                    │   └───────────┘  └───────────┘           │
                    └─────────────────────────────────────────┘
```

**Control plane (Java, recommended).** Keep it in Java so it can call `GameDataManager`
directly to inspect/restore save bytes — a Node service couldn't touch the engine's save
format. It owns: OAuth, sessions, the lobby, the user/game database, presence, notifications,
and orchestration (spawn a game JVM when a match launches, reap it on game-end or idle,
restore from a save to resume).

**Game JVMs.** Each is essentially today's `WebPlayableServer`, but (a) parameterized with a
game-id + optional save path instead of a hardcoded XML, (b) reporting lifecycle/turn events
back to the control plane, and (c) extended from single-seat to **multi-seat routing** — the
one real gap in the current code.

---

## 4. Reuse audit (P4.0 findings)

We have a meaningful head start in-repo, but it is **conventions + DTO shapes + domain
knowledge**, not a drop-in server.

### Present in THIS repo (harvest directly)

- **Lobby client DTOs** — `http-clients/lobby-client-data/` defines the *shapes* of users,
  games, bans, mutes, moderator audit events, access logs, and player summaries as the client
  sees them. These encode 20 years of domain decisions.
- **DB tooling + scaffolding** — `.docker/` has `docker-compose.yml`, `docker-flyway.config`,
  and `database/01-init.sql` (creates an empty `lobby_db`/`lobby_user`). Migration convention
  is **Flyway** with semver naming `V<compat>.<feature>.<patch>__desc.sql`
  (`docs/development/db-migration-file-versioning.md`).
- **Auth logic doc** — `docs/development/lobby-authentication-logic.md` (legacy: client SHA-256
  → server bcrypt → `api_key` + `player_chat_id`).
- **Relay server module** — `game-app/game-relay-server/` (legacy NAT fallback; likely
  obsolete for us since games are server-hosted, but worth understanding).

### NOT in this repo (upstream `spitfire-server`) — and now audited

- The actual lobby **server implementation** and its `V*.sql` **schema migrations** are not
  here (zero migration files locally). I read the real upstream schema at the last commit
  before it was **deleted on 2024-06-11** (commit `b824876f8c`, "Remove lobby/infrastructure/
  nodebb components"). Findings:
  - **Their game model does not fit us.** `lobby_game` is an ephemeral peer *advertisement*
    (`host_name`, opaque `game_id`, keep-alive key) — **no game state, no seats, no saves, no
    resume.** All game-state tables are therefore **greenfield** for us; nothing upstream helps.
  - **~half the ~19 tables are baggage:** `lobby_api_key`, `game_hosting_api_key`,
    `temp_password_request(_history)`, `lobby_game`, `game_chat_history`, bcrypt/MD5 password
    columns — all P2P-host or password-auth artifacts. Dropped.
  - **Reusable cluster (deferred transplant, not v1):** moderation/audit — `banned_user`
    (bans by IP `inet` + `system_id` device id + expiry + public dispute id, indexed
    `(ip, system_id)`), `banned_username`, `bad_word`, `moderator_action_history`,
    `access_log`. Plus `error_report_history` (in-DB rate-limit/dedup) and an optional map
    catalog (`map_index` + `tag_type` + `map_tag_values`).
  - **Decision:** author our **own** Flyway history (fresh `V1.x` baseline); greenfield
    game-state; hand-copy the moderation DDL only **when we open up** (§5, §10.4).

### Hard-won domain knowledge worth keeping (from the DTOs)

1. **Multi-vector bans** — username + IP + `hashedMac`. They learned single-vector bans are
   trivially evaded.
2. **Server-assigned `systemId` / device id** — tracks per-machine logins to catch IP-cycling
   evaders.
3. **`player_chat_id`** — a *public, server-assigned* identifier (not a secret) that lets you
   link aliases historically without leaking anything.
4. **Immutable access log + moderator audit log** — forensic reconstruction of abuse networks.
5. **Mute targets `player_chat_id`, not username** — survives renames/relogins.
6. **`passwordChangeRequired` / forced reset** — admins issue temp credentials and force a change.

> Modernization line: we reuse the *schema decisions and abuse-case knowledge*, **not** the
> legacy transport, Swing lobby client, or password auth. Auth moves to OAuth (§5).

---

## 5. Auth & user model

**Mechanism:** Social OAuth (Google / Discord) → httpOnly, Secure, SameSite session cookie.
No passwords, no reset flows, no email plumbing to build or secure. Good fit for a gaming
audience and for a private group (allow-list the invited accounts).

**Standards applied** (from global CLAUDE.md): tokens in **httpOnly cookies, never
localStorage**; session expiration; validate all env/config at startup and fail fast if an
OAuth client secret is missing; secrets in env vars only.

**User model (greenfield, informed by §4):**

```
users(
  id, oauth_provider, oauth_subject,     -- identity from Google/Discord
  display_name, player_chat_id,          -- player_chat_id: public, server-assigned
  role,                                  -- player | moderator | admin
  created_at, last_login_at
)
```

**Access control for v1 = the allow-list itself.** Only invited OAuth identities may log in.
For a private group of friends there is no one to moderate, so the **entire ban/mute/audit
apparatus is deferred** — it is *not* v1 work. We keep the upstream moderation schema as a
**documented future transplant** (see §4 / §10.4) so opening the platform up later is a
known, low-risk addition, not a redesign. Build it when we add public signup, not before.

---

## 6. Game lifecycle, persistence & resume

Players come and go across **hours to days**. Async-resumable, DB-backed is the model.

### 6.1 Persistence — DB is the source of truth (Lichess pattern)

The live game JVM is a **cache**; the durable record is in the save store + Postgres.

- **Flush on every committed turn, synchronously, in a transaction.** Lichess flushes
  periodically (a correctness compromise it accepts because of blitz write-rates). Our write
  rate is tiny (a turn every many minutes), so we can afford to flush on every committed
  turn and **never lose a committed move to a crash**. `GameData.toBytes()` → save store;
  metadata row → Postgres.
- **Lazy rehydration.** Don't hold every game in memory. Spin up a game JVM when the first
  player (re)connects; load the latest save via `GameDataManager.loadGame(InputStream)` and
  resume. Reap idle game JVMs; their state is safely on disk.

```
games(id, map_xml, edition, status[lobby|active|paused|finished],
      container_id, ws_endpoint, current_save_id, turn, round,
      created_by, created_at, updated_at)
seats(game_id, power_name, user_id NULL, kind[human|ai|open],
      connected_bool, turn_deadline_at NULL)
saves(id, game_id, turn, round, bytes_ref, created_at)
sessions(token_hash, user_id, expires_at)   -- or signed cookie, no table
```

### 6.2 Reconnection & catch-up — version every event (Lichess pattern, copy directly)

This is the single most directly reusable pattern from the research.

- Every game event carries a **monotonic version `v`**. The game JVM keeps a bounded
  in-memory buffer of recent events.
- On (re)connect the client sends its **`lastSeenVersion`**; the server **replays the tail**
  from `v+1`. The client never misses or double-applies an event.
- Use an explicit **ack counter** so a client can idempotently resend an unacked move.
- If the client is too far behind (e.g. the JVM restarted and the buffer is empty), fall back
  to a **fresh snapshot from the DB**, then resume version-based catch-up.
- **Spectators** join the same versioned stream from the current version.

> Replaces today's crude `onOpen` "replay the last snapshot to anyone" in
> `GameWebSocketServer`. Assume the transport is lossy and rely on version reconciliation for
> correctness, not on delivery guarantees.

### 6.3 Turn clock — days-per-move (Lichess correspondence model)

Default to a **fixed days-per-move budget** (e.g. 1–14 days), reset each turn, applied to
**whoever's turn it is**. Idle players feel no clock pressure — correct for multi-day play.
Deliberately **no Fischer increment, no time-bank** — Lichess rejected those for async as
needless complexity, and they're right.

### 6.4 Abandonment — the N-player problem is OURS to solve

Lichess only solves the **2-player** case (timeout → game over). For a **4–6 player** game,
"one player vanishes for three days" can't end the game without punishing the other 3–5.

**Decision required (see §10):** when a seat blows its turn deadline, do we
**(a) eliminate the seat**, **(b) hand it to AI** (engine already has AI players —
`aiType.newPlayerWithName`), or **(c) pause the whole game**?

**Recommendation:** **AI takeover / auto-skip for the timed-out seat**, so the rest of the
table isn't held hostage; reserve "game over" for the degenerate 2-player case. Layer
Lichess's **escalating play-bans** as the anti-griefer deterrent for repeat abandoners.
(Borrow the "divide the reconnect window when the leaver is losing" trick only if we ever add
a real-time mode.)

### 6.5 Crash recovery

Because DB = source of truth and we flush on every committed turn (§6.1): after a crash,
the next player to touch the game triggers **lazy rehydration** from the latest save. At most
we lose *in-progress, uncommitted* UI actions, never a committed turn.

---

## 7. Multi-seat routing (highest-risk new work)

Today `WebDecisionBridge.onClientMessage` routes replies by request UUID with **no player
filtering** — *"ANY client reply completes ANY pending request."* Fine for one human + AIs;
unacceptable for 2–6 humans sharing one game.

Required: **bind each WebSocket connection to a seat** (power), tag every decision request and
reply with the seat, and **reject cross-seat replies** server-side. Never trust the client's
claimed seat — derive it from the authenticated session + the `seats` table. This is the one
piece that touches the sensitive engine-adjacent decision path, so it's sequenced **first**
(P4.1) to de-risk early.

---

## 8. Lobby, matchmaking & presence

**Lobby (Lichess two-lane model):**
- **Public table list** — open seats; players accept into a specific power. Analogous to a
  "seek," but ours needs **N acceptances** (all seats filled), not 2.
- **Private invite links** — direct-challenge a specific user; fits the "private group" target.
- **Ready-up gate** — all seats filled + all ready → host launches → control plane spawns the
  game JVM.
- **Rematch** — clone table config under a new game id.

**Presence & notifications:**
- Track connected/away at the WS tier and **feed that signal into the game JVM** (Lichess does
  this — it powers abandonment logic).
- **"Your turn" push is a first-class feature, not an afterthought.** Multi-day turns mean a
  stalled seat blocks 3–5 people; out-of-site notification matters far more than it does for
  chess. Plan **Web Push** (browser) + escalating reminders before the deadline fires. This is
  precisely the gap Lichess never closed — we can't afford to leave it open.

---

## 9. Tech-stack decisions

| Decision | Recommendation | Rationale / alternative |
|---|---|---|
| Control-plane language | **Java** | Direct engine save-API access; one fewer language. Alt: Node/TS matches the frontend but can't read engine saves. Strong lean to Java. |
| Database | **Postgres** | Already the lobby DB; `.docker/` scaffolding + Flyway convention in-repo. |
| Migrations | **Flyway**, `V<compat>.<feature>.<patch>__desc.sql` | Matches existing convention; pure SQL, no ORM magic. |
| Game-instance isolation | **Docker container per game** | Forced by §2; control plane spawns/reaps via Docker API. |
| Hosting (this phase) | **Docker Compose on the existing Linux VM** | Lowest cost for a private group; scales to dozens before needing k8s. Azure swap is clean later. |
| Save store | **Docker volume now → R2/S3 later** | Standards say "upload to object storage, never the app server." Pragmatic on one VM now; R2 swap is a clean seam. (Decision in §10.) |

---

## 10. Open decisions (need your call)

1. ~~**Abandonment policy**~~ — **DECIDED: AI takeover** of a timed-out seat (§6.4). The
   engine's AI caretakes the abandoned power; escalating play-bans deter repeat abandoners.
2. ~~**Save store**~~ — **DECIDED: Docker volume now**, R2/S3 swap later via the storage seam.
3. ~~**Control-plane language**~~ — **DECIDED: Java** (only Java can read engine save bytes).
4. ~~**Pull upstream `spitfire-server` SQL?**~~ — **DECIDED: neither — greenfield, our own
   Flyway history.** Their game model is an ephemeral P2P *advertisement* (no state/seats/
   saves/resume) and the whole lobby module was deleted upstream mid-2024, so we adopt none of
   it wholesale. We **hand-transplant the moderation/audit DDL** (`banned_user`,
   `banned_username`, `bad_word`, `moderator_action_history`, `access_log`) as a **deferred,
   documented future addition** — *not v1*, since a private allow-list has no one to moderate.
   `lobby_user`/`user_role` inform our user skeleton only (identity + role, zero credentials).

---

## 11. Build-out phases (each shippable)

- **P4.0 — Recon** ✅ *(this document)* — process model settled; reuse audit done; patterns
  captured from Lichess/BGA.
- **P4.1 — Multi-seat single game** — extend the playable server so 2+ humans share one game,
  seat-routed and seat-validated (§7). Proves the hardest, riskiest part first.
- **P4.2 — Persistence & resume** — versioned events + `lastSeenVersion` catch-up (§6.2);
  flush-on-turn-commit (§6.1); resume a game from a save. Async play becomes real.
- **P4.3 — Auth + lobby** — Google/Discord OAuth, httpOnly sessions, lobby UI, create/join/
  list, ready-up.
- **P4.4 — Orchestrator** — control plane spawns/reaps per-game containers via Docker API;
  routes the browser to the right game's WS; lazy rehydration on reconnect.
- **P4.5 — Hosting** — Docker Compose on the VM (control plane + Postgres + on-demand game
  containers); presence + "your turn" Web Push.

---

## Appendix — primary sources

**Engine (in-repo, verified):** `GameData.java:122,154`, `GameState.java:7`,
`RemoteRandom.java:13`, `ClientSetting.java:53+`, `GameDataManager.java:64`,
`ServerGame.java:78`, `ServerLauncher.java:196`, `HeadlessGameServer.java:29,60`,
`WebDecisionBridge` (no seat filtering), `GameWebSocketServer` (`onOpen` snapshot replay).

**Lichess (lila) — patterns for §6/§8:**
- Move/version/reconnect mechanics — https://www.davidreis.me/2024/what-happens-when-you-make-a-move-in-lichess
- Active game round system (RoundAsyncActor, GameProxy, lazy reload) — https://deepwiki.com/lichess-org/lila/4.2-active-game-round-system
- Game persistence & export — https://deepwiki.com/lichess-org/lila/4.5-game-persistence-and-export
- Correspondence time controls — https://lichess.org/faq
- Claim-victory / abandonment — https://lichess.org/qa/2964/claiming-a-victory-when-my-opponent-disconnects

> Confidence note: Lichess's *exact* real-time claim-victory thresholds (≈10s leave;
> ≈40/80/120s disconnect window scaling with time control; ÷3 when losing) are approximate —
> the *shape* is verified, the precise seconds are not. The versioning, persistence, and
> crash-recovery patterns (§6.2, §6.5) are source-derived and safe to design against. Board
> Game Arena was consulted for lobby/UX only; its stateless-PHP compute model does **not**
> apply to our stateful-process-per-game engine.
