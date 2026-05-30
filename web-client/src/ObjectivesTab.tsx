import type { ObjectiveItem } from "./types";

/**
 * The Objectives info tab: the base game's ObjectivePanel — national objectives grouped by section
 * (nation), each with a satisfied/unsatisfied marker and its HTML description. The list and statuses
 * come from the server (evaluated read-only against the live game each step), pushed as an
 * `objectives` envelope. Sections are rendered in the order the server sends them.
 */
export function ObjectivesTab({
  items,
  colors,
}: {
  items: ObjectiveItem[];
  colors: Record<string, string>;
}) {
  if (!items || items.length === 0) {
    return <div style={{ color: "#778", padding: "8px 2px" }}>This game has no objectives.</div>;
  }

  // Group consecutively by section, preserving the server's order.
  const groups: { section: string; objectives: ObjectiveItem[] }[] = [];
  for (const item of items) {
    let group = groups.find((g) => g.section === item.section);
    if (!group) {
      group = { section: item.section, objectives: [] };
      groups.push(group);
    }
    group.objectives.push(item);
  }

  return (
    <div style={{ maxWidth: 760, fontSize: 13 }}>
      <style>{OBJECTIVES_CSS}</style>
      {groups.map((group) => (
        <div key={group.section} style={{ marginBottom: 12 }}>
          <div
            style={{
              fontWeight: 700,
              color: colors[group.section] ? `#${colors[group.section]}` : "#cfe0ee",
              borderBottom: "1px solid #3a4654",
              paddingBottom: 3,
              marginBottom: 5,
            }}
          >
            {group.section}
          </div>
          {group.objectives.map((o, i) => (
            <div key={i} style={{ display: "flex", gap: 8, alignItems: "baseline", padding: "2px 0" }}>
              <span
                title={o.satisfied ? "satisfied" : "not satisfied"}
                style={{
                  flex: "0 0 auto",
                  width: 16,
                  textAlign: "center",
                  fontWeight: 700,
                  color: o.satisfied ? "#7ec77e" : "#7a8694",
                }}
              >
                {o.satisfied ? "✓" : "○"}
              </span>
              <span
                className="objective-text"
                style={{ color: o.satisfied ? "#e6eef5" : "#9fb0bf" }}
                // Trusted local map content (same source as the Notes tab).
                dangerouslySetInnerHTML={{ __html: o.text }}
              />
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}

// Scoped so author HTML in the descriptions (e.g. <b>+5 PUs</b>) reads on the dark theme.
const OBJECTIVES_CSS = `
.objective-text * { background-color: transparent !important; }
.objective-text b, .objective-text strong { color: #ffffff; }
`;
