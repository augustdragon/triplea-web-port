#!/usr/bin/env bash
#
# Redeploy the TripleA web-port on this host.
#
# Pulls the latest web-port branch, rebuilds the backend (control plane + game
# container), restarts the control-plane service, and reports the deployed commit.
# The SPA is only rebuilt when web-client/ actually changed (and re-exports
# geometry.json afterward, since `vite build` empties dist/). See docs/web-port/DEPLOY.md.
#
# Run as root on the VPS:  sudo /opt/triplea-web/deploy/redeploy.sh [--with-web]
#
#   --with-web   force a web-client rebuild (+ geometry re-export) even if unchanged
#   --help       show this help
#
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/triplea-web}"
SVC="${SVC:-triplea-control-plane}"
SVC_USER="${SVC_USER:-triplea}"
BRANCH="${BRANCH:-web-port}"

# Map assets for the geometry re-export (only used on a web rebuild). Override via env for other maps.
MAP_DIR="${MAP_DIR:-$APP_DIR/maps/world_war_ii_pacific-master/map}"
GAME_XML="${GAME_XML:-$MAP_DIR/games/ww2pac40_2nd_edition.xml}"
GEOMETRY_OUT="${GEOMETRY_OUT:-$APP_DIR/web-client/dist/geometry.json}"

usage() {
  cat <<'EOF'
Redeploy the TripleA web-port: pull web-port, rebuild the backend, restart the service.

Usage: sudo /opt/triplea-web/deploy/redeploy.sh [--with-web]

  --with-web, -w   force a web-client rebuild (+ geometry re-export) even if unchanged
  --help, -h       show this help

The SPA is rebuilt automatically when web-client/ changed in the pull. See docs/web-port/DEPLOY.md.
EOF
}

FORCE_WEB=0
for arg in "$@"; do
  case "$arg" in
    --with-web|-w) FORCE_WEB=1 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

if [[ $EUID -ne 0 ]]; then
  echo "Run as root — this uses 'sudo -u $SVC_USER' for repo/build and systemctl for the service." >&2
  exit 1
fi

# All repo/git/build ops run as the service account that owns the repo (root git trips
# git's "dubious ownership" guard on a triplea-owned tree).
asuser() { sudo -u "$SVC_USER" "$@"; }
git_t()  { asuser git -C "$APP_DIR" "$@"; }

cd "$APP_DIR"

echo "==> Pulling latest origin/$BRANCH"
BEFORE="$(git_t rev-parse HEAD)"
git_t fetch --quiet origin "$BRANCH"
git_t checkout --quiet "$BRANCH"
git_t pull --quiet --ff-only origin "$BRANCH"
AFTER="$(git_t rev-parse HEAD)"

if [[ "$BEFORE" == "$AFTER" ]]; then
  echo "    Already at ${AFTER:0:9} — rebuilding anyway."
else
  echo "    ${BEFORE:0:9} -> ${AFTER:0:9}"
  # If this script itself was updated by the pull, re-run the new version once.
  if [[ "${REDEPLOY_REEXEC:-}" != "1" ]] && ! git_t diff --quiet "$BEFORE" "$AFTER" -- deploy/redeploy.sh; then
    echo "    redeploy.sh changed — re-running the updated script"
    REDEPLOY_REEXEC=1 exec "$APP_DIR/deploy/redeploy.sh" "$@"
  fi
fi

# Decide whether the SPA needs rebuilding (skip the slow path when only the backend changed).
web_changed=0
if [[ "$FORCE_WEB" -eq 1 || "$BEFORE" == "$AFTER" ]]; then
  web_changed=1
elif ! git_t diff --quiet "$BEFORE" "$AFTER" -- web-client/; then
  web_changed=1
fi

echo "==> Building backend (control plane + game container)"
asuser ./gradlew --quiet --console=plain :game-control-plane:installDist :game-web-server:installDist

if [[ "$web_changed" -eq 1 ]]; then
  echo "==> Building web client"
  # `npm ci` only when deps changed; otherwise a plain build is enough.
  if [[ "$BEFORE" == "$AFTER" ]] || ! git_t diff --quiet "$BEFORE" "$AFTER" -- web-client/package-lock.json web-client/package.json; then
    asuser npm --prefix web-client ci
  fi
  asuser npm --prefix web-client run build
  echo "==> Re-exporting geometry.json (vite build empties dist/)"
  asuser ./gradlew --quiet --console=plain :game-web-server:exportGeometry \
    --args="$MAP_DIR $GEOMETRY_OUT $GAME_XML"
else
  echo "==> web-client unchanged — skipping SPA build"
fi

echo "==> Restarting $SVC"
systemctl restart "$SVC"
# Give it a moment, then confirm it came up.
for _ in 1 2 3 4 5; do
  systemctl is-active --quiet "$SVC" && break
  sleep 1
done
if systemctl is-active --quiet "$SVC"; then
  echo "    $SVC is active."
else
  echo "    $SVC failed to start — recent logs:" >&2
  journalctl -u "$SVC" --no-pager -n 20 >&2 || true
  exit 1
fi

echo "==> Deployed:"
git_t --no-pager log --oneline -1
echo "Done."
