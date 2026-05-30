import type { PlayerStat } from "./types";

export interface AllianceGroup {
  alliance: string;
  members: PlayerStat[];
}

/**
 * Group players by their (primary) alliance, preserving the order powers appear in (turn order).
 * Shared by the Players and Resources info tabs so each side reads as its own labelled section —
 * e.g. Axis, Allies, then the passive minors (which the game models as their own single-member
 * alliances). A player with no alliance falls under "Unaligned".
 */
export function groupByAlliance(stats: PlayerStat[]): AllianceGroup[] {
  const groups: AllianceGroup[] = [];
  for (const s of stats) {
    const alliance = s.alliances[0] ?? "Unaligned";
    let group = groups.find((g) => g.alliance === alliance);
    if (!group) {
      group = { alliance, members: [] };
      groups.push(group);
    }
    group.members.push(s);
  }
  return groups;
}
