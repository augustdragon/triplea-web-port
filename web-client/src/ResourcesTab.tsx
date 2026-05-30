import type { PlayerStat, ResourceCell } from "./types";

/**
 * The Resources info tab: the base game's EconomyPanel as a table — one row per power, one column
 * per resource (every resource except VPs, in engine order), each cell showing the amount on hand
 * and the estimated next-turn income as `amount (+income)`. Adds a total row per alliance with >1
 * member. Player names are faction-tinted. Data is live from the StateSnapshot.
 */
export function ResourcesTab({
  stats,
  colors,
}: {
  stats: PlayerStat[];
  colors: Record<string, string>;
}) {
  if (!stats || stats.length === 0) {
    return <div style={{ color: "#778", padding: "8px 2px" }}>Waiting for game state…</div>;
  }

  const columns = stats[0].resources.map((r) => r.name);

  // Per-alliance totals (amount + income summed across members), for alliances with >1 member.
  const allianceNames = [...new Set(stats.flatMap((s) => s.alliances))];
  const totals = allianceNames
    .map((name) => {
      const members = stats.filter((s) => s.alliances.includes(name));
      return { name, count: members.length, cells: sumByResource(members, columns) };
    })
    .filter((t) => t.count > 1);

  return (
    <table style={{ borderCollapse: "collapse", width: "100%", maxWidth: 720, fontSize: 13 }}>
      <thead>
        <tr style={{ color: "#9fb6c9", textAlign: "right" }}>
          <th style={{ textAlign: "left", padding: "2px 10px 6px 2px" }}>Player</th>
          {columns.map((name) => (
            <th key={name} style={{ padding: "2px 10px 6px 0" }} title={name}>
              {name}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {stats.map((s) => (
          <tr key={s.player} style={{ textAlign: "right" }}>
            <td
              style={{
                textAlign: "left",
                padding: "2px 10px 2px 2px",
                color: colors[s.player] ? `#${colors[s.player]}` : "#e6e6e6",
                fontWeight: 600,
              }}
            >
              {s.player}
            </td>
            {s.resources.map((cell) => (
              <td key={cell.name} style={{ padding: "2px 10px 2px 0" }}>
                {formatCell(cell)}
              </td>
            ))}
          </tr>
        ))}
        {totals.map((t) => (
          <tr key={t.name} style={{ textAlign: "right", color: "#cfe0ee", fontStyle: "italic" }}>
            <td style={{ textAlign: "left", padding: "6px 10px 2px 2px", borderTop: "1px solid #3a4654" }}>
              {t.name}
            </td>
            {t.cells.map((cell) => (
              <td key={cell.name} style={{ padding: "6px 10px 2px 0", borderTop: "1px solid #3a4654" }}>
                {formatCell(cell)}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** "26 (+36)" — amount, then signed income in parentheses (matches EconomyPanel). */
function formatCell(cell: ResourceCell): string {
  const sign = cell.income >= 0 ? "+" : "";
  return `${cell.amount} (${sign}${cell.income})`;
}

function sumByResource(rows: PlayerStat[], columns: string[]): ResourceCell[] {
  return columns.map((name) => {
    let amount = 0;
    let income = 0;
    for (const r of rows) {
      const cell = r.resources.find((c) => c.name === name);
      if (cell) {
        amount += cell.amount;
        income += cell.income;
      }
    }
    return { name, amount, income };
  });
}
