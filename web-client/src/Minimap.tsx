import { useEffect, useRef } from "react";
import type { MapGeometry } from "./types";
import { fillColor } from "./MapCanvas";

/**
 * A small, static overview of the whole map, tinted by current ownership — the base game's
 * `ImageScrollerSmallView`, minus interactivity. It fits the entire map into a fixed-width box
 * (height derived from the map's aspect ratio). Deferred: the live viewport rectangle and
 * click-to-pan sync with the main canvas (Phase 3h "deferred" list).
 */
export function Minimap({
  geometry,
  owners,
  width = 276,
}: {
  geometry: MapGeometry;
  owners: Record<string, string>;
  width?: number;
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const scale = width / geometry.mapWidth;
  const height = Math.round(geometry.mapHeight * scale);

  useEffect(() => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext("2d");
    if (!canvas || !ctx) return;

    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = "#1a2733";
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    ctx.lineWidth = 0.3;
    ctx.strokeStyle = "rgba(0,0,0,0.4)";
    for (const t of geometry.territories) {
      ctx.fillStyle = fillColor(geometry, owners, t);
      for (const poly of t.polygons) {
        ctx.beginPath();
        poly.forEach((p, i) => {
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
  }, [geometry, owners, scale]);

  return (
    <canvas
      ref={canvasRef}
      width={width}
      height={height}
      style={{ display: "block", width, height, borderRadius: 3 }}
    />
  );
}
