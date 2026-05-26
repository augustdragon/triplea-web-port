import type { ReactNode } from "react";
import type { BattleEvent, StateSnapshot } from "./types";
import { PhaseIndicator } from "./PhaseIndicator";
import { BattleLog } from "./BattleLog";

/**
 * The single control/info pane: a fixed right sidebar that gathers everything the player needs in
 * one place — connection + turn status, the phase indicator, the active decision panel (passed as
 * `children`), and the battle log. Replaces the previously scattered move panel (upper-right) and
 * battle log (lower-left).
 */
export function Sidebar({
  wsStatus,
  snapshot,
  territoryCount,
  events,
  children,
}: {
  wsStatus: string;
  snapshot: StateSnapshot | null;
  territoryCount: number;
  events: BattleEvent[];
  children: ReactNode;
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
      </div>

      <PhaseIndicator step={snapshot?.step} />

      {/* Active decision panel (purchase / move / casualties / retreat), or an idle note. */}
      <div style={{ flex: "0 1 auto", overflowY: "auto", maxHeight: "46vh", padding: "0 12px" }}>
        {children}
      </div>

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
