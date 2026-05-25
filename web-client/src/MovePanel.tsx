import type { MovableUnit, MoveRequest } from "./types";

/**
 * The move panel (3c — combat and non-combat; land, sea, air, transport load/unload). The player
 * clicks a source territory on the map (one with movable units), then clicks further territories to
 * extend the route; this panel picks how many of each movable unit type to send, and submits one
 * move at a time. A land→sea route auto-loads the chosen land units onto transports in the
 * destination sea zone. The engine's move delegate validates each move; a rejection comes back as
 * `error`. "Done" ends the move phase.
 */
export function MovePanel({
  request,
  route,
  units,
  setUnits,
  onMove,
  onClear,
  onDone,
  onUndo,
}: {
  request: MoveRequest;
  route: string[];
  units: Record<string, number>;
  setUnits: (u: Record<string, number>) => void;
  onMove: () => void;
  onClear: () => void;
  onDone: () => void;
  onUndo: (index: number) => void;
}) {
  const source = route[0];
  const movable: MovableUnit[] = source ? (request.movableUnits[source] ?? []) : [];
  const totalChosen = Object.values(units).reduce((a, b) => a + b, 0);
  const canMove = route.length >= 2 && totalChosen > 0;

  function bump(type: string, delta: number, max: number) {
    const next = Math.min(max, Math.max(0, (units[type] ?? 0) + delta));
    setUnits({ ...units, [type]: next });
  }

  return (
    <div
      style={{
        position: "fixed",
        top: 60,
        right: 16,
        width: 340,
        maxHeight: "85vh",
        overflowY: "auto",
        background: "rgba(20,28,36,0.98)",
        border: "1px solid #67788a",
        borderRadius: 6,
        padding: 12,
        color: "#e6e6e6",
        fontFamily: "sans-serif",
        fontSize: 13,
        zIndex: 100,
        boxShadow: "0 4px 24px rgba(0,0,0,0.5)",
      }}
    >
      <div style={{ fontWeight: "bold", fontSize: 15, marginBottom: 2 }}>
        {request.combat ? "Combat move" : "Move"} — {request.player}
      </div>

      {request.error && (
        <div
          style={{
            background: "rgba(150,40,40,0.4)",
            border: "1px solid #a55",
            borderRadius: 4,
            padding: "4px 6px",
            marginBottom: 8,
            color: "#fbb",
          }}
        >
          Rejected: {request.error}
        </div>
      )}

      {request.undoableMoves.length > 0 && (
        <div style={{ marginBottom: 8 }}>
          <div style={{ color: "#9fb6c9", fontSize: 11, marginBottom: 3 }}>
            Moves this phase (newest last):
          </div>
          {request.undoableMoves.map((m) => (
            <div
              key={m.index}
              style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
                padding: "2px 4px",
              }}
            >
              <span style={{ flex: 1, color: "#cdd6df" }}>{m.label}</span>
              <button
                onClick={() => onUndo(m.index)}
                disabled={!m.canUndo}
                title={m.canUndo ? "Undo this move" : "A later move depends on this one"}
                style={{ ...secondaryBtn, flex: "none", padding: "2px 8px", opacity: m.canUndo ? 1 : 0.5 }}
              >
                Undo
              </button>
            </div>
          ))}
        </div>
      )}

      {!source ? (
        <div style={{ color: "#9fb6c9" }}>
          Click a territory with your units to start a move. Territories with movable units:{" "}
          <b>{Object.keys(request.movableUnits).length}</b>.
        </div>
      ) : (
        <>
          <div style={{ marginBottom: 6, color: "#9fb6c9" }}>
            Route: <b style={{ color: "#ff8c2a" }}>{route.join(" → ")}</b>
            <div style={{ fontSize: 11, marginTop: 2 }}>
              Click more territories to extend the path.
            </div>
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 3 }}>
            {movable.map((mu) => {
              const n = units[mu.type] ?? 0;
              const max = mu.count;
              const kind = mu.air ? "✈" : mu.sea ? "⚓" : "▮";
              const kindColor = mu.air ? "#7ec8ff" : mu.sea ? "#9fd0ff" : "#cdb98a";
              return (
                <div
                  key={mu.type}
                  style={{
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "space-between",
                    padding: "2px 4px",
                    background: n > 0 ? "rgba(120,150,90,0.18)" : "transparent",
                    borderRadius: 3,
                  }}
                >
                  <span style={{ flex: 1 }}>
                    <span title={mu.air ? "air" : mu.sea ? "sea" : "land"} style={{ color: kindColor }}>
                      {kind}
                    </span>{" "}
                    {mu.type} <span style={{ color: "#9fb6c9" }}>({max})</span>
                    {mu.movementLeft > 0 && (
                      <span style={{ color: "#7a8a78", fontSize: 11 }}> · move {mu.movementLeft}</span>
                    )}
                  </span>
                  <button onClick={() => bump(mu.type, -1, max)} style={btn} disabled={n === 0}>
                    −
                  </button>
                  <span style={{ width: 24, textAlign: "center" }}>{n}</span>
                  <button onClick={() => bump(mu.type, +1, max)} style={btn} disabled={n >= max}>
                    +
                  </button>
                </div>
              );
            })}
          </div>
        </>
      )}

      <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
        <button
          onClick={onMove}
          disabled={!canMove}
          style={{ ...primaryBtn, opacity: canMove ? 1 : 0.5 }}
        >
          Move
        </button>
        <button onClick={onClear} disabled={!source} style={secondaryBtn}>
          Clear
        </button>
        <button onClick={onDone} style={secondaryBtn}>
          Done
        </button>
      </div>
    </div>
  );
}

const btn: React.CSSProperties = {
  width: 24,
  height: 22,
  background: "#33424f",
  color: "#e6e6e6",
  border: "1px solid #556",
  borderRadius: 3,
  cursor: "pointer",
};
const primaryBtn: React.CSSProperties = {
  flex: 1,
  padding: "6px 0",
  background: "#3a6a3a",
  color: "#fff",
  border: "1px solid #5a8a5a",
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
