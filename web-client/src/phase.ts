/**
 * Maps the engine's raw step name (e.g. "japaneseCombatMove", "japaneseNonCombatMove",
 * "japaneseBattle") to one of the canonical turn phases, so the UI can show where the player is —
 * even for phases that aren't interactive yet (they still light up as the engine passes through).
 */
export interface Phase {
  key: string;
  label: string;
}

export const PHASES: Phase[] = [
  { key: "purchase", label: "Purchase" },
  { key: "combatMove", label: "Combat Move" },
  { key: "battle", label: "Combat" },
  { key: "nonCombatMove", label: "Non-Combat Move" },
  { key: "place", label: "Place Units" },
  { key: "endTurn", label: "End of Turn" },
];

/** Which canonical phase a step belongs to, or null if it doesn't map cleanly. */
export function phaseKeyOf(step?: string | null): string | null {
  if (!step) return null;
  const s = step.toLowerCase();
  // Order matters: "noncombatmove" contains "combatmove".
  if (s.includes("noncombatmove")) return "nonCombatMove";
  if (s.includes("combatmove")) return "combatMove";
  if (s.includes("purchase") || s.includes("bid")) return "purchase";
  if (s.includes("battle")) return "battle";
  if (s.includes("place")) return "place";
  if (s.includes("endturn") || s.includes("politics") || s.includes("tech")) return "endTurn";
  return null;
}
