import type { BattleEvent, MapGeometry, StateSnapshot } from "./types";
import { PhaseIndicator } from "./PhaseIndicator";
import { BattleLog } from "./BattleLog";
import { Minimap } from "./Minimap";

/**
 * The right info pane: a fixed sidebar holding a minimap, connection + turn status, the
 * Relationships button, the phase indicator, and the battle log. The information tabs and the
 * active decision panel live in the bottom dock (see App), not here — this pane keeps the stable,
 * glanceable, always-visible info.
 */
export function Sidebar({
  wsStatus,
  snapshot,
  geometry,
  owners,
  territoryCount,
  events,
  width,
  onNewGame,
}: {
  wsStatus: string;
  snapshot: StateSnapshot | null;
  geometry: MapGeometry;
  owners: Record<string, string>;
  territoryCount: number;
  events: BattleEvent[];
  width: number;
  onNewGame: () => void;
}) {
  const live = wsStatus === "live";
  return (
    <div
      style={{
        position: "fixed",
        top: 0,
        right: 0,
        width,
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
      {/* Minimap — whole-map overview, tinted by current ownership. */}
      <div style={{ padding: 8, borderBottom: "1px solid #3a4654", background: "rgba(0,0,0,0.2)" }}>
        <Minimap geometry={geometry} owners={owners} width={width - 16} />
      </div>

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
          onClick={onNewGame}
          title="Reset the game on the server and start a fresh one (testing convenience)"
          style={{
            width: "100%",
            marginTop: 6,
            background: "#3a2730",
            color: "#e8c9c9",
            border: "1px solid #5c4650",
            borderRadius: 5,
            padding: "5px 0",
            fontSize: 12,
            cursor: "pointer",
          }}
        >
          ⟳ New game
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
