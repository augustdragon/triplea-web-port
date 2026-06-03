import type { CSSProperties } from "react";
import type { SeatRoster } from "./types";

/**
 * The lobby waiting room, shown while a launched game's roster phase is "waiting". Seats are already
 * assigned by the lobby (no claiming here); this screen shows who holds each seat and whether they
 * have connected yet, and gives the host a Start button. The game starts automatically once every
 * human seat is connected, or when the host starts early.
 */
interface Props {
  roster: SeatRoster;
  mySeat: string | null;
  isHost: boolean;
  colors: Record<string, string>;
  onStart: () => void;
}

export function WaitingRoom({ roster, mySeat, isHost, colors, onStart }: Props) {
  const humans = roster.seats.filter((s) => s.owner != null);
  const connected = humans.filter((s) => s.connected).length;
  const allConnected = humans.length > 0 && connected === humans.length;
  return (
    <div style={overlay}>
      <div style={card}>
        <h1 style={{ margin: "0 0 4px", fontSize: 22 }}>{roster.gameName}</h1>
        <p style={{ margin: "0 0 16px", color: "#aaa", fontSize: 14 }}>
          {roster.savedGame
            ? `Resuming round ${roster.savedGame.round}. `
            : "Waiting for players. "}
          {connected} of {humans.length} connected.
        </p>
        <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 14 }}>
          <thead>
            <tr style={{ textAlign: "left", color: "#888", fontSize: 12 }}>
              <th style={th}>Nation</th>
              <th style={th}>Player</th>
              <th style={th}>Status</th>
            </tr>
          </thead>
          <tbody>
            {roster.seats.map((s) => {
              const mine = s.name === mySeat;
              return (
                <tr key={s.name} style={{ borderTop: "1px solid #333" }}>
                  <td style={td}>
                    <span style={{ color: colors[s.name] ?? "#ccc", fontWeight: 600 }}>
                      {s.name}
                    </span>
                  </td>
                  <td style={td}>
                    {s.owner != null ? (
                      <span>
                        👤 {mine ? `${s.owner} (you)` : s.owner}
                      </span>
                    ) : (
                      <span style={{ color: "#888" }}>AI</span>
                    )}
                  </td>
                  <td style={td}>
                    {s.owner == null ? (
                      <span style={{ color: "#888" }}>—</span>
                    ) : s.connected ? (
                      <span style={{ color: "#6d6" }}>● connected</span>
                    ) : (
                      <span style={{ color: "#c93" }}>○ waiting…</span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {isHost ? (
          <div style={{ display: "flex", gap: 12, marginTop: 20, justifyContent: "flex-end" }}>
            <button style={primaryBtn} onClick={onStart}>
              {roster.savedGame ? "Resume now" : allConnected ? "Start now" : "Start anyway"}
            </button>
          </div>
        ) : (
          <p style={{ color: "#777", fontSize: 12, marginTop: 16 }}>
            The game begins when everyone has connected, or when the host starts it.
          </p>
        )}
        {isHost && !allConnected && (
          <p style={{ color: "#777", fontSize: 12, marginTop: 12 }}>
            Starting before everyone connects leaves their seats waiting until they join.
          </p>
        )}
      </div>
    </div>
  );
}

const overlay: CSSProperties = {
  position: "fixed",
  inset: 0,
  background: "#1a1a1aee",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  zIndex: 50,
  fontFamily: "sans-serif",
};
const card: CSSProperties = {
  background: "#242424",
  border: "1px solid #444",
  borderRadius: 8,
  padding: 28,
  width: 560,
  maxHeight: "85vh",
  overflowY: "auto",
  color: "#ddd",
};
const th: CSSProperties = { padding: "4px 8px", fontWeight: 500 };
const td: CSSProperties = { padding: "8px" };
const btn: CSSProperties = {
  background: "#333",
  border: "1px solid #555",
  color: "#ddd",
  borderRadius: 4,
  padding: "6px 14px",
  cursor: "pointer",
};
const primaryBtn: CSSProperties = {
  ...btn,
  background: "#2d8",
  borderColor: "#2d8",
  color: "#052",
  fontWeight: 600,
};
