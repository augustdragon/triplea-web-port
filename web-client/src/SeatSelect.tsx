import type { CSSProperties } from "react";
import type { SeatRoster } from "./types";

/**
 * The pre-game seat-assignment screen, shown while the roster's phase is "setup". Mirrors the
 * engine's local/network setup: each nation is Open (runs as a selectable AI) or claimed by a human.
 * Claim a nation to play it, change an open seat's AI type, then anyone can start the game. "Watch
 * only" dismisses this screen to spectate the (not-yet-started) board.
 */
interface Props {
  roster: SeatRoster;
  mySeat: string | null;
  myName: string;
  colors: Record<string, string>;
  onNameChange: (name: string) => void;
  onClaim: (seat: string) => void;
  onRelease: (seat: string) => void;
  onSetType: (seat: string, type: string) => void;
  onStart: () => void;
  onSpectate: () => void;
}

export function SeatSelect({
  roster,
  mySeat,
  myName,
  colors,
  onNameChange,
  onClaim,
  onRelease,
  onSetType,
  onStart,
  onSpectate,
}: Props) {
  const claimed = roster.seats.filter((s) => s.owner != null).length;
  return (
    <div style={overlay}>
      <div style={card}>
        <h1 style={{ margin: "0 0 4px", fontSize: 22 }}>{roster.gameName}</h1>
        <p style={{ margin: "0 0 16px", color: "#aaa", fontSize: 14 }}>
          Claim a nation to play it, or leave it to the chosen AI. {claimed} human{" "}
          {claimed === 1 ? "seat" : "seats"} claimed.
        </p>
        <label style={{ display: "block", marginBottom: 16, fontSize: 14 }}>
          Your name:{" "}
          <input
            value={myName}
            onChange={(e) => onNameChange(e.target.value)}
            placeholder="Player"
            style={input}
          />
        </label>
        <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 14 }}>
          <thead>
            <tr style={{ textAlign: "left", color: "#888", fontSize: 12 }}>
              <th style={th}>Nation</th>
              <th style={th}>Controlled by</th>
              <th style={th} />
            </tr>
          </thead>
          <tbody>
            {roster.seats.map((s) => {
              const mine = s.owner != null && s.name === mySeat;
              const takenByOther = s.owner != null && !mine;
              return (
                <tr key={s.name} style={{ borderTop: "1px solid #333" }}>
                  <td style={td}>
                    <span style={{ color: colors[s.name] ?? "#ccc", fontWeight: 600 }}>
                      {s.name}
                    </span>
                  </td>
                  <td style={td}>
                    {s.owner != null ? (
                      <span>👤 {mine ? `${s.owner} (you)` : s.owner}</span>
                    ) : (
                      <select
                        value={s.aiType}
                        onChange={(e) => onSetType(s.name, e.target.value)}
                        style={select}
                      >
                        {roster.aiTypes.map((t) => (
                          <option key={t} value={t}>
                            {t}
                          </option>
                        ))}
                      </select>
                    )}
                  </td>
                  <td style={{ ...td, textAlign: "right" }}>
                    {mine ? (
                      <button style={btn} onClick={() => onRelease(s.name)}>
                        Release
                      </button>
                    ) : takenByOther ? (
                      <span style={{ color: "#666" }}>taken</span>
                    ) : (
                      <button style={primaryBtn} onClick={() => onClaim(s.name)}>
                        Claim
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        <div style={{ display: "flex", gap: 12, marginTop: 20, justifyContent: "flex-end" }}>
          <button style={btn} onClick={onSpectate}>
            Watch only
          </button>
          <button style={primaryBtn} onClick={onStart}>
            Start game
          </button>
        </div>
        <p style={{ color: "#777", fontSize: 12, marginTop: 12 }}>
          Unclaimed nations play as the selected AI. Anyone can start once seats are set.
        </p>
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
const input: CSSProperties = {
  background: "#1a1a1a",
  border: "1px solid #555",
  color: "#eee",
  borderRadius: 4,
  padding: "4px 8px",
};
const select: CSSProperties = {
  background: "#1a1a1a",
  border: "1px solid #555",
  color: "#eee",
  borderRadius: 4,
  padding: "3px 6px",
};
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
