import { useEffect, useState } from "react";
import type { MapGeometry } from "./types";
import { MapCanvas } from "./MapCanvas";

export default function App() {
  const [geometry, setGeometry] = useState<MapGeometry | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetch("/geometry.json")
      .then((r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return r.json();
      })
      .then(setGeometry)
      .catch((e) => setError(String(e)));
  }, []);

  if (error) {
    return (
      <div style={{ color: "#f88", padding: 16, fontFamily: "sans-serif" }}>
        Failed to load /geometry.json: {error}
        <div style={{ color: "#aaa", marginTop: 8 }}>
          Generate it with: <code>:game-web-server:exportGeometry</code>, then copy to{" "}
          <code>web-client/public/geometry.json</code>.
        </div>
      </div>
    );
  }
  if (!geometry) {
    return <div style={{ color: "#ccc", padding: 16, fontFamily: "sans-serif" }}>Loading map…</div>;
  }

  const ownedCount = geometry.initialOwners ? Object.keys(geometry.initialOwners).length : 0;
  return (
    <div style={{ color: "#ccc", fontFamily: "sans-serif", padding: 8 }}>
      <div style={{ marginBottom: 8 }}>
        {geometry.territories.length} territories · {ownedCount} owned · {geometry.mapWidth}×
        {geometry.mapHeight}
      </div>
      <MapCanvas geometry={geometry} />
    </div>
  );
}
