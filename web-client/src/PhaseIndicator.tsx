import { PHASES, phaseKeyOf } from "./phase";

/**
 * Shows the canonical turn phases with the current one highlighted, so the player always knows where
 * they are. Phases that aren't interactive yet still light up as the engine passes through them.
 */
export function PhaseIndicator({ step }: { step?: string | null }) {
  const current = phaseKeyOf(step);
  return (
    <div style={{ padding: "8px 12px", borderBottom: "1px solid #3a4654" }}>
      <div
        style={{
          fontSize: 10,
          color: "#778",
          marginBottom: 4,
          textTransform: "uppercase",
          letterSpacing: 0.6,
        }}
      >
        Phase
      </div>
      <div style={{ display: "flex", flexDirection: "column", gap: 1 }}>
        {PHASES.map((p) => {
          const active = p.key === current;
          return (
            <div
              key={p.key}
              style={{
                display: "flex",
                alignItems: "center",
                gap: 6,
                padding: "3px 6px",
                borderRadius: 4,
                background: active ? "rgba(120,150,90,0.28)" : "transparent",
                color: active ? "#fff" : "#8aa0b0",
                fontWeight: active ? "bold" : "normal",
              }}
            >
              <span style={{ width: 9, color: "#cdb98a" }}>{active ? "▶" : ""}</span>
              <span>{p.label}</span>
            </div>
          );
        })}
      </div>
      {current === null && step && (
        <div style={{ marginTop: 4, fontSize: 11, color: "#9fb6c9" }}>Step: {step}</div>
      )}
    </div>
  );
}
