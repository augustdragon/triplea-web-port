import { useEffect, useState } from "react";
import type { MapGeometry } from "./types";
import { MapCanvas } from "./MapCanvas";

interface StateSnapshot {
  round: number;
  step: string;
  currentPlayer: string | null;
  owners: Record<string, string>;
}

// The spectator WebSocket server (see :game-web-server:runSpectator). Same host as the page,
// so it works over LAN/ZeroTier too.
const WS_URL = `ws://${location.hostname}:8080`;

export default function App() {
  const [geometry, setGeometry] = useState<MapGeometry | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [snapshot, setSnapshot] = useState<StateSnapshot | null>(null);
  const [wsStatus, setWsStatus] = useState("connecting…");

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
    ws.onopen = () => setWsStatus("live");
    ws.onmessage = (e) => setSnapshot(JSON.parse(e.data) as StateSnapshot);
    ws.onclose = () => setWsStatus("disconnected");
    ws.onerror = () => setWsStatus("error — is :game-web-server:runSpectator running?");
    return () => ws.close();
  }, []);

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

  // Live owners from the server when connected; otherwise the static initial ownership.
  const owners = snapshot?.owners ?? geometry.initialOwners ?? {};
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
      </div>
      <MapCanvas geometry={geometry} owners={owners} />
    </div>
  );
}
