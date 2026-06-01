import type { CSSProperties } from "react";
import type { SeatRoster } from "./types";

/**
 * Shown when reconnecting into a **running** game without a confirmed seat (e.g. after closing and
 * reopening the browser). Unlike setup, claiming a live seat is a deliberate act — it can take over
 * a buffered decision or evict whoever is there — so we confirm rather than silently auto-claim.
 *
 * Cases, in priority order:
 *  - your remembered seat is open → one-click **Rejoin**;
 *  - your remembered seat is held by someone else → explicit **Take over**;
 *  - otherwise → a picker of open human seats (seats whose player dropped), or just spectate.
 *
 * Only **human** seats are offered (AI seats have no buffered decision to take over).
 */
interface Props {
  roster: SeatRoster;
  recalledSeat: string | null;
  colors: Record<string, string>;
  onRejoin: (seat: string) => void;
  onSpectate: () => void;
}

export function RejoinPrompt({ roster, recalledSeat, colors, onRejoin, onSpectate }: Props) {
  const tint = (name: string) => ({ color: colors[name] ?? "#ccc", fontWeight: 600 });
  const reclaimable = roster.seats.filter((s) => s.human && !s.owner);
  const recalled = recalledSeat
    ? roster.seats.find((s) => s.name === recalledSeat && s.human)
    : undefined;

  let body;
  if (recalled && !recalled.owner) {
    body = (
      <>
        <p style={msg}>
          Rejoin as <span style={tint(recalled.name)}>{recalled.name}</span>? Your turn is waiting.
        </p>
        <button style={primaryBtn} onClick={() => onRejoin(recalled.name)}>
          Rejoin as {recalled.name}
        </button>
      </>
    );
  } else if (recalled && recalled.owner) {
    body = (
      <>
        <p style={msg}>
          <span style={tint(recalled.name)}>{recalled.name}</span> is currently controlled by{" "}
          <b>{recalled.owner}</b>. Take it over?
        </p>
        <button style={primaryBtn} onClick={() => onRejoin(recalled.name)}>
          Take over {recalled.name}
        </button>
      </>
    );
  } else if (reclaimable.length > 0) {
    body = (
      <>
        <p style={msg}>Claim an open seat to play:</p>
        <div style={{ display: "flex", flexWrap: "wrap", gap: 8, justifyContent: "center" }}>
          {reclaimable.map((s) => (
            <button key={s.name} style={primaryBtn} onClick={() => onRejoin(s.name)}>
              <span style={tint(s.name)}>{s.name}</span>
            </button>
          ))}
        </div>
      </>
    );
  } else {
    body = <p style={msg}>No open seats to rejoin right now — you can watch.</p>;
  }

  return (
    <div style={overlay}>
      <div style={card}>
        <h1 style={{ margin: "0 0 6px", fontSize: 20 }}>{roster.gameName} — game in progress</h1>
        {body}
        <button style={{ ...btn, marginTop: 16 }} onClick={onSpectate}>
          Watch only
        </button>
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
  width: 460,
  textAlign: "center",
  color: "#ddd",
};
const msg: CSSProperties = { margin: "0 0 16px", fontSize: 15, lineHeight: 1.5 };
const btn: CSSProperties = {
  background: "#333",
  border: "1px solid #555",
  color: "#ddd",
  borderRadius: 4,
  padding: "8px 18px",
  cursor: "pointer",
  fontSize: 14,
};
const primaryBtn: CSSProperties = {
  ...btn,
  background: "#2d8",
  borderColor: "#2d8",
  color: "#052",
  fontWeight: 600,
};
