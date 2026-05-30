import type { PlayerStat } from "./types";
import { groupByAlliance } from "./playerGroups";

/**
 * The Players info tab: the base game's StatPanel as a table — one row per power with PUs,
 * Production, Units, TUV (total unit value) and VC (victory cities). Rows are grouped by alliance
 * with a muted section header (Axis / Allies / the passive minors), with a group total for any
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

  const groups = groupByAlliance(stats);
  const span = COLUMNS.length + 1;

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
        {groups.map((group, gi) => [
          // Section header: the alliance name — a separator and a cue for the side.
          <tr key={`h-${group.alliance}`}>
            <td
              colSpan={span}
              style={{
                padding: "8px 10px 2px 2px",
                borderTop: gi === 0 ? "none" : "1px solid #2a323c",
                color: "#8aa3b5",
                fontSize: 11,
                letterSpacing: 0.6,
                textTransform: "uppercase",
              }}
            >
              {group.alliance}
            </td>
          </tr>,
          ...group.members.map((s) => (
            <tr key={s.player} style={{ textAlign: "right" }}>
              <td
                style={{
                  textAlign: "left",
                  padding: "2px 10px 2px 12px",
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
          )),
          // Group total — only meaningful when the alliance has more than one member.
          group.members.length > 1 ? (
            <tr key={`t-${group.alliance}`} style={{ textAlign: "right", color: "#cfe0ee", fontStyle: "italic" }}>
              <td style={{ textAlign: "left", padding: "2px 10px 2px 12px" }}>total</td>
              {COLUMNS.map((c) => (
                <td key={c.key} style={{ padding: "2px 10px 2px 0" }}>
                  {sum(group.members, c.key)}
                </td>
              ))}
            </tr>
          ) : null,
        ])}
      </tbody>
    </table>
  );
}

function sum(rows: PlayerStat[], key: keyof PlayerStat): number {
  return rows.reduce((acc, r) => acc + (r[key] as number), 0);
}
