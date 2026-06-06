import type { CSSProperties } from "react";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  catalog,
  claimSeat,
  createTable,
  launchGame,
  logout,
  me,
  myGames,
  releaseSeat,
  setReady,
  turnLimitLabel,
  TURN_LIMIT_PRESETS,
} from "../api/controlPlane";
import type { CatalogEntry, LobbyTable, MyGame, SeatView, User } from "../api/controlPlane";
import { useLobbySocket } from "../lobby/useLobbySocket";
import { NotificationsToggle } from "../NotificationsToggle";

/** Run an action and surface a server rejection (409/403/…) inline rather than failing silently. */
async function act(p: Promise<Response>): Promise<void> {
  const res = await p;
  if (!res.ok) {
    const body = await res.text().catch(() => "");
    window.alert(`Action failed (HTTP ${res.status})${body ? ": " + body : ""}`);
  }
}

/**
 * The lobby: shows the games you can host, the live list of open tables (pushed over the lobby
 * WebSocket), and per-seat claim/ready/leave controls plus a host launch. Redirects to /login if
 * there's no session. State comes from the WebSocket — actions just POST and let the broadcast
 * refresh everyone.
 */
export function Lobby() {
  const navigate = useNavigate();
  const [user, setUser] = useState<User | null | undefined>(undefined);
  const [games, setGames] = useState<CatalogEntry[]>([]);
  const [mine, setMine] = useState<MyGame[]>([]);
  // Host's chosen per-turn timer for a new table (seconds; default 3 days).
  const [turnLimit, setTurnLimit] = useState<number>(3 * 24 * 60 * 60);
  const tables = useLobbySocket();

  useEffect(() => {
    me().then((u) => (u ? setUser(u) : navigate("/login")));
  }, [navigate]);
  useEffect(() => {
    catalog().then(setGames);
  }, []);
  // Refresh "my games" on every lobby change (launching flips a table from open → active, so it
  // leaves the open list and appears here for non-host players to enter).
  useEffect(() => {
    myGames().then(setMine);
  }, [tables]);

  // Launched games the user is in (lobby tables already appear under "Open tables").
  const launched = mine.filter((g) => g.status !== "lobby");

  if (user === undefined) {
    return <div style={page}>Loading…</div>;
  }
  if (!user) {
    return null;
  }

  return (
    <div style={page}>
      <header style={header}>
        <span style={{ fontSize: 18, fontWeight: 600 }}>TripleA Web — Lobby</span>
        <span style={{ color: "#999", fontSize: 13 }}>
          {user.displayName} · {user.playerChatId}
          <NotificationsToggle style={{ ...btn, marginLeft: 10 }} />
          <button
            style={{ ...btn, marginLeft: 10 }}
            onClick={() => logout().then(() => navigate("/login"))}
          >
            Log out
          </button>
        </span>
      </header>

      <section style={{ marginBottom: 20 }}>
        <h2 style={h2}>Host a game</h2>
        {games.length === 0 ? (
          <p style={muted}>No games configured (set CONTROL_PLANE_GAME_XML on the server).</p>
        ) : (
          <>
            <label style={{ ...muted, display: "block", marginBottom: 8 }}>
              Turn timer:{" "}
              <select
                value={turnLimit}
                onChange={(e) => setTurnLimit(Number(e.target.value))}
                style={{ ...btn, padding: "3px 6px" }}
              >
                {TURN_LIMIT_PRESETS.map((p) => (
                  <option key={p.seconds} value={p.seconds}>
                    {p.label}
                  </option>
                ))}
              </select>{" "}
              <span title="When a seat's turn isn't taken in time, AI takes it over; the player can reclaim it on return.">
                ⓘ
              </span>
            </label>
            <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
              {games.map((g) => (
                <button
                  key={g.id}
                  style={primaryBtn}
                  onClick={() => act(createTable(g.id, turnLimit))}
                >
                  + {g.name}
                </button>
              ))}
            </div>
          </>
        )}
      </section>

      {launched.length > 0 && (
        <section style={{ marginBottom: 20 }}>
          <h2 style={h2}>Your games in progress</h2>
          {launched.map((g) => (
            <div key={g.id} style={card}>
              <div
                style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}
              >
                <span>
                  <span style={{ fontWeight: 600 }}>{g.name}</span>{" "}
                  <span style={muted}>
                    · {g.status}
                    {g.seat ? ` · you play ${g.seat}` : g.isHost ? " · host" : ""}
                  </span>
                </span>
                <button style={primaryBtn} onClick={() => navigate(`/game/${g.id}`)}>
                  Enter
                </button>
              </div>
            </div>
          ))}
        </section>
      )}

      <section>
        <h2 style={h2}>Open tables</h2>
        {tables.length === 0 ? (
          <p style={muted}>No tables yet — host one above.</p>
        ) : (
          tables.map((t) => <TableCard key={t.id} table={t} user={user} />)
        )}
      </section>
    </div>
  );
}

