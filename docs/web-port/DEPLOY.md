# Deploying the web port (single VM, Docker-free)

This is the runbook for hosting the web port on **one Linux VM** (Ubuntu 24.04+ LTS, x86_64 — e.g. a
Hostinger **KVM2** (2 vCPU / 8 GB / 100 GB NVMe) — or any other VPS, ARM or x86; the build is arch-independent).
It uses the **process launcher** (one child JVM per game,
no Docker) and a **single HTTPS origin**: Caddy serves the SPA and reverse-proxies the API and both
WebSockets to the control plane; the control plane proxies each per-game WebSocket to that game's
internal localhost port, so **no game port faces the internet**.

> v1 login is **dev-login** over HTTPS for a trusted, invited group (the allow-list). Real
> Google/Discord OAuth (prod profile + Secure cookies) is a follow-up — the seam is ready.

Artifacts referenced below live in [`../../deploy/`](../../deploy/): `Caddyfile`,
`triplea-control-plane.service`, `control-plane.env.example`.

## 0. Prerequisites (provisioning)

- A VM with a **public IPv4** (Hostinger KVM plans include a dedicated IPv4). The Ubuntu image does
  **not** pre-load a firewall (ufw is installed but inactive), so 80/443 are reachable out of the box.
  To lock it down, open inbound **22/80/443** (TCP) in Hostinger's hPanel firewall (or `ufw allow`),
  and deny the rest — no host-side `iptables` dance needed.
- A **domain/subdomain** with an **A record → the VM's public IP** (DNS-only / grey-cloud if behind
  Cloudflare, so Caddy's cert challenge and WebSockets work).
- Root/SSH access (Hostinger provides root via SSH key or password in hPanel at create time).

## 1. Install software

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk postgresql git unzip
# Node 22 (for the SPA build)
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install -y nodejs
# Caddy (official apt repo)
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install -y caddy
java -version   # confirm 21
```

## 2. Get the code and the map

```bash
# Service account — NO -m: let git create /opt/triplea-web (a pre-created home breaks the clone).
sudo useradd -r -d /opt/triplea-web -s /usr/sbin/nologin triplea || true

# Clone the fork and switch to the web-port branch (the default branch is `main`, which does NOT
# contain the web-port code). Public repo → plain HTTPS, no credentials needed.
sudo git clone https://github.com/augustdragon/triplea-web-port.git /opt/triplea-web
sudo git -C /opt/triplea-web checkout web-port

# Fetch the map (not vendored — map art is gitignored, matching the desktop). The games XML is what
# CONTROL_PLANE_GAME_XML points at: /opt/triplea-web/maps/world_war_ii_pacific-master/map/games/…
sudo mkdir -p /opt/triplea-web/maps
curl -fsSL https://github.com/triplea-maps/world_war_ii_pacific/archive/refs/heads/master.zip -o /tmp/wwiipac.zip
sudo unzip -q /tmp/wwiipac.zip -d /opt/triplea-web/maps    # → maps/world_war_ii_pacific-master/

sudo chown -R triplea:triplea /opt/triplea-web
```

Notes:
- For a build *byte-identical* to one you tested locally, `scp`/`rsync` your local map folder to
  `/opt/triplea-web/maps/` instead of the `curl` above (the upstream `master` can drift).
- The geometry export the client needs is regenerated/served separately (see the unit-icon roadmap
  item). For now the client renders unit counts, not icons, so only the game XML is required to *play*.
- `unzip` may need installing: `sudo apt install -y unzip`.

## 3. Build (on the box — JVM dists are arch-independent)

```bash
cd /opt/triplea-web
sudo -u triplea ./gradlew :game-control-plane:installDist :game-web-server:installDist
sudo -u triplea npm --prefix web-client ci
sudo -u triplea npm --prefix web-client run build      # → web-client/dist
# Copy the geometry export the client expects into the SPA root (see the geometry.json memory note):
#   web-client/dist/geometry.json
```

## 4. Postgres

```bash
sudo -u postgres psql <<'SQL'
CREATE USER triplea_web WITH PASSWORD 'CHANGE_ME_db_password';
CREATE DATABASE triplea_web OWNER triplea_web;
SQL
```

The control plane runs its Flyway migrations automatically on first boot.

## 5. Configure

```bash
sudo mkdir -p /etc/triplea-web
sudo cp /opt/triplea-web/deploy/control-plane.env.example /etc/triplea-web/control-plane.env
sudo chown triplea:triplea /etc/triplea-web/control-plane.env
sudo chmod 600 /etc/triplea-web/control-plane.env
sudoedit /etc/triplea-web/control-plane.env   # set DB password, JWT secret, allow-list, game token, GAME_XML, PUBLIC_URL
```

Generate secrets with `openssl rand -base64 48`. The allow-list is your invited players
(`google:alice,google:bob,…`); with dev-login each picks their listed name to log in.

## 6. systemd service (the control plane + its child game JVMs)

```bash
sudo cp /opt/triplea-web/deploy/triplea-control-plane.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now triplea-control-plane
journalctl -u triplea-control-plane -f      # watch it boot + migrate; ^C when healthy
curl -s localhost:7000/health               # → 200
```

## 7. Caddy (TLS + single origin)

```bash
sudo tee /etc/caddy/Caddyfile >/dev/null <<EOF
{\$DOMAIN} {
	encode gzip
	@backend path /api/* /ws/lobby /game/*/ws
	reverse_proxy @backend localhost:7000
	root * /opt/triplea-web/web-client/dist
	try_files {path} /index.html
	file_server
}
EOF
# Provide DOMAIN + SPA root to Caddy:
sudo mkdir -p /etc/systemd/system/caddy.service.d
printf '[Service]\nEnvironment=DOMAIN=triplea.example.com\n' | sudo tee /etc/systemd/system/caddy.service.d/override.conf
sudo systemctl daemon-reload && sudo systemctl restart caddy
```

(Or hardcode the domain + `root` directly in the `Caddyfile` instead of env vars — see
[`../../deploy/Caddyfile`](../../deploy/Caddyfile).)

## 8. Verify

- Browse to `https://your-domain` → the lobby login loads (valid cert, HTTPS).
- Log in (dev-login) as an allow-listed name → create a table → claim a seat → launch → play a turn.
- On the box: `pgrep -af WebPlayableServer` shows a child JVM **per active game** (with `-Xmx512m`);
  `docker ps` shows nothing (Docker-free). Idle games are reaped (the child JVM disappears) and
  respawn on the next connect.

## 9. Updating

```bash
cd /opt/triplea-web
sudo -u triplea git pull
sudo -u triplea ./gradlew :game-control-plane:installDist :game-web-server:installDist
sudo -u triplea npm --prefix web-client run build
sudo systemctl restart triplea-control-plane
# Caddy only needs a restart if the Caddyfile changed.
```

## Notes & next steps

- **Resource cap:** `CONTROL_PLANE_GAME_HEAP=-Xmx512m` bounds each game JVM. Total RAM ≈ baseline
  (~1–1.5 GB) + `Xmx` × concurrently-active games. Games are reaped when idle and when no player is
  connected, so you only pay for games in active play.
- **Logs:** the control plane and its child game JVMs log to `journalctl -u triplea-control-plane`.
- **Real OAuth (next task):** register Google/Discord apps, set the client id/secret + callback URL,
  switch `CONTROL_PLANE_PROFILE=prod` and `CONTROL_PLANE_DEV_LOGIN=false` (the `LoginService` seam is
  provider-agnostic). Prod profile also marks cookies Secure.
- **"Your turn" Web Push (next task):** unblocked now that the deploy is HTTPS and presence is wired.
