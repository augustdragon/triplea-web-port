import type { PlayerStat, ResourceCell } from "./types";
import { groupByAlliance } from "./playerGroups";

/**
 * The Players info tab: the base game's StatPanel and EconomyPanel merged into one table — one row
 * per power with PUs (amount + estimated next-turn income), Production, Units, TUV (total unit value)
 * and VC (victory cities). Consumable token resources (kamikaze/tech) ride as a chip beside the name
 * of whoever holds them, with a legend underneath; this replaces the old separate Resources tab,
 * whose only non-redundant data was that income figure and those chips.
 *
 * Rows are grouped by alliance with a muted section header (Axis / Allies), and the passive minor
 * powers collapse into a single "Other" group of one-line rows. An alliance with more than one
 * member gets an accounting-style subtotal: a flush-left "total" label and a rule above the figures.
 * Player names are tinted by faction color. Data is live from the StateSnapshot.
 */

/** The plain numeric columns (PUs is rendered specially to carry income + token chips). */
const NUM_COLUMNS: { key: keyof PlayerStat; label: string }[] = [
  { key: "production", label: "Prod" },
  { key: "units", label: "Units" },
  { key: "tuv", label: "TUV" },
  { key: "victoryCities", label: "VC" },
];

const PUS = "PUs";

/** Glyph + short name for the consumable-token resources; unknown ones fall back to the raw name. */
const TOKENS: Record<string, { glyph: string; label: string }> = {
  SuicideAttackTokens: { glyph: "⚡", label: "kamikaze" },
  techTokens: { glyph: "🔬", label: "tech" },
};

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
  const span = NUM_COLUMNS.length + 2; // name + PUs + numeric columns
  // Which token resources actually appear (held by someone), for the legend.
  const tokensPresent = [...new Set(stats.flatMap((s) => tokensOf(s).map((c) => c.name)))];

  return (
    <div>
      <table style={{ borderCollapse: "collapse", width: "100%", maxWidth: 660, fontSize: 13 }}>
        <thead>
          <tr style={{ color: "#9fb6c9", textAlign: "right" }}>
            <th style={{ textAlign: "left", padding: "2px 10px 6px 2px" }}>Player</th>
            <th style={{ padding: "2px 10px 6px 0" }}>IPCs</th>
            {NUM_COLUMNS.map((c) => (
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
                    whiteSpace: "nowrap",
                  }}
                >
                  {s.player}
                  {tokensOf(s).map((c) => (
                    <Chip key={c.name} cell={c} />
                  ))}
                </td>
                <td style={{ padding: "2px 10px 2px 0", whiteSpace: "nowrap" }}>{fmtPus(pusCell(s))}</td>
                {NUM_COLUMNS.map((c) => (
                  <td key={c.key} style={{ padding: "2px 10px 2px 0" }}>
                    {s[c.key] as number}
                  </td>
                ))}
              </tr>
            )),
            // Accounting-style subtotal — only for a real alliance with more than one member.
            group.members.length > 1 && !group.passive ? (
              <tr key={`t-${group.alliance}`} style={{ textAlign: "right", color: "#cfe0ee", fontStyle: "italic" }}>
                <td style={{ textAlign: "left", padding: "4px 10px 2px 2px" }}>total</td>
                <td style={{ padding: "4px 10px 2px 0", borderTop: "1px solid #5a6b7a", whiteSpace: "nowrap" }}>
                  {fmtAmountIncome(
                    sumCells(group.members, "amount"),
                    sumCells(group.members, "income"),
                  )}
                </td>
                {NUM_COLUMNS.map((c) => (
                  <td key={c.key} style={{ padding: "4px 10px 2px 0", borderTop: "1px solid #5a6b7a" }}>
                    {sumField(group.members, c.key)}
                  </td>
                ))}
              </tr>
            ) : null,
          ])}
        </tbody>
      </table>
      {tokensPresent.length > 0 && (
        <div style={{ marginTop: 8, fontSize: 11, color: "#8aa" }}>
          {tokensPresent.map((name, i) => (
            <span key={name}>
              {i > 0 ? " · " : ""}
              {token(name).glyph} {token(name).label} tokens
            </span>
          ))}
        </div>
      )}
    </div>
  );
}

/** A count-only chip for a held token resource; the full resource name is the tooltip. */
function Chip({ cell }: { cell: ResourceCell }) {
  const t = token(cell.name);
  return (
    <span
      title={cell.name}
      style={{
        display: "inline-block",
        marginLeft: 6,
        padding: "1px 7px",
        borderRadius: 9,
        background: "#2c3a4a",
        border: "1px solid #46505c",
        color: "#e6eef5",
        fontSize: 12,
      }}
    >
      {t.glyph} {cell.amount}
    </span>
  );
}

function token(name: string): { glyph: string; label: string } {
  return TOKENS[name] ?? { glyph: "•", label: name };
}

function pusCell(s: PlayerStat): ResourceCell | undefined {
  return s.resources.find((c) => c.name === PUS);
}

/** Non-PU resources the player actually holds (nonzero) — the ones worth a chip. */
function tokensOf(s: PlayerStat): ResourceCell[] {
  return s.resources.filter((c) => c.name !== PUS && c.amount !== 0);
}

function fmtPus(cell: ResourceCell | undefined): string {
  return cell ? fmtAmountIncome(cell.amount, cell.income) : "—";
}

function fmtAmountIncome(amount: number, income: number): string {
  return `${amount} (${income >= 0 ? "+" : ""}${income})`;
}

/** Sum a PU-cell field (amount or income) across an alliance's members. */
function sumCells(rows: PlayerStat[], key: "amount" | "income"): number {
  return rows.reduce((acc, r) => {
    const cell = pusCell(r);
    return acc + (cell ? cell[key] : 0);
  }, 0);
}

function sumField(rows: PlayerStat[], key: keyof PlayerStat): number {
  return rows.reduce((acc, r) => acc + (r[key] as number), 0);
}