function TableCard({ table, user }: { table: LobbyTable; user: User }) {
  const navigate = useNavigate();
  const isHost = table.hostChatId === user.playerChatId;

  async function launch() {
    const res = await launchGame(table.id);
    if (res.ok) {
      navigate(`/game/${table.id}`); // into the game (it spawns + loads on connect)
    } else {
      const body = await res.text().catch(() => "");
      window.alert(`Launch failed (HTTP ${res.status})${body ? ": " + body : ""}`);
    }
  }

  return (
    <div style={card}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
        <span style={{ fontWeight: 600 }}>{table.name}</span>
        <span style={muted}>
          host: {table.hostName} · ⏱ {turnLimitLabel(table.turnLimitSeconds)}
        </span>
      </div>
      <ul style={{ listStyle: "none", padding: 0, margin: "8px 0" }}>
        {table.seats.map((s) => (
          <SeatRow key={s.power} table={table} seat={s} user={user} />
        ))}
      </ul>
      {isHost && (
        <button style={primaryBtn} onClick={launch}>
          Launch game
        </button>
      )}
    </div>
  );
}

function SeatRow({ table, seat, user }: { table: LobbyTable; seat: SeatView; user: User }) {
  const mine = seat.ownerChatId === user.playerChatId;
  return (
    <li style={seatRow}>
      {seat.kind === "human" && (
        <span
          title={seat.connected ? "connected" : "not connected"}
          style={{ color: seat.connected ? "#6d6" : "#666", fontSize: 10 }}
        >
          ●
        </span>
      )}
      <span style={{ width: 100, fontWeight: 500 }}>{seat.power}</span>
      {seat.kind === "open" && (
        <button style={btn} onClick={() => act(claimSeat(table.id, seat.power))}>
          Claim
        </button>
      )}
      {seat.kind === "human" && !mine && (
        <span style={muted}>
          {seat.ownerName} {seat.ready ? "· ✓ ready" : "· not ready"}
        </span>
      )}
      {mine && (
        <>
          <span style={{ color: seat.ready ? "#2d8" : "#ddd" }}>
            you {seat.ready ? "· ✓ ready" : ""}
          </span>
          <button style={btn} onClick={() => act(setReady(table.id, seat.power, !seat.ready))}>
            {seat.ready ? "Unready" : "Ready"}
          </button>
          <button style={btn} onClick={() => act(releaseSeat(table.id, seat.power))}>
            Leave
          </button>
        </>
      )}
    </li>
  );
}

const page: CSSProperties = {
  minHeight: "100vh",
  background: "#1a1a1a",
  color: "#ddd",
  fontFamily: "sans-serif",
  padding: 24,
  boxSizing: "border-box",
};
const header: CSSProperties = {
  display: "flex",
  justifyContent: "space-between",
  alignItems: "center",
  borderBottom: "1px solid #333",
  paddingBottom: 12,
  marginBottom: 20,
};
const h2: CSSProperties = { fontSize: 15, color: "#bbb", margin: "0 0 8px" };
const muted: CSSProperties = { color: "#888", fontSize: 13 };
const card: CSSProperties = {
  background: "#242424",
  border: "1px solid #444",
  borderRadius: 8,
  padding: 16,
  marginBottom: 12,
  maxWidth: 520,
};
const seatRow: CSSProperties = { display: "flex", alignItems: "center", gap: 8, padding: "3px 0" };
const btn: CSSProperties = {
  background: "#333",
  border: "1px solid #555",
  color: "#ddd",
  borderRadius: 4,
  padding: "4px 12px",
  cursor: "pointer",
  fontSize: 13,
};
const primaryBtn: CSSProperties = {
  ...btn,
  background: "#2d8",
  borderColor: "#2d8",
  color: "#052",
  fontWeight: 600,
};
