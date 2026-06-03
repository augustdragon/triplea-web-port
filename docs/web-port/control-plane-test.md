# Control Plane — Manual Test (P4.3 M1–M3a)

How to run and exercise the **control plane** yourself. It has **no browser UI yet** (that's M3b),
so everything here is over HTTP — `curl` + the provided smoke script. Covers auth (M2) and the
lobby (M3a).

> Companion to the browser game-client checklist in `MANUAL-TEST-SCRIPT.md`. The control plane is a
> separate service (`:game-control-plane`) from the game server (`:game-web-server`).

## 1. Start it (two pieces)

**Postgres (dev DB):**
```
docker compose -f .docker/web-port-db.yml up -d
```

**Control plane** — export config (env only; nothing secret is baked in), then run. Point
`CONTROL_PLANE_GAME_XML` at *your* copy of the canonical map (filename
`ww2pac40_2nd_edition.xml`; on this dev box it's under
`~/triplea-webport-work/world_war_ii_pacific-master/map/games/`):
```
source "$HOME/.sdkman/bin/sdkman-init.sh"
export CONTROL_PLANE_DB_PASSWORD=triplea_web
export CONTROL_PLANE_JWT_SECRET=$(head -c 32 /dev/urandom | base64)   # any >= 32 chars
export CONTROL_PLANE_DEV_LOGIN=true                                   # enables /api/dev-login (dev only)
export CONTROL_PLANE_ALLOWLIST="google:alice,google:bob"              # invited test identities
export CONTROL_PLANE_GAME_XML="/path/to/ww2pac40_2nd_edition.xml"
./gradlew :game-control-plane:run
```
**Expect** in the log: `Available game '...': 5 playable powers [Japanese, Americans, Chinese,
British, ANZAC]` and `Control plane listening on :7000`. Health check: `curl localhost:7000/health`
→ `{"status":"ok"}`.

## 2. Run the lobby smoke test

With the control plane running:
```
bash game-app/game-control-plane/scripts/lobby-smoke.sh
```
It logs in two users (alice, bob), lists the catalog, creates a table, claims/readies seats, and
host-launches — printing each step. **Expect**: claims show `human=Alice`/`=Bob`, a re-claim of a
taken seat is **409**, readying/launching someone else's seat or table is **403**, the host launch
flips the table to `active`, and the active table drops out of the lobby list.

## 3. Poke it by hand (optional)

Auth is a session cookie, so use a cookie jar:
```
B=http://localhost:7000
curl -s -c alice.jar -X POST $B/api/dev-login -H 'Content-Type: application/json' \
  -d '{"subject":"alice","displayName":"Alice"}'        # sets the session cookie
curl -s -b alice.jar $B/api/me            | jq           # who am I
curl -s -b alice.jar $B/api/catalog       | jq           # hostable games + powers
curl -s -b alice.jar -X POST $B/api/games -H 'Content-Type: application/json' \
  -d '{"gameId":"world-war-ii-pacific-1940-2nd-edition"}' | jq    # create a table
curl -s -b alice.jar $B/api/games         | jq           # list lobby tables
```
Auth checks worth trying: `GET /api/me` with **no** `-b alice.jar` → **401**; dev-login with a
subject **not** in the allow-list → **403**.

## 4. Watch the live lobby WebSocket (optional, needs Node)

`/ws/lobby` pushes the table list on connect and after every change. Quick check:
```
npm --prefix /tmp install ws >/dev/null
TOKEN=$(grep cp_session alice.jar | awk '{print $7}')
node -e '
  const WS=require("/tmp/node_modules/ws");
  const ws=new WS("ws://localhost:7000/ws/lobby",{headers:{Cookie:"cp_session="+process.argv[1]}});
  ws.on("message",d=>console.log("update:",JSON.parse(d).tables.length,"table(s)"));
' "$TOKEN"
```
Leave it running and create/claim a table in another terminal → you'll see `update:` lines. A
connection with **no cookie** is closed immediately with no data.

## 5. Review the code

The M3a server lives in `game-app/game-control-plane/src/main/java/org/triplea/web/controlplane/`:
- `lobby/LobbyController.java` — the REST endpoints + host/ready-up gate.
- `lobby/LobbyDao.java` — the SQL (seat rules enforced in the WHERE clause).
- `lobby/LobbyBroadcaster.java` — the `/ws/lobby` push.
- `auth/` — JWT cookie + allow-list + `AuthFilter` (M2).
- `game/GameXmlReader.java` — playable-power enumeration via the engine.

## Reset / stop

- Reset data between runs: `docker exec docker-web-port-db-1 psql -U triplea_web -d triplea_web -c
  'TRUNCATE games, users RESTART IDENTITY CASCADE;'`
- Stop: `Ctrl+C` the control plane; `docker compose -f .docker/web-port-db.yml stop web-port-db`.
