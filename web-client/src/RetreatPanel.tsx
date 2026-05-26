import type { RetreatRequest } from "./types";
import { displayTerritory } from "./territoryName";

/**
 * The retreat panel (3d-3). Between battle rounds the attacker may pull out (or a sub may submerge).
 * Shows the surviving forces on both sides so the choice is informed, then offers each retreat
 * destination plus "Stay and fight". Replies {retreatTo:name} or {remain:true}.
 */
export function RetreatPanel({
  request,
  onRetreat,
  onStay,
}: {
  request: RetreatRequest;
  onRetreat: (territory: string) => void;
  onStay: () => void;
}) {
  return (
    <div
      style={{
        position: "fixed",
        top: 60,
        right: 16,
        width: 340,
        background: "rgba(20,28,36,0.98)",
        border: "1px solid #c9a24a",
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
        {request.submerge ? "Submerge?" : "Retreat?"} — {request.player}
      </div>
      <div style={{ color: "#9fb6c9", marginBottom: 6 }}>
        Battle at <b>{displayTerritory(request.battleTerritory)}</b>
      </div>

      <div
        style={{
          background: "rgba(0,0,0,0.25)",
          borderRadius: 4,
          padding: "5px 7px",
          marginBottom: 8,
          fontSize: 12,
        }}
      >
        <div>
          Your forces: <b>{request.attackers || "—"}</b>
        </div>
        <div>
          Enemy: <b>{request.defenders || "—"}</b>
        </div>
      </div>

      <div style={{ color: "#9fb6c9", marginBottom: 8 }}>{request.message}</div>

      <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
        {request.options.map((name) => (
          <button key={name} onClick={() => onRetreat(name)} style={primaryBtn}>
            {request.submerge ? "Submerge (stay hidden)" : `Retreat to ${displayTerritory(name)}`}
          </button>
        ))}
        <button onClick={onStay} style={secondaryBtn}>
          Stay and fight
        </button>
      </div>
    </div>
  );
}

const primaryBtn: React.CSSProperties = {
  padding: "6px 0",
  background: "#8a6a2a",
  color: "#fff",
  border: "1px solid #c9a24a",
  borderRadius: 4,
  cursor: "pointer",
  fontWeight: "bold",
};
const secondaryBtn: React.CSSProperties = {
  padding: "6px 0",
  background: "#3a6a3a",
  color: "#fff",
  border: "1px solid #5a8a5a",
  borderRadius: 4,
  cursor: "pointer",
  fontWeight: "bold",
};
