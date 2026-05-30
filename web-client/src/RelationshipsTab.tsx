import type { StateSnapshot } from "./types";

/**
 * The Relationships info tab: the inter-faction relationship grid (the web analog of the base game's
 * relationships view). Rows and columns are the powers; each cell shows how the row player relates to
 * the column player — colored by category (war = red, allied = green, neutral = grey) and labelled
 * with the engine type name (e.g. "Neutrality", "Custodianship"). The matrix is symmetric, so it
 * reads the same across the diagonal. Data comes straight from the latest snapshot, so it reflects
 * declarations of war as they happen. (Previously a modal opened from a sidebar button.)
 */
export function RelationshipsTab({
  snapshot,
  colors,
}: {
  snapshot: StateSnapshot | null;
  colors: Record<string, string>;
}) {
  const players = snapshot?.players ?? [];
  const rel = snapshot?.relationships ?? {};
  if (players.length === 0) {
    return <div style={{ color: "#778", padding: "8px 2px" }}>Waiting for game state…</div>;
  }

  return (
    <div data-testid="relationships-grid">
      <div style={{ color: "#9fb6c9", fontSize: 12, marginBottom: 10 }}>
        How each power (row) relates to each other (column).
      </div>

      <div style={{ overflowX: "auto" }}>
        <table style={{ borderCollapse: "collapse", fontSize: 12 }}>
          <thead>
            <tr>
              <th style={cornerCell} />
              {players.map((p) => (
                <th key={p} style={headCell}>
                  <span style={{ ...swatch, background: colors[p] ? `#${colors[p]}` : "#888" }} />
                  {p}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {players.map((row) => (
              <tr key={row}>
                <th style={rowHeadCell}>
                  <span style={{ ...swatch, background: colors[row] ? `#${colors[row]}` : "#888" }} />
                  {row}
                </th>
                {players.map((col) => {
                  if (row === col) {
                    return (
                      <td key={col} style={{ ...cell, background: "#222a33", color: "#556" }}>
                        —
                      </td>
                    );
                  }
                  const c = rel[row]?.[col];
                  const bg = c ? CATEGORY_BG[c.category] ?? "#3a4654" : "#2a323c";
                  return (
                    <td
                      key={col}
                      style={{ ...cell, background: bg }}
                      title={c?.type ?? ""}
                      data-testid={`rel-${row}-${col}`}
                    >
                      {c?.type ?? "?"}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div style={{ display: "flex", gap: 14, marginTop: 12, fontSize: 12 }}>
        <Legend label="War" color={CATEGORY_BG.war} />
        <Legend label="Allied" color={CATEGORY_BG.allied} />
        <Legend label="Neutral" color={CATEGORY_BG.neutral} />
      </div>
    </div>
  );
}

function Legend({ label, color }: { label: string; color: string }) {
  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 5, color: "#cdd6df" }}>
      <span style={{ width: 14, height: 14, borderRadius: 3, background: color }} />
      {label}
    </span>
  );
}

const CATEGORY_BG: Record<string, string> = {
  war: "#7a2f2f",
  allied: "#2f6a3a",
  neutral: "#46505c",
};
const cell: React.CSSProperties = {
  border: "1px solid #2a323c",
  padding: "5px 8px",
  textAlign: "center",
  whiteSpace: "nowrap",
};
const headCell: React.CSSProperties = {
  border: "1px solid #2a323c",
  padding: "5px 8px",
  background: "#1b232d",
  color: "#cdd6df",
  whiteSpace: "nowrap",
};
const rowHeadCell: React.CSSProperties = { ...headCell, textAlign: "left" };
const cornerCell: React.CSSProperties = { border: "1px solid #2a323c", background: "#1b232d" };
const swatch: React.CSSProperties = {
  display: "inline-block",
  width: 10,
  height: 10,
  borderRadius: 2,
  marginRight: 5,
  verticalAlign: "middle",
};
