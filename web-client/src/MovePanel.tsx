import type { MovableUnit, MoveRequest } from "./types";
import { displayTerritory } from "./territoryName";

/**
 * The move panel (combat and non-combat; land, sea, air, transport load/unload). The player clicks a
 * source territory on the map (one with movable units), picks how many of each unit type to send,
 * then clicks any destination — the server finds the best legal route there (the same routing the
 * desktop client uses) and sends it back as a `preview`, which this panel summarises and the map
 * highlights. "Move" commits it; the engine validates and a rejection comes back as `error`. A
 * land→sea route auto-loads the chosen land units onto transports in the destination sea zone.
 * "Done" ends the move phase.
 */
export function MovePanel({
  request,
  from,
  to,
  units,
  setUnits,
  onMove,
  onClear,
  onDone,
  onUndo,
  onUndoAll,
}: {
  request: MoveRequest;
  from: string | null;
  to: string | null;
  units: Record<string, number>;
  setUnits: (u: Record<string, number>) => void;
  onMove: () => void;
  onClear: () => void;
  onDone: () => void;
  onUndo: (index: number) => void;
  onUndoAll: () => void;
}) {
  const movable: MovableUnit[] = from ? (request.movableUnits[from] ?? []) : [];
  const totalChosen = Object.values(units).reduce((a, b) => a + b, 0);
  // The preview is authoritative only when it's the one we asked for (this source + destination).
  const preview =
    request.preview && request.preview.from === from && request.preview.to === to
      ? request.preview
      : null;
  const canMove = !!from && !!to && totalChosen > 0 && preview?.route != null;

  function bump(type: string, delta: number, max: number) {
    const next = Math.min(max, Math.max(0, (units[type] ?? 0) + delta));
    setUnits({ ...units, [type]: next });
  }

  // Select every eligible unit in the source territory. `movable` is already filtered server-side to
  // units with movement left (or transportable cargo), so this never picks a spent unit.
  function selectAll() {
    const all: Record<string, number> = {};
    for (const mu of movable) all[mu.type] = mu.count;
    setUnits(all);
  }

  return (
    <div style={{ padding: "10px 0" }}>
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
          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              marginBottom: 3,
            }}
          >
            <span style={{ color: "#9fb6c9", fontSize: 11 }}>Moves this phase (newest last):</span>
            <button
              onClick={onUndoAll}
              title="Undo every move made this phase"
              style={{ ...secondaryBtn, flex: "none", padding: "2px 8px" }}
            >
              Undo all
            </button>
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
              <span style={{ flex: 1, color: "#cdd6df" }}>
                <b>{m.units}</b>
                <span style={{ color: "#9fb6c9" }}> · {displayTerritory(m.label)}</span>
              </span>
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

      {!from ? (
        <div style={{ color: "#9fb6c9" }}>
          Click a territory with your units to start a move. Territories with movable units:{" "}
          <b>{Object.keys(request.movableUnits).length}</b>.
        </div>
      ) : (
        <>
          <div style={{ marginBottom: 6, color: "#9fb6c9" }}>
            From: <b style={{ color: "#ff8c2a" }}>{displayTerritory(from)}</b>
            {to ? (
              <RouteSummary to={to} preview={preview} hasUnits={totalChosen > 0} />
            ) : (
              <div style={{ fontSize: 11, marginTop: 2 }}>Now click a destination territory.</div>
            )}
          </div>
          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              marginBottom: 4,
            }}
          >
            <span style={{ color: "#9fb6c9", fontSize: 11 }}>Units to move:</span>
            <button
              onClick={selectAll}
              title="Select every eligible unit here"
              style={{ ...secondaryBtn, flex: "none", padding: "2px 8px" }}
            >
              Select all
            </button>
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 3 }}>
            {movable.map((mu) => {
              const n = units[mu.type] ?? 0;
              const max = mu.count;
              const kind = mu.air ? "✈" : mu.sea ? "⚓" : "▮";
              const kindColor = mu.air ? "#7ec8ff" : mu.sea ? "#9fd0ff" : "#cdb98a";
              const blocked = preview?.blockedTypes.includes(mu.type) && n > 0;
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
                    {blocked && (
                      <span title="Not enough movement to reach the destination this turn"
                        style={{ color: "#e7a23c", fontSize: 11 }}> · can’t reach</span>
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
        <button onClick={onClear} disabled={!from} style={secondaryBtn}>
          Clear
        </button>
        <button onClick={onDone} style={secondaryBtn}>
          Done
        </button>
      </div>
    </div>
  );
}

/** The route line under "From": the previewed path + cost, a "no route" reason, or a pending hint. */
function RouteSummary({
  to,
  preview,
  hasUnits,
}: {
  to: string;
  preview: MoveRequest["preview"];
  hasUnits: boolean;
}) {
  if (!preview) {
    // Destination chosen but the server hasn't echoed a route yet (or no units picked).
    return (
      <div style={{ fontSize: 11, marginTop: 2 }}>
        To <b style={{ color: "#cdd6df" }}>{displayTerritory(to)}</b> —{" "}
        {hasUnits ? "finding route…" : "pick units to preview the route."}
      </div>
    );
  }
  if (!preview.route) {
    return (
      <div style={{ fontSize: 11, marginTop: 2, color: "#e7a23c" }}>
        {preview.message ?? "No legal route."}
      </div>
    );
  }
  return (
    <div style={{ fontSize: 11, marginTop: 2 }}>
      Route:{" "}
      <b style={{ color: "#ff8c2a" }}>{preview.route.map(displayTerritory).join(" → ")}</b>
      <span style={{ color: "#9fb6c9" }}> · cost {preview.cost}</span>
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
