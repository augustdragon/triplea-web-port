import { useState } from "react";
import type { PoliticsRequest } from "./types";

/**
 * The politics panel: the first phase of a turn, where the player may take political actions —
 * chiefly declarations of war. The server offers only the actions the engine deems legal right now
 * (e.g. Japan can declare on the USA only while still neutral with it).
 *
 * <p>Declarations are <i>staged</i>, not applied on click: clicking an action queues it (reversible
 * with its Undo button), and nothing reaches the engine until "End Politics Phase". That commits
 * the whole staged set at once — the engine has no political undo, so staging is the change-your-
 * mind window. Committing flips relationships (which reshape what moves are legal this turn —
 * declaring war makes the new enemy's territory attackable). If a staged action was already made
 * redundant by another (e.g. the combined "war on all Allies"), the server skips it and re-prompts
 * with a note. Use the Relationships button (sidebar header) to see the full current matrix.
 */
export function PoliticsPanel({
  request,
  onCommit,
}: {
  request: PoliticsRequest;
  onCommit: (names: string[]) => void;
}) {
  // The staged-but-not-yet-committed action names. Local: a fresh request (after commit) remounts
  // this with an empty queue.
  const [staged, setStaged] = useState<string[]>([]);
  const stagedSet = new Set(staged);
  const available = request.actions.filter((a) => !stagedSet.has(a.name));
  const byName = new Map(request.actions.map((a) => [a.name, a]));

  return (
    <div>
      {/* Header row: title + intro on the left, the End button on the right. */}
      <div
        style={{ display: "flex", alignItems: "center", gap: 16, justifyContent: "space-between" }}
      >
        <div>
          <span style={{ fontWeight: "bold", fontSize: 15 }}>Politics — {request.player}</span>
          <span style={{ color: "#9fb6c9", marginLeft: 10, fontSize: 12 }}>
            Stage declarations of war (or treaties); nothing is committed until you end the phase.
          </span>
        </div>
        <button onClick={() => onCommit(staged)} style={endBtn}>
          {staged.length > 0 ? `End phase & apply ${staged.length}` : "End Politics Phase"}
        </button>
      </div>

      {request.error && (
        <div
          style={{
            background: "rgba(150,40,40,0.4)",
            border: "1px solid #a55",
            borderRadius: 4,
            padding: "4px 6px",
            margin: "8px 0",
            color: "#fbb",
          }}
        >
          {request.error}
        </div>
      )}

      {/* Staged queue — horizontal chips, each removable. */}
      {staged.length > 0 && (
        <div style={{ marginTop: 10 }}>
          <div style={{ color: "#9fb6c9", fontSize: 12, marginBottom: 4 }}>
            Staged — applied when you end the phase:
          </div>
          <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
            {staged.map((name) => {
              const a = byName.get(name);
              return (
                <div key={name} style={stagedChip} title={a?.changes.join("\n")}>
                  <span style={{ color: "#ffd27a", fontSize: 13 }}>{a?.summary ?? name}</span>
                  <button
                    onClick={() => setStaged((s) => s.filter((n) => n !== name))}
                    style={undoBtn}
                  >
                    Undo
                  </button>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Available actions to stage — wrapping cards across the bar width. */}
      {available.length > 0 && (
        <div style={{ marginTop: 10 }}>
          <div style={{ color: "#9fb6c9", fontSize: 12, marginBottom: 4 }}>
            {staged.length > 0 ? "Add another (click to stage):" : "Available (click to stage):"}
          </div>
          <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
            {available.map((a) => (
              <button
                key={a.name}
                onClick={() => setStaged((s) => [...s, a.name])}
                style={actionCard}
                title={`${a.changes.join("\n")}\n\n(full picture in Relationships)`}
              >
                <span style={{ color: "#ffd27a", fontSize: 14, fontWeight: 600 }}>{a.summary}</span>
                {a.costPu > 0 && (
                  <span style={{ color: "#9fb6c9", fontSize: 11, marginTop: 3 }}>{a.costPu} PUs</span>
                )}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

const actionCard: React.CSSProperties = {
  display: "flex",
  flexDirection: "column",
  textAlign: "left",
  minWidth: 180,
  maxWidth: 280,
  padding: "8px 12px",
  background: "#3a2f4f",
  color: "#e6e6e6",
  border: "1px solid #6a5a8a",
  borderRadius: 5,
  cursor: "pointer",
};
const stagedChip: React.CSSProperties = {
  display: "flex",
  alignItems: "center",
  gap: 10,
  padding: "6px 10px",
  background: "rgba(122,47,47,0.35)",
  border: "1px solid #b35a5a",
  borderRadius: 5,
};
const undoBtn: React.CSSProperties = {
  padding: "3px 10px",
  background: "#444",
  color: "#ddd",
  border: "1px solid #666",
  borderRadius: 4,
  cursor: "pointer",
};
const endBtn: React.CSSProperties = {
  flexShrink: 0,
  padding: "8px 18px",
  background: "#3a5a7a",
  color: "#fff",
  border: "1px solid #5a7a9a",
  borderRadius: 5,
  cursor: "pointer",
  fontWeight: "bold",
};
