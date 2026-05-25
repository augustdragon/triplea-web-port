import { useEffect, useMemo, useRef, useState } from "react";
import type { MapGeometry, TerritoryGeometry, UnitStack, XyPoint } from "./types";

// Fixed canvas viewport; the map is fit into it initially, then pan/zoom navigates within.
const VIEW_W = 1500;
const VIEW_H = 920;
const WATER_COLOR = "#2f5d86";
const FALLBACK_COLOR = "#8a8a7a";
const MIN_SCALE = 0.05;
const MAX_SCALE = 6;

interface View {
  scale: number;
  offsetX: number;
  offsetY: number;
}

/** Fill color for a territory: blue for sea zones, owner's color for land, fallback otherwise. */
function fillColor(
  geometry: MapGeometry,
  owners: Record<string, string>,
  territory: TerritoryGeometry,
): string {
  if (territory.water) return WATER_COLOR;
  const owner = owners[territory.name];
  const hex = owner ? geometry.playerColors[owner] : undefined;
  return hex ? `#${hex}` : FALLBACK_COLOR;
}

/** Ray-casting point-in-polygon test (polygon is a ring of map-space points). */
function pointInPolygon(px: number, py: number, ring: XyPoint[]): boolean {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const xi = ring[i].x;
    const yi = ring[i].y;
    const xj = ring[j].x;
    const yj = ring[j].y;
    const intersects =
      yi > py !== yj > py && px < ((xj - xi) * (py - yi)) / (yj - yi) + xi;
    if (intersects) inside = !inside;
  }
  return inside;
}

function territoryAt(geometry: MapGeometry, mx: number, my: number): string | null {
  // Territories are drawn in array order, so later entries paint on top. Sea-zone polygons are
  // large and overlap coastal/island land, so hit-test in reverse (topmost first) — a click resolves
  // to the territory the user actually sees, not a sea zone beneath it. (Forward iteration left ~1/3
  // of land — essentially every Pacific island — unclickable, since the sea zone matched first.)
  const terrs = geometry.territories;
  for (let i = terrs.length - 1; i >= 0; i--) {
    const t = terrs[i];
    for (const poly of t.polygons) {
      if (pointInPolygon(mx, my, poly)) return t.name;
    }
  }
  return null;
}

