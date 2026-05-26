import { useMemo, useState } from "react";
import type { PurchaseRequest } from "./types";

/**
 * The purchase decision panel. Lets the human pick how many of each production rule to buy, shows
 * the running cost against the PU budget, and submits the choice (rule name -> count) back to the
 * server. The engine's purchase delegate validates it; if rejected, the request comes back with an
 * `error` and the panel re-renders so the player can adjust.
 */
export function PurchasePanel({
  request,
  onSubmit,
}: {
  request: PurchaseRequest;
  onSubmit: (choices: Record<string, number>) => void;
}) {
  const [counts, setCounts] = useState<Record<string, number>>({});

  const spent = useMemo(
    () =>
      request.options.reduce((sum, o) => sum + (counts[o.name] ?? 0) * o.cost, 0),
    [counts, request.options],
  );
  const remaining = request.pusAvailable - spent;
  const overBudget = remaining < 0;

  function bump(name: string, delta: number) {
    setCounts((c) => ({ ...c, [name]: Math.max(0, (c[name] ?? 0) + delta) }));
  }

  return (
    <div style={{ padding: "10px 0" }}>
      <div style={{ fontWeight: "bold", fontSize: 15, marginBottom: 2 }}>
        {request.bid ? "Bid purchase" : "Purchase"} — {request.player}
      </div>
      <div style={{ marginBottom: 8 }}>
        Budget: <b>{request.pusAvailable}</b> PUs · spent {spent} ·{" "}
        <b style={{ color: overBudget ? "#f88" : "#7c7" }}>{remaining}</b> left
      </div>
      {request.error && (
        <div
          style={{
            background: "rgba(150,40,40,0.4)",
            border: "1px solid #a55",
            borderRadius: 4,
            padding: "4px 6px",
            marginBottom: 8,
            color: "#fbb",
          }}
        >
          Rejected: {request.error}
        </div>
      )}

      <div style={{ display: "flex", flexDirection: "column", gap: 3 }}>
        {request.options.map((o) => {
          const n = counts[o.name] ?? 0;
          return (
            <div
              key={o.name}
              style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
                padding: "2px 4px",
                background: n > 0 ? "rgba(120,150,90,0.18)" : "transparent",
                borderRadius: 3,
              }}
            >
              <span style={{ flex: 1 }}>
                {o.produces || o.name}
                {o.quantity > 1 ? ` ×${o.quantity}` : ""}{" "}
                <span style={{ color: "#9fb6c9" }}>({o.cost})</span>
              </span>
              <button onClick={() => bump(o.name, -1)} style={btn} disabled={n === 0}>
                −
              </button>
              <span style={{ width: 24, textAlign: "center" }}>{n}</span>
              <button onClick={() => bump(o.name, +1)} style={btn}>
                +
              </button>
            </div>
          );
        })}
      </div>

      <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
        <button
          onClick={() => onSubmit(counts)}
          disabled={overBudget}
          style={{ ...primaryBtn, opacity: overBudget ? 0.5 : 1 }}
        >
          Buy
        </button>
        <button onClick={() => onSubmit({})} style={secondaryBtn}>
          Buy nothing
        </button>
      </div>
    </div>
  );
}

const btn: React.CSSProperties = {
  width: 24,
  height: 22,
  background: "#33424f",
  color: "#e6e6e6",
  border: "1px solid #556",
  borderRadius: 3,
  cursor: "pointer",
};
const primaryBtn: React.CSSProperties = {
  flex: 1,
  padding: "6px 0",
  background: "#3a6a3a",
  color: "#fff",
  border: "1px solid #5a8a5a",
  borderRadius: 4,
  cursor: "pointer",
  fontWeight: "bold",
};
const secondaryBtn: React.CSSProperties = {
  flex: 1,
  padding: "6px 0",
  background: "#444",
  color: "#ddd",
  border: "1px solid #666",
  borderRadius: 4,
  cursor: "pointer",
};
