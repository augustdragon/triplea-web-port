import { useEffect, useRef, useState } from "react";
import type {
  BattleEvent,
  CasualtyRequest,
  DecisionRequest,
  MapGeometry,
  MoveRequest,
  PlaceRequest,
  PoliticsRequest,
  PurchaseRequest,
  RetreatRequest,
  StateSnapshot,
} from "./types";
import { MapCanvas } from "./MapCanvas";
import { PoliticsPanel } from "./PoliticsPanel";
import { RelationshipsModal } from "./RelationshipsModal";
import { PurchasePanel } from "./PurchasePanel";
import { MovePanel } from "./MovePanel";
import { CasualtyPanel } from "./CasualtyPanel";
import { RetreatPanel } from "./RetreatPanel";
import { PlacePanel } from "./PlacePanel";
import { Sidebar } from "./Sidebar";

// The game WebSocket server (see :game-web-server:runSpectator / runPlayable). Same host as the
// page, so it works over LAN/ZeroTier too.
const WS_URL = `ws://${location.hostname}:8080`;

export default function App() {
  const [geometry, setGeometry] = useState<MapGeometry | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [snapshot, setSnapshot] = useState<StateSnapshot | null>(null);
  const [request, setRequest] = useState<DecisionRequest | null>(null);
  const [wsStatus, setWsStatus] = useState("connecting…");
  const [moveRoute, setMoveRoute] = useState<string[]>([]);
  const [moveUnits, setMoveUnits] = useState<Record<string, number>>({});
  const [placeTarget, setPlaceTarget] = useState<string | null>(null);
  const [placeUnits, setPlaceUnits] = useState<Record<string, number>>({});
  const [battleLog, setBattleLog] = useState<BattleEvent[]>([]);
  const [showRelationships, setShowRelationships] = useState(false);
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
        | ({ type: "request" } & DecisionRequest)
        | ({ type: "battle" } & BattleEvent);
      if (env.type === "state") {
        setSnapshot(env.snapshot);
      } else if (env.type === "request") {
        setRequest(env);
      } else if (env.type === "battle") {
        // One result per battle now, so keep a long history (still bounded for safety).
        setBattleLog((prev) => [...prev, env].slice(-500));
      }
    };
    ws.onclose = () => setWsStatus("disconnected");
    ws.onerror = () => setWsStatus("error — is a :game-web-server run task running?");
    return () => ws.close();
  }, []);

  function sendDecision(payload: object) {
    const ws = wsRef.current;
    if (ws && request) {
      ws.send(JSON.stringify({ type: "decision", requestId: request.requestId, payload }));
    }
    setRequest(null);
  }

  // Politics: commit the staged set of declarations (possibly empty) and end the phase. The server
  // applies each, skips any made redundant by another, and re-prompts only if some were skipped.
  function submitPolitics(names: string[]) {
    sendDecision({ commit: names });
  }

  // Purchase: one submission ends the phase.
  function submitPurchase(choices: Record<string, number>) {
    sendDecision({ choices });
  }

  // Move: click territories to build a route; submit one move (server loops for the next), or Done.
  // First click must be a territory with movable units; each further click must extend to a
  // neighbor (per the adjacency graph) and ignores re-clicking the current tail.
  function onTerritoryClick(name: string | null) {
    if (!name) return;
    if (request?.kind === "place") {
      // Any territory is a candidate target; the engine validates (factory, sea adjacency, …).
      // Toggle: clicking the current target again deselects it (no need for a Clear button).
      setPlaceTarget((prev) => (prev === name ? null : name));
      setPlaceUnits({});
      return;
    }
    if (request?.kind !== "move") return;
    const movable = (request.payload as MoveRequest).movableUnits;
    const connections = geometry?.connections ?? {};
    setMoveRoute((prev) => {
      // Toggle: clicking a territory already in the route deselects it — and, since a route is a
      // path, everything after it. Clicking the only/start territory clears the route entirely.
      const at = prev.indexOf(name);
      if (at !== -1) return prev.slice(0, at);
      if (prev.length === 0) return movable[name] ? [name] : prev;
      const tail = prev[prev.length - 1];
      if (!connections[tail]?.includes(name)) return prev; // only extend to an adjacent territory
      return [...prev, name];
    });
  }
  function resetMove() {
    setMoveRoute([]);
    setMoveUnits({});
  }
  function submitMove() {
    sendDecision({ route: moveRoute, units: moveUnits });
    resetMove();
  }
  function submitDone() {
    sendDecision({ done: true });
    resetMove();
  }
  function submitUndo(index: number) {
    sendDecision({ undo: index });
    resetMove();
  }
  function submitUndoAll() {
    sendDecision({ undoAll: true });
    resetMove();
  }
  function submitCasualties(killed: Record<string, number>) {
    sendDecision({ killed });
  }
  function submitRetreat(territory: string) {
    sendDecision({ retreatTo: territory });
  }
  function submitStay() {
    sendDecision({ remain: true });
  }
  function resetPlace() {
    setPlaceTarget(null);
    setPlaceUnits({});
  }
  function submitPlace() {
    sendDecision({ territory: placeTarget, units: placeUnits });
    resetPlace();
  }
  function submitPlaceDone() {
    sendDecision({ done: true });
    resetPlace();
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
    <div style={{ color: "#ccc", fontFamily: "sans-serif" }}>
      <MapCanvas
        geometry={geometry}
        owners={owners}
        units={units}
        onSelect={onTerritoryClick}
        highlight={
          request?.kind === "move"
            ? moveRoute
            : request?.kind === "place" && placeTarget
              ? [placeTarget]
              : undefined
        }
      />
      {showRelationships && snapshot && (
        <RelationshipsModal
          snapshot={snapshot}
          colors={geometry.playerColors}
          onClose={() => setShowRelationships(false)}
        />
      )}
      <Sidebar
        wsStatus={wsStatus}
        snapshot={snapshot}
        territoryCount={geometry.territories.length}
        events={battleLog}
        onShowRelationships={() => setShowRelationships(true)}
      />
      {/* Active decision panel: a bottom action bar spanning the width left of the sidebar. Only
          present while a decision is pending, so the map is unobstructed otherwise. */}
      {request && (
        <div style={actionBar}>
          <div style={{ maxWidth: request.kind === "politics" ? "none" : 620 }}>
            {request.kind === "politics" && (
              <PoliticsPanel
                request={request.payload as PoliticsRequest}
                onCommit={submitPolitics}
              />
            )}
            {request.kind === "purchase" && (
              <PurchasePanel
                request={request.payload as PurchaseRequest}
                onSubmit={submitPurchase}
              />
            )}
            {request.kind === "move" && (
              <MovePanel
                request={request.payload as MoveRequest}
                route={moveRoute}
                units={moveUnits}
                setUnits={setMoveUnits}
                onMove={submitMove}
                onClear={resetMove}
                onDone={submitDone}
                onUndo={submitUndo}
                onUndoAll={submitUndoAll}
              />
            )}
            {request.kind === "selectCasualties" && (
              <CasualtyPanel
                request={request.payload as CasualtyRequest}
                onSubmit={submitCasualties}
              />
            )}
            {request.kind === "retreat" && (
              <RetreatPanel
                request={request.payload as RetreatRequest}
                onRetreat={submitRetreat}
                onStay={submitStay}
              />
            )}
            {request.kind === "place" && (
              <PlacePanel
                request={request.payload as PlaceRequest}
                target={placeTarget}
                units={placeUnits}
                setUnits={setPlaceUnits}
                onPlace={submitPlace}
                onDone={submitPlaceDone}
              />
            )}
          </div>
        </div>
      )}
    </div>
  );
}

// The bottom action bar: pinned to the bottom, spanning from the left edge to the sidebar's left
// edge (the sidebar is a fixed 360px on the right). Scrolls internally if a panel is tall.
const actionBar: React.CSSProperties = {
  position: "fixed",
  left: 0,
  right: 360,
  bottom: 0,
  maxHeight: "42vh",
  overflowY: "auto",
  padding: "10px 16px",
  background: "rgba(16,22,30,0.97)",
  borderTop: "1px solid #3a4654",
  boxShadow: "0 -4px 24px rgba(0,0,0,0.4)",
  zIndex: 90,
};
