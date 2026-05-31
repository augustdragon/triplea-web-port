import type { PlayerStat } from "./types";

export interface AllianceGroup {
  alliance: string;
  members: PlayerStat[];
  /**
   * True for the collapsed "Other" bucket of passive (optional) minor powers — the Players tab
   * renders one detail line per member and no subtotal, since these powers don't act together.
   */
  passive?: boolean;
}

/**
 * Group players for the Players info tab so each side reads as its own labelled section — e.g. Axis,
 * Allies, then a single "Other" group holding the passive minor powers (Pacific's Russians/French/
 * Dutch, which the engine flags as optional). Real alliances keep turn order; the Other group, if
 * any, is appended last. A non-passive player with no alliance falls under "Unaligned".
 */
export function groupByAlliance(stats: PlayerStat[]): AllianceGroup[] {
  const groups: AllianceGroup[] = [];
  let other: AllianceGroup | null = null;
  for (const s of stats) {
    if (s.passive) {
      if (!other) other = { alliance: "Other", members: [], passive: true };
      other.members.push(s);
      continue;
    }
    const alliance = s.alliances[0] ?? "Unaligned";
    let group = groups.find((g) => g.alliance === alliance);
    if (!group) {
      group = { alliance, members: [] };
      groups.push(group);
    }
    group.members.push(s);
  }
  if (other) groups.push(other);
  return groups;
}
