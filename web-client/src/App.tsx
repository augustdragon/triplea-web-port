import { useEffect, useRef, useState } from "react";
import type {
  AirWarningRequest,
  BattleEvent,
  CasualtyRequest,
  DecisionRequest,
  MapGeometry,
  MoveRequest,
  ObjectiveItem,
  PlaceRequest,
  PoliticsRequest,
  PurchaseRequest,
  RetreatRequest,
  StateSnapshot,
} from "./types";
import { MapCanvas } from "./MapCanvas";
import { AirWarningPanel } from "./AirWarningPanel";
import { PoliticsPanel } from "./PoliticsPanel";
import { PurchasePanel } from "./PurchasePanel";
import { MovePanel } from "./MovePanel";
import { CasualtyPanel } from "./CasualtyPanel";
import { RetreatPanel } from "./RetreatPanel";
import { PlacePanel } from "./PlacePanel";
import { Sidebar } from "./Sidebar";
import { BottomDock, type DockTab } from "./BottomDock";

// The game WebSocket server (see :game-web-server:runSpectator / runPlayable). Same host as the
// page, so it works over LAN/ZeroTier too.
const WS_URL = `ws://${location.hostname}:8080`;

// Width of the fixed right sidebar; the bottom dock spans from the left edge to here.
const SIDEBAR_WIDTH = 300;

