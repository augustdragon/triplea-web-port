import type { PlayerStat, ResourceCell } from "./types";

/**
 * The Resources info tab. PUs is the spendable economy, so it's the one real column (amount +
 * estimated next-turn income). Every other resource is a consumable counter (kamikaze tokens, tech
 * tokens) shown as a count-only chip on the rows that hold it (see the base game's EconomyPanel,
 * which instead gives each its own mostly-empty column).
 *
 * Rows are grouped by alliance with a section header, so the sides read clearly — e.g. Japan under
 * AXIS, the four Allied powers under ALLIES (with a group total), and the passive minors
 * (Russians/French/Dutch), which the game models as their own single-member alliances, each under
 * their own header rather than lumped in with the Allies. Data is live from the snapshot.
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

  // Group players by their (primary) alliance, preserving turn order — so groups come out Axis,
  // Allies, then the single-member minors, matching the order powers take their turns.
  const groups: { alliance: string; members: PlayerStat[] }[] = [];
  for (const s of stats) {
    const alliance = s.alliances[0] ?? "Unaligned";
    let group = groups.find((g) => g.alliance === alliance);
    if (!group) {
      group = { alliance, members: [] };
      groups.push(group);
    }
    group.members.push(s);
  }

  // Which token resources actually appear (held by someone), for the legend.
  const tokensPresent = [...new Set(stats.flatMap((s) => tokensOf(s).map((c) => c.name)))];

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
          {groups.map((group, gi) => {
            const total = group.members.map(pusCell);
            return [
              // Section header: the alliance name (a separator + visual cue for the side).
              <tr key={`h-${group.alliance}`}>
                <td
                  colSpan={3}
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
                <tr key={s.player}>
                  <td
                    style={{
                      padding: "2px 10px 2px 12px",
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
              )),
              // Group total — only meaningful when the alliance has more than one member.
              group.members.length > 1 ? (
                <tr key={`t-${group.alliance}`} style={{ color: "#cfe0ee", fontStyle: "italic" }}>
                  <td style={{ padding: "2px 10px 2px 12px" }}>total</td>
                  <td style={{ textAlign: "right", padding: "2px 10px 2px 0" }}>
                    {fmtAmountIncome(sum(total, "amount"), sum(total, "income"))}
                  </td>
                  <td />
                </tr>
              ) : null,
            ];
          })}
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
        marginLeft: 4,
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
