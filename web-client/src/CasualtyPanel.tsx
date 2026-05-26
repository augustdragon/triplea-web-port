import { useState } from "react";
import type { CasualtyRequest } from "./types";
import { displayTerritory } from "./territoryName";

/**
 * The casualty-selection panel (3d-2). When the player's units are hit with a real choice, they pick
 * exactly `count` units to lose, from the eligible pool. Pre-filled with the engine's auto-pick so
 * the player can just accept. Submit is enabled only when the chosen total equals `count`, so the
 * engine never rejects the selection.
 */
export function CasualtyPanel({
  request,
  onSubmit,
}: {
  request: CasualtyRequest;
  onSubmit: (killed: Record<string, number>) => void;
}) {
  const [killed, setKilled] = useState<Record<string, number>>(request.defaultKilled);

  const total = Object.values(killed).reduce((a, b) => a + b, 0);
  const ready = total === request.count;

  function bump(type: string, delta: number, max: number) {
    const next = Math.min(max, Math.max(0, (killed[type] ?? 0) + delta));
    setKilled({ ...killed, [type]: next });
  }

  return (
    <div
      style={{
        position: "fixed",
        top: 60,
        right: 16,
        width: 340,
        background: "rgba(36,20,20,0.98)",
        border: "1px solid #a55",
        borderRadius: 6,
        padding: 12,
        color: "#e6e6e6",
        fontFamily: "sans-serif",
        fontSize: 13,
        zIndex: 110,
        boxShadow: "0 4px 24px rgba(0,0,0,0.5)",
      }}
    >
      <div style={{ fontWeight: "bold", fontSize: 15, marginBottom: 2 }}>
        Choose casualties — {request.player}
      </div>
      <div style={{ color: "#d9b3b3", marginBottom: 8 }}>
        {request.message || "Select casualties"}
        {request.location ? ` at ${displayTerritory(request.location)}` : ""} — pick{" "}
        <b>{request.count}</b>.
      </div>

      <div style={{ display: "flex", flexDirection: "column", gap: 3 }}>
        {Object.entries(request.options).map(([type, max]) => {
          const n = killed[type] ?? 0;
          return (
            <div
              key={type}
              style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
                padding: "2px 4px",
                background: n > 0 ? "rgba(150,60,60,0.25)" : "transparent",
                borderRadius: 3,
              }}
            >
              <span style={{ flex: 1 }}>
                {type} <span style={{ color: "#c79" }}>({max})</span>
              </span>
              <button onClick={() => bump(type, -1, max)} style={btn} disabled={n === 0}>
                −
              </button>
              <span style={{ width: 24, textAlign: "center" }}>{n}</span>
              <button onClick={() => bump(type, +1, max)} style={btn} disabled={n >= max}>
                +
              </button>
            </div>
          );
        })}
      </div>

      <div style={{ marginTop: 8, color: ready ? "#9c9" : "#e88", fontSize: 12 }}>
        Selected {total} of {request.count}
      </div>
      <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
        <button
          onClick={() => onSubmit(killed)}
          disabled={!ready}
          style={{ ...primaryBtn, opacity: ready ? 1 : 0.5 }}
        >
          Take casualties
        </button>
        <button onClick={() => onSubmit(request.defaultKilled)} style={secondaryBtn}>
          Accept default
        </button>
      </div>
    </div>
  );
}

const btn: React.CSSProperties = {
  width: 24,
  height: 22,
  background: "#4a3333",
  color: "#e6e6e6",
  border: "1px solid #755",
  borderRadius: 3,
  cursor: "pointer",
};
const primaryBtn: React.CSSProperties = {
  flex: 1,
  padding: "6px 0",
  background: "#8a3a3a",
  color: "#fff",
  border: "1px solid #a55",
  borderRadius: 4,
  cursor: "pointer",
  fontWeight: "bold",
};
const secondaryBtn: React.CSSProperties = {
  flex: 1,
  padding: "6px 0",
  background: "#444",
  color: "#ddd",
  border: "1px solid #666",
  borderRadius: 4,
  cursor: "pointer",
};
