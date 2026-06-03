#!/usr/bin/env bash
#
# Smoke-test the control-plane lobby API (P4.3 M3a) end to end with two users.
# Assumes the control plane is ALREADY running with dev-login enabled and alice+bob
# allow-listed — see docs/web-port/control-plane-test.md for how to start it.
#
# Usage:   bash game-app/game-control-plane/scripts/lobby-smoke.sh
# Override: BASE=http://host:7000 GAME=<game-id> bash .../lobby-smoke.sh
#
set -u
BASE="${BASE:-http://localhost:7000}"
GAME="${GAME:-world-war-ii-pacific-1940-2nd-edition}"

command -v jq >/dev/null || { echo "this script needs 'jq'"; exit 1; }
curl -fsS -o /dev/null "$BASE/health" \
  || { echo "control plane not reachable at $BASE/health — start it first"; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
login() { curl -s -o /dev/null -c "$work/$1.jar" -X POST "$BASE/api/dev-login" \
            -H 'Content-Type: application/json' -d "{\"subject\":\"$1\",\"displayName\":\"$2\"}"; }
as()    { local who="$1"; shift; curl -s -b "$work/$who.jar" "$@"; }
code()  { local who="$1"; shift; curl -s -o /dev/null -w '%{http_code}' -b "$work/$who.jar" "$@"; }
seats() { jq -r '"  "+.status+" | "+([.seats[]
            | .power+":"+.kind+(if .ready then "(ready)" else "" end)
            + (if .ownerName then "="+.ownerName else "" end)]|join(", "))'; }
ready() { as "$1" -X POST "$BASE/api/games/$2/seats/$3/ready" \
            -H 'Content-Type: application/json' -d '{"ready":true}'; }

login alice Alice
login bob   Bob

echo "catalog (games you can host):"
as alice "$BASE/api/catalog" | jq -c '.[] | {id, playablePowers}'

GID="$(as alice -X POST "$BASE/api/games" -H 'Content-Type: application/json' \
        -d "{\"gameId\":\"$GAME\"}" | jq -r .id)"
echo "created table: $GID"

echo "alice claims Japanese:"; as alice -X POST "$BASE/api/games/$GID/seats/Japanese/claim" | seats
echo "bob claims Americans:";  as bob   -X POST "$BASE/api/games/$GID/seats/Americans/claim" | seats
echo "bob re-claims Japanese  (expect 409): $(code bob -X POST "$BASE/api/games/$GID/seats/Japanese/claim")"
echo "bob readies Japanese    (expect 403): $(code bob -X POST "$BASE/api/games/$GID/seats/Japanese/ready" -H 'Content-Type: application/json' -d '{"ready":true}')"

ready alice "$GID" Japanese >/dev/null
echo "both ready:"; ready bob "$GID" Americans | seats

echo "bob launches (not host) (expect 403): $(code bob -X POST "$BASE/api/games/$GID/launch")"
echo "alice (host) launches:"; as alice -X POST "$BASE/api/games/$GID/launch" | seats
echo "lobby list now (active table is gone): $(as alice "$BASE/api/games" | jq -c .)"

echo
echo "OK — lobby flow exercised. (Each run leaves a table; reset with:"
echo "  docker exec docker-web-port-db-1 psql -U triplea_web -d triplea_web -c 'TRUNCATE games, users RESTART IDENTITY CASCADE;')"
