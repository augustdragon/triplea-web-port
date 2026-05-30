import type { PlayerStat } from "./types";

/**
 * The Players info tab: the base game's StatPanel as a table — one row per power with PUs,
 * Production, Units, TUV (total unit value) and VC (victory cities), plus a total row for each
 * alliance that has more than one member. Player names are tinted by faction color. Data comes from
 * the live StateSnapshot, so it updates as the game advances.
 */
const COLUMNS: { key: keyof PlayerStat; label: string }[] = [
  { key: "pus", label: "PUs" },
  { key: "production", label: "Production" },
  { key: "units", label: "Units" },
  { key: "tuv", label: "TUV" },
  { key: "victoryCities", label: "VC" },
];

export function PlayersTab({
  stats,
  colors,
}: {
  stats: PlayerStat[];
  colors: Record<string, string>;
}) {
  if (!stats || stats.length === 0) {
    return <div style={{ color: "#778", padding: "8px 2px" }}>Waiting for game state…</div>;
  }

  // One total row per alliance with >1 member (e.g. the Allies); singletons add no information.
  const allianceNames = [...new Set(stats.flatMap((s) => s.alliances))];
  const totals = allianceNames
    .map((name) => {
      const members = stats.filter((s) => s.alliances.includes(name));
      return {
        name,
        count: members.length,
        pus: sum(members, "pus"),
        production: sum(members, "production"),
        units: sum(members, "units"),
        tuv: sum(members, "tuv"),
        victoryCities: sum(members, "victoryCities"),
      };
    })
    .filter((t) => t.count > 1);

  return (
    <table style={{ borderCollapse: "collapse", width: "100%", maxWidth: 640, fontSize: 13 }}>
      <thead>
        <tr style={{ color: "#9fb6c9", textAlign: "right" }}>
          <th style={{ textAlign: "left", padding: "2px 10px 6px 2px" }}>Player</th>
          {COLUMNS.map((c) => (
            <th key={c.key} style={{ padding: "2px 10px 6px 0" }}>
              {c.label}
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
            {COLUMNS.map((c) => (
              <td key={c.key} style={{ padding: "2px 10px 2px 0" }}>
                {s[c.key] as number}
              </td>
            ))}
          </tr>
        ))}
        {totals.map((t) => (
          <tr key={t.name} style={{ textAlign: "right", color: "#cfe0ee", fontStyle: "italic" }}>
            <td style={{ textAlign: "left", padding: "6px 10px 2px 2px", borderTop: "1px solid #3a4654" }}>
              {t.name}
            </td>
            {COLUMNS.map((c) => (
              <td key={c.key} style={{ padding: "6px 10px 2px 0", borderTop: "1px solid #3a4654" }}>
                {t[c.key as keyof typeof t] as number}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function sum(rows: PlayerStat[], key: keyof PlayerStat): number {
  return rows.reduce((acc, r) => acc + (r[key] as number), 0);
}
