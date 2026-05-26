import { useEffect, useRef } from "react";
import type { BattleEvent } from "./types";
import { displayTerritory } from "./territoryName";

/**
 * A live battle log fed by the server's {type:"battle"} events (see WebDisplay). Read-only; it just
 * narrates what the engine resolved — battle start, dice, casualties, retreats, and the result.
 * Auto-scrolls to the newest line.
 */
export function BattleLog({ events }: { events: BattleEvent[] }) {
  const endRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    endRef.current?.scrollIntoView({ block: "end" });
  }, [events]);

  if (events.length === 0) return null;

  return (
    <div
      style={{
        position: "fixed",
        bottom: 16,
        left: 16,
        width: 360,
        maxHeight: "32vh",
        overflowY: "auto",
        background: "rgba(20,28,36,0.97)",
        border: "1px solid #67788a",
        borderRadius: 6,
        padding: 10,
        color: "#e6e6e6",
        fontFamily: "sans-serif",
        fontSize: 12,
        zIndex: 90,
        boxShadow: "0 4px 24px rgba(0,0,0,0.5)",
      }}
    >
      <div style={{ fontWeight: "bold", fontSize: 13, marginBottom: 4 }}>Battle log</div>
      {events.map((e, i) => (
        <BattleLine key={i} event={e} />
      ))}
      <div ref={endRef} />
    </div>
  );
}

function BattleLine({ event }: { event: BattleEvent }) {
  switch (event.kind) {
    case "start":
      return (
        <div style={{ marginTop: 6, color: "#ffd54a" }}>
          ⚔ {displayTerritory(event.location ?? "")}
          {event.amphibious ? " (amphibious)" : ""}:{" "}
          <span style={{ color: "#e6e6e6" }}>
            {event.attacker} ({event.attackers || "—"}) vs {event.defender} (
            {event.defenders || "—"})
          </span>
        </div>
      );
    case "round":
      return (
        <div style={{ marginTop: 4, color: "#cdb98a", borderTop: "1px solid #3a4654", paddingTop: 3 }}>
          ── Round {event.round} ──{" "}
          <span style={{ color: "#e6e6e6" }}>
            {event.attacker}: {event.attackers || "—"} · {event.defender}: {event.defenders || "—"}
          </span>
        </div>
      );
    case "dice":
      return (
        <div style={{ color: "#9fb6c9", paddingLeft: 10 }}>
          {event.step}: <b>{event.hits}</b> hit{event.hits === 1 ? "" : "s"}
        </div>
      );
    case "casualties":
      return (
        <div style={{ color: "#e89", paddingLeft: 10 }}>
          {event.player} lost {event.killed || "nothing"}
          {event.damaged ? ` (damaged: ${event.damaged})` : ""}
        </div>
      );
    case "retreat":
      return <div style={{ color: "#9fb6c9", paddingLeft: 10 }}>{event.message}</div>;
    case "end":
      return <div style={{ color: "#7c7", marginBottom: 2 }}>{event.message}</div>;
    default:
      return null;
  }
}
