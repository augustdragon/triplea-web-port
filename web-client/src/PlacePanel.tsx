import type { PlaceRequest } from "./types";
import { displayTerritory } from "./territoryName";

/**
 * The place-units panel (3e). The player clicks a destination territory on the map, picks how many
 * of each bought unit to place there, and submits — one territory at a time, like the move panel.
 * The engine validates the placement (factory, production caps, sea-zone adjacency) and a rejection
 * comes back as `error`. "Done" ends the phase (any unplaced units are lost).
 */
export function PlacePanel({
  request,
  target,
  units,
  setUnits,
  onPlace,
  onDone,
}: {
  request: PlaceRequest;
  target: string | null;
  units: Record<string, number>;
  setUnits: (u: Record<string, number>) => void;
  onPlace: () => void;
  onDone: () => void;
}) {
  const totalChosen = Object.values(units).reduce((a, b) => a + b, 0);
  const canPlace = target !== null && totalChosen > 0;

  function bump(type: string, delta: number, max: number) {
    const next = Math.min(max, Math.max(0, (units[type] ?? 0) + delta));
    setUnits({ ...units, [type]: next });
  }

  return (
    <div style={{ padding: "10px 0" }}>
      <div style={{ fontWeight: "bold", fontSize: 15, marginBottom: 2 }}>
        {request.bid ? "Place (bid)" : "Place units"} — {request.player}
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

      <div style={{ marginBottom: 6, color: "#9fb6c9" }}>
        {target ? (
          <>
            Placing at <b style={{ color: "#ff8c2a" }}>{displayTerritory(target)}</b>
          </>
        ) : (
          "Click a territory to place into (land needs a factory; sea zones next to one)."
        )}
      </div>

      <div style={{ display: "flex", flexDirection: "column", gap: 3 }}>
        {request.toPlace.map((u) => {
          const n = units[u.type] ?? 0;
          const kind = u.air ? "✈" : u.sea ? "⚓" : "▮";
          const kindColor = u.air ? "#7ec8ff" : u.sea ? "#9fd0ff" : "#cdb98a";
          return (
            <div
              key={u.type}
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
                <span title={u.air ? "air" : u.sea ? "sea" : "land"} style={{ color: kindColor }}>
                  {kind}
                </span>{" "}
                {u.type} <span style={{ color: "#9fb6c9" }}>({u.count})</span>
              </span>
              <button onClick={() => bump(u.type, -1, u.count)} style={btn} disabled={n === 0}>
                −
              </button>
              <span style={{ width: 24, textAlign: "center" }}>{n}</span>
              <button onClick={() => bump(u.type, +1, u.count)} style={btn} disabled={n >= u.count}>
                +
              </button>
            </div>
          );
        })}
      </div>

      <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
        <button
          onClick={onPlace}
          disabled={!canPlace}
          style={{ ...primaryBtn, opacity: canPlace ? 1 : 0.5 }}
        >
          Place
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
