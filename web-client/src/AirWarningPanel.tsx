import type { AirWarningRequest } from "./types";
import { displayTerritory } from "./territoryName";

/**
 * Shown when the player tries to end a move phase while they still have aircraft that can't reach
 * friendly territory — those units will be lost at the end of the phase (the engine removes them).
 * Mirrors the base game's "ok to let air die?" confirmation: end anyway and accept the loss, or go
 * back and keep moving to land them.
 */
export function AirWarningPanel({
  request,
  onEndAnyway,
  onKeepMoving,
  onSelect,
}: {
  request: AirWarningRequest;
  onEndAnyway: () => void;
  onKeepMoving: () => void;
  /** Pan the map to a territory so the player can find the at-risk aircraft. */
  onSelect: (territory: string) => void;
}) {
  return (
    <div style={{ padding: "4px 0" }}>
      <div style={{ fontWeight: "bold", fontSize: 15, marginBottom: 4, color: "#ffcf87" }}>
        ⚠ Aircraft can't land — {request.player}
      </div>
      <div style={{ color: "#e6d2b0", marginBottom: 6, maxWidth: 560 }}>
        These territories hold air units that can't reach friendly territory this turn. If you end
        the phase now, <b>they will be lost</b>. Click one to find it on the map:
      </div>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 6, marginBottom: 10 }}>
        {request.territories.map((t) => (
          <button key={t} onClick={() => onSelect(t)} style={chip} title="Show on map">
            {displayTerritory(t)}
          </button>
        ))}
      </div>
      <div style={{ display: "flex", gap: 8 }}>
        <button onClick={onKeepMoving} style={keepBtn}>
          Keep moving
        </button>
        <button onClick={onEndAnyway} style={endBtn}>
          End anyway (lose aircraft)
        </button>
      </div>
    </div>
  );
}

const chip: React.CSSProperties = {
  padding: "4px 10px",
  background: "rgba(150,90,40,0.35)",
  border: "1px solid #b3833a",
  borderRadius: 4,
  fontSize: 13,
  color: "#ffe6c4",
  cursor: "pointer",
};
const keepBtn: React.CSSProperties = {
  flexShrink: 0,
  padding: "7px 16px",
  background: "#3a5a7a",
  color: "#fff",
  border: "1px solid #5a7a9a",
  borderRadius: 5,
  cursor: "pointer",
  fontWeight: "bold",
};
const endBtn: React.CSSProperties = {
  flexShrink: 0,
  padding: "7px 16px",
  background: "#7a2f2f",
  color: "#fff",
  border: "1px solid #b35a5a",
  borderRadius: 5,
  cursor: "pointer",
};