export default function App() {
  const [geometry, setGeometry] = useState<MapGeometry | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [snapshot, setSnapshot] = useState<StateSnapshot | null>(null);
  const [request, setRequest] = useState<DecisionRequest | null>(null);
  const [wsStatus, setWsStatus] = useState("connecting…");
  // A move is a source territory + a destination; the server finds the legal route between them.
  const [moveFrom, setMoveFrom] = useState<string | null>(null);
  const [moveTo, setMoveTo] = useState<string | null>(null);
  const [moveUnits, setMoveUnits] = useState<Record<string, number>>({});
  const [placeTarget, setPlaceTarget] = useState<string | null>(null);
  // The territory last clicked on the map, shown in the Territory info tab (independent of move/place).
  const [selectedTerritory, setSelectedTerritory] = useState<string | null>(null);
  const [placeUnits, setPlaceUnits] = useState<Record<string, number>>({});
  const [battleLog, setBattleLog] = useState<BattleEvent[]>([]);
  // The game's notes (HTML from the map's <notes> property), pushed once and cached by the server.
  const [notesHtml, setNotesHtml] = useState<string>("");
  // National objectives + their satisfied state, re-pushed by the server each step.
  const [objectives, setObjectives] = useState<ObjectiveItem[]>([]);
  const [activeTab, setActiveTab] = useState<DockTab>("Actions");
  const [dockCollapsed, setDockCollapsed] = useState(false);
  // A territory to pan the map to (from the air-can't-land warning pills); nonce re-triggers on
  // a repeat click of the same territory.
  const [airFocus, setAirFocus] = useState<{ name: string; nonce: number } | null>(null);
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
        | ({ type: "battle" } & BattleEvent)
        | { type: "notes"; html: string }
        | { type: "objectives"; items: ObjectiveItem[] };
      if (env.type === "state") {
        setSnapshot(env.snapshot);
      } else if (env.type === "request") {
        setRequest(env);
      } else if (env.type === "battle") {
        // One result per battle now, so keep a long history (still bounded for safety).
        setBattleLog((prev) => [...prev, env].slice(-500));
      } else if (env.type === "notes") {
        setNotesHtml(env.html);
      } else if (env.type === "objectives") {
        setObjectives(env.items);
      }
    };
    ws.onclose = () => setWsStatus("disconnected");
    ws.onerror = () => setWsStatus("error — is a :game-web-server run task running?");
    return () => ws.close();
  }, []);

  // When a decision arrives, surface it: jump to the Actions tab and expand the dock if collapsed.
  useEffect(() => {
    if (request) {
      setActiveTab("Actions");
      setDockCollapsed(false);
    }
  }, [request]);

  function sendDecision(payload: object) {
    const ws = wsRef.current;
    if (ws && request) {
      ws.send(JSON.stringify({ type: "decision", requestId: request.requestId, payload }));
    }
    setRequest(null);
  }

  // Reset the game on the server (a testing convenience — see GameController) and clear local UI
  // state. The server tears down the current game and starts a fresh one on the same socket, then
  // pushes new state + the opening decision; no page reload or server restart needed.
  function newGame() {
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "control", action: "newGame" }));
    }
    setRequest(null);
    setBattleLog([]);
    resetMove();
    resetPlace();
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

  // Move: click a territory with your units (the source), pick how many, then click any destination —
  // the server finds the best legal route and previews it. Clicking the source again clears.
  function onTerritoryClick(name: string | null) {
    // Track the click for the Territory info tab regardless of phase (null clears it).
    setSelectedTerritory(name);
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
    if (!moveFrom) {
      // First pick: a territory with movable units becomes the source.
      if (movable[name]) {
        setMoveFrom(name);
        setMoveTo(null);
        setMoveUnits({});
      }
      return;
    }
    if (name === moveFrom) {
      resetMove(); // click the source again to start over
      return;
    }
    // Any other territory is the destination — ask the server to preview the route there.
    setMoveTo(name);
    requestPreview(moveFrom, name, moveUnits);
  }
  // Ask the server to compute (not execute) the best legal route, which comes back as the next move
  // request's `preview`. Does NOT clear the pending request — the server re-prompts the move.
  function requestPreview(from: string, to: string, unitChoice: Record<string, number>) {
    const ws = wsRef.current;
    if (ws && request) {
      ws.send(
        JSON.stringify({
          type: "decision",
          requestId: request.requestId,
          payload: { previewRoute: { from, to, units: unitChoice } },
        }),
      );
    }
  }
  // Unit picks changed: keep them, and refresh the route preview if a destination is set (the legal
  // route can depend on which units move — land vs sea route, movement range).
  function changeMoveUnits(unitChoice: Record<string, number>) {
    setMoveUnits(unitChoice);
    if (moveFrom && moveTo) requestPreview(moveFrom, moveTo, unitChoice);
  }
  function resetMove() {
    setMoveFrom(null);
    setMoveTo(null);
    setMoveUnits({});
  }
  function submitMove() {
    if (moveFrom && moveTo) {
      sendDecision({ from: moveFrom, to: moveTo, units: moveUnits });
      resetMove();
    }
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
  // Air-can't-land warning: end the phase and lose the stranded aircraft, or go back to moving.
  function submitAirEndAnyway() {
    setAirFocus(null);
    sendDecision({ endAnyway: true });
  }
  function submitAirKeepMoving() {
    setAirFocus(null);
    sendDecision({ endAnyway: false });
  }
  // Pan the map to an at-risk territory (clicked in the air warning); bump the nonce each click.
  function focusTerritory(name: string) {
    setAirFocus((f) => ({ name, nonce: (f?.nonce ?? 0) + 1 }));
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
  // The territories to outline during a move: the server's previewed route (when it matches the
  // current source/destination), else just the chosen source.
  const movePreview =
    request?.kind === "move" ? (request.payload as MoveRequest).preview : null;
  const movePath =
    movePreview?.route && movePreview.from === moveFrom && movePreview.to === moveTo
      ? movePreview.route
      : moveFrom
        ? [moveFrom]
        : [];
  return (
    <div style={{ color: "#ccc", fontFamily: "sans-serif" }}>
      <MapCanvas
        geometry={geometry}
        owners={owners}
        units={units}
        onSelect={onTerritoryClick}
        highlight={
          request?.kind === "move"
            ? movePath
            : request?.kind === "place" && placeTarget
              ? [placeTarget]
              : request?.kind === "airWarning" && airFocus
                ? [airFocus.name]
                : undefined
        }
        focus={request?.kind === "airWarning" ? airFocus : null}
      />
      <Sidebar
        wsStatus={wsStatus}
        snapshot={snapshot}
        geometry={geometry}
        owners={owners}
        territoryCount={geometry.territories.length}
        events={battleLog}
        width={SIDEBAR_WIDTH}
        onNewGame={newGame}
      />
      {/* The bottom tab dock: information panels + the active decision panel under "Actions". */}
      <BottomDock
        activeTab={activeTab}
        onTabChange={setActiveTab}
        collapsed={dockCollapsed}
        onToggleCollapsed={() => setDockCollapsed((c) => !c)}
        hasRequest={!!request}
        snapshot={snapshot}
        geometry={geometry}
        owners={owners}
        units={units}
        colors={geometry.playerColors}
        selectedTerritory={selectedTerritory}
        notesHtml={notesHtml}
        objectives={objectives}
        sidebarWidth={SIDEBAR_WIDTH}
        actionsContent={
          request ? (
            <div
              style={{
                maxWidth:
                  request.kind === "politics" || request.kind === "purchase" ? "none" : 620,
              }}
            >
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
                  from={moveFrom}
                  to={moveTo}
                  units={moveUnits}
                  setUnits={changeMoveUnits}
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
              {request.kind === "airWarning" && (
                <AirWarningPanel
                  request={request.payload as AirWarningRequest}
                  onEndAnyway={submitAirEndAnyway}
                  onKeepMoving={submitAirKeepMoving}
                  onSelect={focusTerritory}
                />
              )}
            </div>
          ) : (
            <div style={{ color: "#778", padding: "8px 2px" }}>No action required right now.</div>
          )
        }
      />
    </div>
  );
}
