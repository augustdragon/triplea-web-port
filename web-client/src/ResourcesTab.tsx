import type { PlayerStat, ResourceCell } from "./types";

/**
 * The Resources info tab. PUs is the spendable economy, so it's the one real column (amount +
 * estimated next-turn income, with per-alliance total rows). Every other resource is a consumable
 * counter (kamikaze tokens, tech tokens) that's relevant only to whoever holds it and has no income,
 * so rather than give each its own mostly-empty column (as the base EconomyPanel does) we show it as
 * a small count-only chip on the rows that hold a nonzero amount. Data is live from the snapshot.
 */
const PUS = "PUs";

/** Glyph + short name for the consumable-token resources; unknown ones fall back to the raw name. */
const TOKENS: Record<string, { glyph: string; label: string }> = {
  SuicideAttackTokens: { glyph: "⚡", label: "kamikaze" },
  techTokens: { glyph: "🔬", label: "tech" },
};

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

  // Per-alliance PUs totals (amount + income), for alliances with >1 member.
  const allianceNames = [...new Set(stats.flatMap((s) => s.alliances))];
  const totals = allianceNames
    .map((name) => {
      const members = stats.filter((s) => s.alliances.includes(name));
      const cells = members.map(pusCell);
      return {
        name,
        count: members.length,
        amount: sum(cells, "amount"),
        income: sum(cells, "income"),
      };
    })
    .filter((t) => t.count > 1);

  // Which token resources actually appear (held by someone), for the legend.
  const tokensPresent = [
    ...new Set(stats.flatMap((s) => tokensOf(s).map((c) => c.name))),
  ];

  return (
    <div>
      <table style={{ borderCollapse: "collapse", maxWidth: 520, fontSize: 13 }}>
        <thead>
          <tr style={{ color: "#9fb6c9" }}>
            <th style={{ textAlign: "left", padding: "2px 10px 6px 2px" }}>Player</th>
            <th style={{ textAlign: "right", padding: "2px 10px 6px 0" }}>PUs</th>
            <th style={{ textAlign: "left", padding: "2px 10px 6px 0" }} />
          </tr>
        </thead>
        <tbody>
          {stats.map((s) => (
            <tr key={s.player}>
              <td
                style={{
                  padding: "2px 10px 2px 2px",
                  color: colors[s.player] ? `#${colors[s.player]}` : "#e6e6e6",
                  fontWeight: 600,
                }}
              >
                {s.player}
              </td>
              <td style={{ textAlign: "right", padding: "2px 10px 2px 0" }}>{fmtPus(pusCell(s))}</td>
              <td style={{ padding: "2px 10px 2px 0" }}>
                {tokensOf(s).map((c) => (
                  <Chip key={c.name} cell={c} />
                ))}
              </td>
            </tr>
          ))}
          {totals.map((t) => (
            <tr key={t.name} style={{ color: "#cfe0ee", fontStyle: "italic" }}>
              <td style={{ padding: "6px 10px 2px 2px", borderTop: "1px solid #3a4654" }}>{t.name}</td>
              <td style={{ textAlign: "right", padding: "6px 10px 2px 0", borderTop: "1px solid #3a4654" }}>
                {fmtAmountIncome(t.amount, t.income)}
              </td>
              <td style={{ borderTop: "1px solid #3a4654" }} />
            </tr>
          ))}
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
        marginRight: 6,
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

function sum(cells: (ResourceCell | undefined)[], key: "amount" | "income"): number {
  return cells.reduce((acc, c) => acc + (c ? c[key] : 0), 0);
}
