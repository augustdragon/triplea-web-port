import { useEffect, useRef } from "react";
import type { MapGeometry } from "./types";

const MAX_W = 1400;
const MAX_H = 1000;
const FALLBACK_COLOR = "#888888";

/** Owner color for a territory: player color from the map, or a fallback for unowned/geometry-only. */
function ownerColor(geometry: MapGeometry, territoryName: string): string {
  const owner = geometry.initialOwners?.[territoryName];
  const hex = owner ? geometry.playerColors[owner] : undefined;
  return hex ? `#${hex}` : FALLBACK_COLOR;
}

export function MapCanvas({ geometry }: { geometry: MapGeometry }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext("2d");
    if (!canvas || !ctx) return;

    // Fit the full-resolution map into a sensible on-screen size, preserving aspect ratio.
    const scale = Math.min(MAX_W / geometry.mapWidth, MAX_H / geometry.mapHeight);
    canvas.width = Math.round(geometry.mapWidth * scale);
    canvas.height = Math.round(geometry.mapHeight * scale);

    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.lineWidth = 0.5;
    ctx.strokeStyle = "rgba(0,0,0,0.4)";

    for (const territory of geometry.territories) {
      ctx.fillStyle = ownerColor(geometry, territory.name);
      for (const polygon of territory.polygons) {
        ctx.beginPath();
        polygon.forEach((p, i) => {
          const x = p.x * scale;
          const y = p.y * scale;
          if (i === 0) ctx.moveTo(x, y);
          else ctx.lineTo(x, y);
        });
        ctx.closePath();
        ctx.fill();
        ctx.stroke();
      }
    }

    // Territory centers as small dots, to confirm anchor placement.
    ctx.fillStyle = "rgba(0,0,0,0.7)";
    for (const territory of geometry.territories) {
      if (territory.center) {
        ctx.beginPath();
        ctx.arc(territory.center.x * scale, territory.center.y * scale, 1.2, 0, Math.PI * 2);
        ctx.fill();
      }
    }
  }, [geometry]);

  return <canvas ref={canvasRef} style={{ border: "1px solid #444", display: "block" }} />;
}
