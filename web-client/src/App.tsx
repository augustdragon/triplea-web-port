import { useEffect, useRef, useState } from "react";
import type { DecisionRequest, MapGeometry, StateSnapshot } from "./types";
import { MapCanvas } from "./MapCanvas";
import { PurchasePanel } from "./PurchasePanel";

// The game WebSocket server (see :game-web-server:runSpectator / runPlayable). Same host as the
// page, so it works over LAN/ZeroTier too.
const WS_URL = `ws://${location.hostname}:8080`;

export default function App() {
  const [geometry, setGeometry] = useState<MapGeometry | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [snapshot, setSnapshot] = useState<StateSnapshot | null>(null);
  const [request, setRequest] = useState<DecisionRequest | null>(null);
  const [wsStatus, setWsStatus] = useState("connecting…");
  const wsRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    fetch("/geometry.json")
      .then((r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return r.json();
      })
      .then(setGeometry)
      .catch((e) => setError(String(e)));
  }, []);

  useEffect(() => {
    const ws = new WebSocket(WS_URL);
    wsRef.current = ws;
    ws.onopen = () => setWsStatus("live");
    ws.onmessage = (e) => {
      const env = JSON.parse(e.data) as
        | { type: "state"; snapshot: StateSnapshot }
        | ({ type: "request" } & DecisionRequest);
      if (env.type === "state") {
        setSnapshot(env.snapshot);
      } else if (env.type === "request") {
        setRequest(env);
      }
    };
    ws.onclose = () => setWsStatus("disconnected");
    ws.onerror = () => setWsStatus("error — is a :game-web-server run task running?");
    return () => ws.close();
  }, []);

  function submitDecision(choices: Record<string, number>) {
    const ws = wsRef.current;
    if (ws && request) {
      ws.send(JSON.stringify({ type: "decision", requestId: request.requestId, payload: { choices } }));
    }
    setRequest(null);
  }

  if (error) {
    return (
      <div style={{ color: "#f88", padding: 16, fontFamily: "sans-serif" }}>
        Failed to load /geometry.json: {error}
      </div>
    );
  }
  if (!geometry) {
    return <div style={{ color: "#ccc", padding: 16, fontFamily: "sans-serif" }}>Loading map…</div>;
  }

  // Live owners/units from the server when connected; otherwise the static initial ownership.
  const owners = snapshot?.owners ?? geometry.initialOwners ?? {};
  const units = snapshot?.units ?? {};
  return (
    <div style={{ color: "#ccc", fontFamily: "sans-serif", padding: 8 }}>
      <div style={{ marginBottom: 8, display: "flex", gap: 16 }}>
        <span>
          WS: <b style={{ color: wsStatus === "live" ? "#7c7" : "#e88" }}>{wsStatus}</b>
        </span>
        <span>round: {snapshot?.round ?? "—"}</span>
        <span>step: {snapshot?.step ?? "—"}</span>
        <span>turn: {snapshot?.currentPlayer ?? "—"}</span>
        <span>{geometry.territories.length} territories</span>
        <span style={{ color: "#778" }}>drag = pan · wheel = zoom · click = select</span>
      </div>
      <MapCanvas geometry={geometry} owners={owners} units={units} />
      {request?.kind === "purchase" && (
        <PurchasePanel request={request.payload} onSubmit={submitDecision} />
      )}
    </div>
  );
}