export function MapCanvas({
  geometry,
  owners,
  units,
  onSelect,
  highlight,
}: {
  geometry: MapGeometry;
  owners: Record<string, string>;
  units: Record<string, UnitStack[]>;
  onSelect?: (territoryName: string | null) => void;
  /** Territories to outline as the in-progress move route (drawn in order, distinct color). */
  highlight?: string[];
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const dragRef = useRef<{ x: number; y: number; moved: boolean } | null>(null);
  const [view, setView] = useState<View>({ scale: 1, offsetX: 0, offsetY: 0 });
  const [hover, setHover] = useState<{ name: string; x: number; y: number } | null>(null);
  const [selected, setSelected] = useState<string | null>(null);

  const byName = useMemo(() => {
    const m = new Map<string, TerritoryGeometry>();
    for (const t of geometry.territories) m.set(t.name, t);
    return m;
  }, [geometry]);

  // Fit the whole map into the viewport whenever the map changes.
  useEffect(() => {
    const scale = Math.min(VIEW_W / geometry.mapWidth, VIEW_H / geometry.mapHeight);
    setView({
      scale,
      offsetX: (VIEW_W - geometry.mapWidth * scale) / 2,
      offsetY: (VIEW_H - geometry.mapHeight * scale) / 2,
    });
  }, [geometry]);

  // Redraw on any state that affects the picture.
  useEffect(() => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext("2d");
    if (!canvas || !ctx) return;

    const { scale, offsetX, offsetY } = view;
    const sx = (x: number) => x * scale + offsetX;
    const sy = (y: number) => y * scale + offsetY;

    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = "#1a2733";
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    // Territory polygons.
    const routeSet = new Set(highlight ?? []);
    ctx.lineWidth = 0.6;
    for (const t of geometry.territories) {
      ctx.fillStyle = fillColor(geometry, owners, t);
      const onRoute = routeSet.has(t.name);
      const highlighted = t.name === selected || t.name === hover?.name;
      ctx.strokeStyle = onRoute ? "#ff8c2a" : highlighted ? "#ffd54a" : "rgba(0,0,0,0.45)";
      ctx.lineWidth = onRoute ? 3 : highlighted ? 2 : 0.6;
      for (const poly of t.polygons) {
        ctx.beginPath();
        poly.forEach((p, i) => {
          const x = sx(p.x);
          const y = sy(p.y);
          if (i === 0) ctx.moveTo(x, y);
          else ctx.lineTo(x, y);
        });
        ctx.closePath();
        ctx.fill();
        ctx.stroke();
      }
    }

    // Capital markers (small star-ish dot) and unit stack counts at centers.
    for (const t of geometry.territories) {
      if (!t.center) continue;
      const cx = sx(t.center.x);
      const cy = sy(t.center.y);
      if (t.capitalOf) {
        ctx.fillStyle = "#fff3b0";
        ctx.strokeStyle = "#7a5c00";
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.arc(cx, cy - 9, 3.2, 0, Math.PI * 2);
        ctx.fill();
        ctx.stroke();
      }
      const stacks = units[t.name];
      if (stacks && stacks.length > 0) {
        const total = stacks.reduce((sum, s) => sum + s.count, 0);
        const label = String(total);
        ctx.font = "bold 11px sans-serif";
        const w = ctx.measureText(label).width + 6;
        ctx.fillStyle = "rgba(0,0,0,0.65)";
        ctx.fillRect(cx - w / 2, cy - 7, w, 14);
        ctx.fillStyle = "#fff";
        ctx.textAlign = "center";
        ctx.textBaseline = "middle";
        ctx.fillText(label, cx, cy);
      }
    }
  }, [geometry, owners, units, view, selected, hover, highlight]);

  // ---- Pointer interaction: drag to pan, wheel to zoom, click to select, hover to inspect. ----

  function canvasXy(e: React.PointerEvent | React.WheelEvent): { x: number; y: number } {
    const rect = canvasRef.current!.getBoundingClientRect();
    return { x: e.clientX - rect.left, y: e.clientY - rect.top };
  }

  function toMap(cx: number, cy: number): { x: number; y: number } {
    return { x: (cx - view.offsetX) / view.scale, y: (cy - view.offsetY) / view.scale };
  }

  function onPointerDown(e: React.PointerEvent) {
    const { x, y } = canvasXy(e);
    dragRef.current = { x, y, moved: false };
    canvasRef.current?.setPointerCapture(e.pointerId);
  }

  function onPointerMove(e: React.PointerEvent) {
    const { x, y } = canvasXy(e);
    const drag = dragRef.current;
    if (drag) {
      const dx = x - drag.x;
      const dy = y - drag.y;
      if (Math.abs(dx) > 2 || Math.abs(dy) > 2) drag.moved = true;
      drag.x = x;
      drag.y = y;
      setView((v) => ({ ...v, offsetX: v.offsetX + dx, offsetY: v.offsetY + dy }));
      return;
    }
    const m = toMap(x, y);
    const name = territoryAt(geometry, m.x, m.y);
    setHover(name ? { name, x, y } : null);
  }

  function onPointerUp(e: React.PointerEvent) {
    const drag = dragRef.current;
    dragRef.current = null;
    canvasRef.current?.releasePointerCapture(e.pointerId);
    if (drag && !drag.moved) {
      const { x, y } = canvasXy(e);
      const m = toMap(x, y);
      const name = territoryAt(geometry, m.x, m.y);
      setSelected(name);
      onSelect?.(name);
    }
  }

  function onWheel(e: React.WheelEvent) {
    const { x, y } = canvasXy(e);
    setView((v) => {
      const factor = e.deltaY < 0 ? 1.12 : 1 / 1.12;
      const scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, v.scale * factor));
      // Keep the map point under the cursor fixed while zooming.
      const wx = (x - v.offsetX) / v.scale;
      const wy = (y - v.offsetY) / v.scale;
      return { scale, offsetX: x - wx * scale, offsetY: y - wy * scale };
    });
  }

  const hovered = hover ? byName.get(hover.name) : undefined;

  return (
    <div style={{ position: "relative", width: VIEW_W, height: VIEW_H }}>
      <canvas
        ref={canvasRef}
        width={VIEW_W}
        height={VIEW_H}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onWheel={onWheel}
        style={{ border: "1px solid #444", display: "block", cursor: dragRef.current ? "grabbing" : "grab", touchAction: "none" }}
      />
      {hovered && hover && (
        <Tooltip
          territory={hovered}
          owner={owners[hovered.name]}
          stacks={units[hovered.name]}
          colors={geometry.playerColors}
          x={hover.x}
          y={hover.y}
        />
      )}
    </div>
  );
}

function Tooltip({
  territory,
  owner,
  stacks,
  colors,
  x,
  y,
}: {
  territory: TerritoryGeometry;
  owner: string | undefined;
  stacks: UnitStack[] | undefined;
  colors: Record<string, string>;
  x: number;
  y: number;
}) {
  return (
    <div
      style={{
        position: "absolute",
        left: Math.min(x + 14, VIEW_W - 230),
        top: Math.min(y + 14, VIEW_H - 160),
        width: 210,
        background: "rgba(20,28,36,0.96)",
        border: "1px solid #556",
        borderRadius: 4,
        padding: "6px 8px",
        color: "#e6e6e6",
        fontSize: 12,
        pointerEvents: "none",
        zIndex: 10,
      }}
    >
      <div style={{ fontWeight: "bold", marginBottom: 2 }}>{territory.name}</div>
      <div style={{ color: "#9fb6c9" }}>
        {territory.water ? "sea zone" : `land · PU ${territory.production}`}
        {territory.capitalOf ? ` · ${territory.capitalOf} capital` : ""}
      </div>
      {owner && !territory.water && (
        <div style={{ color: "#9fb6c9" }}>owner: {owner}</div>
      )}
      {stacks && stacks.length > 0 ? (
        <div style={{ marginTop: 4 }}>
          {stacks.map((s, i) => (
            <div key={i} style={{ display: "flex", justifyContent: "space-between" }}>
              <span style={{ color: colors[s.owner] ? `#${colors[s.owner]}` : "#ccc" }}>
                {s.type}
              </span>
              <span>×{s.count}</span>
            </div>
          ))}
        </div>
      ) : (
        <div style={{ marginTop: 4, color: "#778" }}>no units</div>
      )}
    </div>
  );
}
