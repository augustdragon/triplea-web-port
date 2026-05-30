import type { ReactNode } from "react";
import type { MapGeometry, ObjectiveItem, StateSnapshot, UnitStack } from "./types";
import { PlayersTab } from "./PlayersTab";
import { ResourcesTab } from "./ResourcesTab";
import { TerritoryTab } from "./TerritoryTab";
import { NotesTab } from "./NotesTab";
import { RelationshipsTab } from "./RelationshipsTab";
import { ObjectivesTab } from "./ObjectivesTab";

/** The dock's tabs, mirroring the base game's right-hand tabbed pane (TripleAFrame). */
export const DOCK_TABS = [
  "Actions",
  "Players",
  "Resources",
  "Relationships",
  "Objectives",
  "Notes",
  "Territory",
] as const;
export type DockTab = (typeof DOCK_TABS)[number];

/**
 * The bottom tab dock: a full-width bar (left of the sidebar) holding the game's information panels
 * as tabs, mirroring the base game's right-hand `JTabbedPane` but laid out horizontally for more
 * room. "Actions" holds the active decision panel (the old bottom action bar); the rest are
 * information views filled in over Phase 3h steps 2–5. Collapsible so the map can reclaim the space.
 */
export function BottomDock({
  activeTab,
  onTabChange,
  collapsed,
  onToggleCollapsed,
  hasRequest,
  actionsContent,
  snapshot,
  geometry,
  owners,
  units,
  colors,
  selectedTerritory,
  notesHtml,
  objectives,
  sidebarWidth,
}: {
  activeTab: DockTab;
  onTabChange: (tab: DockTab) => void;
  collapsed: boolean;
  onToggleCollapsed: () => void;
  /** Whether a decision is pending — badges the Actions tab. */
  hasRequest: boolean;
  /** The active decision panel (or an idle message) shown under the Actions tab. */
  actionsContent: ReactNode;
  /** Live game state feeding the information tabs. */
  snapshot: StateSnapshot | null;
  /** Map geometry (territory type/production/capital), for the Territory tab. */
  geometry: MapGeometry;
  /** Current ownership (live or initial), for the Territory tab. */
  owners: Record<string, string>;
  /** Units by territory (live), for the Territory tab. */
  units: Record<string, UnitStack[]>;
  /** Faction colors (hex, no '#') for tinting player names. */
  colors: Record<string, string>;
  /** The territory last clicked on the map, shown in the Territory tab. */
  selectedTerritory: string | null;
  /** The game's notes HTML (from the map), shown in the Notes tab. */
  notesHtml: string;
  /** National objectives + statuses (from the server), shown in the Objectives tab. */
  objectives: ObjectiveItem[];
  sidebarWidth: number;
}) {
  return (
    <div
      style={{
        position: "fixed",
        left: 0,
        right: sidebarWidth,
        bottom: 0,
        background: "rgba(16,22,30,0.97)",
        borderTop: "1px solid #3a4654",
        boxShadow: "0 -4px 24px rgba(0,0,0,0.4)",
        color: "#e6e6e6",
        fontFamily: "sans-serif",
        fontSize: 13,
        zIndex: 90,
        display: "flex",
        flexDirection: "column",
      }}
    >
      {/* Tab bar */}
      <div style={{ display: "flex", alignItems: "stretch", borderBottom: collapsed ? "none" : "1px solid #2a323c" }}>
        {DOCK_TABS.map((tab) => {
          const active = tab === activeTab && !collapsed;
          return (
            <button
              key={tab}
              onClick={() => {
                onTabChange(tab);
                if (collapsed) onToggleCollapsed();
              }}
              style={{
                background: active ? "rgba(44,58,74,0.9)" : "transparent",
                color: active ? "#fff" : "#9fb6c9",
                border: "none",
                borderRight: "1px solid #2a323c",
                borderBottom: active ? "2px solid #5a9bd4" : "2px solid transparent",
                padding: "7px 14px",
                fontSize: 13,
                fontWeight: active ? 600 : 400,
                cursor: "pointer",
                whiteSpace: "nowrap",
              }}
            >
              {tab}
              {tab === "Actions" && hasRequest && (
                <span title="action required" style={{ marginLeft: 6, color: "#ffd54a" }}>
                  ●
                </span>
              )}
            </button>
          );
        })}
        <div style={{ flex: 1 }} />
        <button
          onClick={onToggleCollapsed}
          title={collapsed ? "Expand panel" : "Collapse panel"}
          style={{
            background: "transparent",
            color: "#9fb6c9",
            border: "none",
            padding: "7px 14px",
            fontSize: 14,
            cursor: "pointer",
          }}
        >
          {collapsed ? "▲" : "▼"}
        </button>
      </div>

      {/* Content — a fixed height so the pane doesn't jump as you switch tabs; scrolls if taller. */}
      {!collapsed && (
        <div style={{ height: "40vh", overflowY: "auto", padding: "10px 16px" }}>
          {activeTab === "Actions" ? (
            actionsContent
          ) : activeTab === "Players" ? (
            <PlayersTab stats={snapshot?.playerStats ?? []} colors={colors} />
          ) : activeTab === "Resources" ? (
            <ResourcesTab stats={snapshot?.playerStats ?? []} colors={colors} />
          ) : activeTab === "Relationships" ? (
            <RelationshipsTab snapshot={snapshot} colors={colors} />
          ) : activeTab === "Objectives" ? (
            <ObjectivesTab items={objectives} colors={colors} />
          ) : activeTab === "Territory" ? (
            <TerritoryTab
              geometry={geometry}
              owners={owners}
              units={units}
              colors={colors}
              selected={selectedTerritory}
            />
          ) : activeTab === "Notes" ? (
            <NotesTab html={notesHtml} />
          ) : null}
        </div>
      )}
    </div>
  );
}
