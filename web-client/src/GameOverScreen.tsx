import type { CSSProperties } from "react";

/** The end-of-game summary pushed as {type:"gameOver"} (or derived from a finished /connect). */
export interface GameOverInfo {
  reason: string;
  winners: string[];
  message: string;
}

/**
 * The end screen, shown when the game is over. Announces the winner (or the end reason when there
 * is none — a round-cap / stuck / error stop) and offers a way back to the lobby. Players stay on
 * this screen rather than being silently disconnected.
 */
export function GameOverScreen({
  info,
  colors,
  inLobbyFlow,
}: {
  info: GameOverInfo;
  colors: Record<string, string>;
  inLobbyFlow: boolean;
}) {
  const headline =
    info.winners.length > 0
      ? `${info.winners.length === 1 ? "Winner" : "Winners"}`
      : "Game over";
  return (
    <div style={overlay}>
      <div style={card}>
        <h1 style={{ margin: "0 0 8px", fontSize: 26 }}>🏁 {headline}</h1>
        {info.winners.length > 0 ? (
          <p style={{ margin: "0 0 12px", fontSize: 18 }}>
            {info.winners.map((w, i) => (
              <span key={w}>
                {i > 0 ? " & " : ""}
                <span style={{ color: colors[w] ?? "#ddd", fontWeight: 700 }}>{w}</span>
              </span>
            ))}
          </p>
        ) : null}
        <p style={{ margin: "0 0 20px", color: "#bbb", fontSize: 14 }}>{info.message}</p>
        <p style={{ margin: "0 0 20px", color: "#777", fontSize: 12 }}>
          Reason: {info.reason || "unknown"}
        </p>
        {inLobbyFlow ? (
          <a href="/" style={primaryBtn}>
            Back to lobby
          </a>
        ) : (
          <p style={{ color: "#777", fontSize: 12 }}>Reload to start a new game.</p>
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
  zIndex: 60,
  fontFamily: "sans-serif",
};
const card: CSSProperties = {
  background: "#242424",
  border: "1px solid #444",
  borderRadius: 8,
  padding: 32,
  width: 460,
  textAlign: "center",
  color: "#ddd",
};
const primaryBtn: CSSProperties = {
  display: "inline-block",
  background: "#2d8",
  border: "1px solid #2d8",
  color: "#052",
  borderRadius: 4,
  padding: "8px 18px",
  fontWeight: 600,
  textDecoration: "none",
  cursor: "pointer",
};
