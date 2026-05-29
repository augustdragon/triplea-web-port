import type { BattleEvent, StateSnapshot } from "./types";
import { PhaseIndicator } from "./PhaseIndicator";
import { BattleLog } from "./BattleLog";

/**
 * The right info pane: a fixed sidebar holding connection + turn status, the Relationships button,
 * the phase indicator, and the battle log. The active decision panel lives in a separate bottom
 * action bar (see App), not here — keeping this pane to stable, glanceable info.
 */
export function Sidebar({
  wsStatus,
  snapshot,
  territoryCount,
  events,
  onShowRelationships,
}: {
  wsStatus: string;
  snapshot: StateSnapshot | null;
  territoryCount: number;
  events: BattleEvent[];
  onShowRelationships: () => void;
}) {
  const live = wsStatus === "live";
  return (
    <div
      style={{
        position: "fixed",
        top: 0,
        right: 0,
        width: 360,
        height: "100vh",
        display: "flex",
        flexDirection: "column",
        background: "rgba(16,22,30,0.97)",
        borderLeft: "1px solid #3a4654",
        color: "#e6e6e6",
        fontFamily: "sans-serif",
        fontSize: 13,
        zIndex: 100,
        boxShadow: "-4px 0 24px rgba(0,0,0,0.4)",
      }}
    >
      {/* Status header */}
      <div style={{ padding: "10px 12px", borderBottom: "1px solid #3a4654" }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <span style={{ fontWeight: "bold", fontSize: 15 }}>TripleA — Pacific 1940</span>
          <span style={{ fontSize: 11, color: "#9fb6c9" }} title={wsStatus}>
            <b style={{ color: live ? "#7c7" : "#e88" }}>●</b> {live ? "live" : wsStatus}
          </span>
        </div>
        <div style={{ color: "#9fb6c9", marginTop: 3 }}>
          Round <b style={{ color: "#e6e6e6" }}>{snapshot?.round ?? "—"}</b> · turn{" "}
          <b style={{ color: "#e6e6e6" }}>{snapshot?.currentPlayer ?? "—"}</b>
        </div>
        <button
          onClick={onShowRelationships}
          disabled={!snapshot}
          title="Show the current relationships between all powers"
          style={{
            width: "100%",
            marginTop: 8,
            background: "#2c3a4a",
            color: "#e6eef5",
            border: "1px solid #46505c",
            borderRadius: 5,
            padding: "7px 0",
            fontSize: 13,
            fontWeight: 600,
            cursor: snapshot ? "pointer" : "default",
            opacity: snapshot ? 1 : 0.5,
          }}
        >
          ⚔ Relationships
        </button>
      </div>

      <PhaseIndicator step={snapshot?.step} />

      {/* Battle log fills the remaining height. */}
      <div
        style={{
          flex: "1 1 auto",
          minHeight: 0,
          borderTop: "1px solid #3a4654",
          display: "flex",
          flexDirection: "column",
        }}
      >
        <BattleLog events={events} />
      </div>

      <div style={{ padding: "5px 12px", borderTop: "1px solid #3a4654", fontSize: 10, color: "#667" }}>
        drag = pan · wheel = zoom · click = select · {territoryCount} territories
      </div>
    </div>
  );
}
