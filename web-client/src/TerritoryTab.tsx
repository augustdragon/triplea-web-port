import type { MapGeometry, UnitStack } from "./types";
import { displayTerritory } from "./territoryName";

/**
 * The Territory info tab: the base game's TerritoryDetailPanel, pinned to the territory you last
 * clicked (the map already highlights it yellow). Shows name, land/sea + production, capital, owner,
 * and the units present grouped by owner — all from data the client already has (geometry +
 * the live owners/units snapshot). The Battle Calculator / Add Attackers / Find buttons are deferred
 * (a separate feature); this is the read-only detail view.
 */
export function TerritoryTab({
  geometry,
  owners,
  units,
  colors,
  selected,
}: {
  geometry: MapGeometry;
  owners: Record<string, string>;
  units: Record<string, UnitStack[]>;
  colors: Record<string, string>;
  selected: string | null;
}) {
  if (!selected) {
    return <div style={{ color: "#778", padding: "8px 2px" }}>Click a territory on the map to inspect it.</div>;
  }
  const terr = geometry.territories.find((t) => t.name === selected);
  const stacks = units[selected] ?? [];
  const owner = owners[selected];
  const total = stacks.reduce((sum, s) => sum + s.count, 0);

  // Group the stacks by owner (a contested territory can hold units of more than one power).
  const byOwner = new Map<string, UnitStack[]>();
  for (const s of stacks) {
    const list = byOwner.get(s.owner) ?? [];
    list.push(s);
    byOwner.set(s.owner, list);
  }

  return (
    <div style={{ maxWidth: 420 }}>
      <div style={{ fontWeight: "bold", fontSize: 15, marginBottom: 4 }}>{displayTerritory(selected)}</div>
      {terr ? (
        <div style={{ color: "#9fb6c9", marginBottom: 2 }}>
          {terr.water ? "sea zone" : `land · production ${terr.production}`}
          {terr.capitalOf ? ` · ${terr.capitalOf} capital` : ""}
        </div>
      ) : (
        <div style={{ color: "#778", marginBottom: 2 }}>geometry-only territory (not in game data)</div>
      )}
      {owner && terr && !terr.water && (
        <div style={{ marginBottom: 6 }}>
          owner:{" "}
          <span style={{ color: colors[owner] ? `#${colors[owner]}` : "#e6e6e6", fontWeight: 600 }}>
            {owner}
          </span>
        </div>
      )}

      <div style={{ marginTop: 6, color: "#9fb6c9" }}>
        Units: <b style={{ color: "#e6e6e6" }}>{total}</b>
      </div>
      {total === 0 ? (
        <div style={{ color: "#778", marginTop: 2 }}>no units</div>
      ) : (
        [...byOwner.entries()].map(([owr, list]) => (
          <div key={owr} style={{ marginTop: 4 }}>
            <div style={{ color: colors[owr] ? `#${colors[owr]}` : "#ccc", fontWeight: 600 }}>{owr}</div>
            {list.map((s) => (
              <div
                key={s.type}
                style={{ display: "flex", justifyContent: "space-between", maxWidth: 240, paddingLeft: 8 }}
              >
                <span>{s.type}</span>
                <span>×{s.count}</span>
              </div>
            ))}
          </div>
        ))
      )}
    </div>
  );
}
