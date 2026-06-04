import { useEffect, useRef, useState } from "react";
import type { CSSProperties } from "react";
import { useParams } from "react-router-dom";
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
  SeatRoster,
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
import { SeatSelect } from "./SeatSelect";
import { WaitingRoom } from "./WaitingRoom";
import { GameOverScreen, type GameOverInfo } from "./GameOverScreen";
import { RejoinPrompt } from "./RejoinPrompt";

// Standalone fallback for the bare /game route (a manually-run game server). The lobby flow uses
// /game/:id, which fetches the real container endpoint from the control plane.
const LEGACY_WS_URL = `ws://${location.hostname}:8080`;

// Width of the fixed right sidebar; the bottom dock spans from the left edge to here.
const SIDEBAR_WIDTH = 300;

// The fixed Concede / Reclaim pill in the top-right of the running game view.
const seatActionBtn: CSSProperties = {
  position: "fixed",
  top: 8,
  right: SIDEBAR_WIDTH + 12,
  padding: "4px 12px",
  borderRadius: 14,
  fontSize: 12,
  cursor: "pointer",
  zIndex: 20,
};

// Seat memory: sessionStorage (per-tab) is primary — it keeps two tabs on distinct seats and
// survives a tab reload; localStorage is the fallback that survives a full browser close, so
// reopening the page can offer to re-claim the same seat.
function recalledSeat(): string | null {
  return sessionStorage.getItem("seat") ?? localStorage.getItem("seat");
}
function rememberSeat(seat: string): void {
  sessionStorage.setItem("seat", seat);
  localStorage.setItem("seat", seat);
}
function forgetSeat(): void {
  sessionStorage.removeItem("seat");
  localStorage.removeItem("seat");
}
function rememberName(name: string): void {
  sessionStorage.setItem("name", name);
  localStorage.setItem("name", name);
}

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
  // `mySeat` is the seat we've claimed THIS session (null until we claim/rejoin) — it drives the
  // in-game UI and routing. `recalledRef` is the seat we last held (from storage), used to silently
  // re-claim in setup and to default the running-phase rejoin prompt.
  const [roster, setRoster] = useState<SeatRoster | null>(null);
  const [mySeat, setMySeat] = useState<string | null>(null);
  const [myName, setMyName] = useState<string>(
    () => sessionStorage.getItem("name") ?? localStorage.getItem("name") ?? "",
  );
  const [spectating, setSpectating] = useState(false);
  // Lobby flow: whether we are the host (may start the game from the waiting room).
  const [isHost, setIsHost] = useState(false);
  // The seat the control plane assigned us (stable; stays ours through a turn-timer AI takeover so
  // we can reclaim it). Distinct from `mySeat` (the seat we're actively driving).
  const [assignedSeat, setAssignedSeat] = useState<string | null>(null);
  // Set when the game ends — drives the end screen and stops the reconnect loop.
  const [gameOver, setGameOver] = useState<GameOverInfo | null>(null);
  const gameOverRef = useRef(false);
  const recalledRef = useRef<string | null>(recalledSeat());
  const setupReclaimDone = useRef(false);
  const wsRef = useRef<WebSocket | null>(null);
  // /game/:id is the lobby flow (resolve the container endpoint); bare /game is standalone dev.
  const { id: gameId } = useParams();

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
    let cancelled = false;
    let socket: WebSocket | null = null;
    let retry: ReturnType<typeof setTimeout> | undefined;

    const onMessage = (e: MessageEvent) => {
      const env = JSON.parse(e.data) as
        | { type: "state"; snapshot: StateSnapshot }
        | ({ type: "request" } & DecisionRequest)
        | ({ type: "battle" } & BattleEvent)
        | { type: "notes"; html: string }
        | { type: "objectives"; items: ObjectiveItem[] }
        | { type: "seats"; roster: SeatRoster }
        | ({ type: "gameOver" } & GameOverInfo);
      if (env.type === "state") {
        setSnapshot(env.snapshot);
      } else if (env.type === "seats") {
        setRoster(env.roster);
      } else if (env.type === "request") {
        setRequest(env);
      } else if (env.type === "battle") {
        // One result per battle now, so keep a long history (still bounded for safety).
        setBattleLog((prev) => [...prev, env].slice(-500));
      } else if (env.type === "notes") {
        setNotesHtml(env.html);
      } else if (env.type === "objectives") {
        setObjectives(env.items);
      } else if (env.type === "gameOver") {
        gameOverRef.current = true; // stop the reconnect loop — the game is finished
        setRequest(null);
        setGameOver({ reason: env.reason, winners: env.winners, message: env.message });
      }
    };

    // Open a WebSocket and, in the lobby flow, authenticate with the connect-ticket as the FIRST
    // message — proving our seat so the container binds it (no claim, no client-supplied name).
    const openWs = (url: string, ticket: string | null) => {
      if (cancelled) return;
      const ws = new WebSocket(url);
      socket = ws;
      wsRef.current = ws;
      ws.onopen = () => {
        setWsStatus("live");
        if (ticket) ws.send(JSON.stringify({ type: "auth", ticket }));
      };
      ws.onmessage = onMessage;
      ws.onclose = () => {
        if (cancelled || gameOverRef.current) return; // don't reconnect to a finished game
        setWsStatus("connecting…");
        // Re-run connect on every drop: a ticket is single-use, so a reconnect needs a FRESH one
        // (and this re-spawns the container if it was reaped while idle — lazy rehydration).
        retry = setTimeout(connect, 2000);
      };
      ws.onerror = () => {}; // onclose follows and schedules the retry
    };

    // Resolve where to connect. Standalone (bare /game): a fixed local endpoint. Lobby (/game/:id):
    // ask the control plane, which authorizes us, (re)spawns the container, and mints a ticket.
    const connect = async () => {
      if (cancelled) return;
      if (!gameId) {
        openWs(LEGACY_WS_URL, null);
        return;
      }
      try {
        const res = await fetch(`/api/games/${gameId}/connect`, { credentials: "include" });
        if (!res.ok) {
          // 401/403/404 won't fix themselves; surface and stop. Transient errors retry below.
          setWsStatus(res.status === 401 ? "not signed in" : "cannot reach game");
          if (res.status >= 500) retry = setTimeout(connect, 2000);
          return;
        }
        const data = (await res.json()) as {
          wsEndpoint?: string;
          ticket?: string | null;
          seat?: string | null;
          isHost?: boolean;
          status?: string;
          reason?: string;
          winner?: string | null;
        };
        // The game is already over — show the end screen instead of connecting.
        if (data.status === "finished") {
          gameOverRef.current = true;
          setGameOver({
            reason: data.reason ?? "",
            winners: data.winner ? data.winner.split(", ") : [],
            message: data.winner ? `${data.winner} win!` : "Game over.",
          });
          return;
        }
        // Our identity comes from the control plane, not local storage: bind to the assigned seat,
        // or spectate if we hold none (e.g. a host who didn't take a seat).
        if (data.seat) {
          setMySeat(data.seat);
          setAssignedSeat(data.seat);
        } else setSpectating(true);
        setIsHost(!!data.isHost);
        // The control plane returns a same-origin PATH (e.g. /game/<id>/ws); the control plane
        // proxies it to the game's internal port, so one origin/cert covers every game.
        const endpoint = data.wsEndpoint as string;
        const wsUrl = endpoint.startsWith("/")
          ? `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}${endpoint}`
          : endpoint;
        openWs(wsUrl, data.ticket ?? null);
      } catch {
        setWsStatus("cannot reach control plane");
        retry = setTimeout(connect, 2000);
      }
    };

    void connect();

    return () => {
      cancelled = true;
      if (retry) clearTimeout(retry);
      socket?.close();
    };
  }, [gameId]);

  // When a decision arrives, surface it: jump to the Actions tab and expand the dock if collapsed.
  useEffect(() => {
    if (request) {
      setActiveTab("Actions");
      setDockCollapsed(false);
    }
  }, [request]);

  // Reconnect handling. In SETUP, silently re-claim the seat we last held (harmless, pre-game). In
  // RUNNING we do NOT auto-claim — the RejoinPrompt asks first, since taking a live seat can take
  // over a buffered decision or evict whoever is there.
  useEffect(() => {
    if (!roster || mySeat || spectating || setupReclaimDone.current) return;
    if (roster.phase === "setup") {
      const recalled = recalledRef.current;
      if (recalled) {
        const seat = roster.seats.find((s) => s.name === recalled);
        if (seat && !seat.owner) {
          setupReclaimDone.current = true;
          claimSeat(recalled);
        }
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roster, mySeat, spectating]);

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
    setSpectating(false);
    resetMove();
    resetPlace();
  }

  // ---- Seat setup controls (setup phase). ----
  function sendControl(msg: object) {
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "control", ...msg }));
    }
  }
  function changeName(name: string) {
    setMyName(name);
    rememberName(name);
  }
  function claimSeat(seat: string) {
    sendControl({ action: "claimSeat", seat, name: myName || undefined });
    setMySeat(seat);
    recalledRef.current = seat;
    rememberSeat(seat);
  }
  function releaseSeat(seat: string) {
    sendControl({ action: "releaseSeat", seat });
    setMySeat(null);
    forgetSeat();
  }
  function setSeatType(seat: string, playerType: string) {
    sendControl({ action: "setSeatType", seat, playerType });
  }
  function startGame() {
    sendControl({ action: "startGame" });
  }
  function resumeGame() {
    sendControl({ action: "resumeGame" });
  }
  // Reclaim our seat back from AI after a turn-timer takeover. We become the active player again.
  function reclaim() {
    if (!assignedSeat) return;
    sendControl({ action: "reclaim" });
    setMySeat(assignedSeat);
    setSpectating(false);
  }
  // Concede: resign our seat to AI; the game continues for everyone else. We drop to spectator.
  function concede() {
    if (!mySeat) return;
    if (
      !window.confirm(
        `Concede ${mySeat}? Your seat is played by AI for the rest of the game — you can keep watching.`,
      )
    ) {
      return;
    }
    sendControl({ action: "concede" });
    setMySeat(null);
    setAssignedSeat(null); // concede is permanent — no reclaim (distinguishes it from a takeover)
    setSpectating(true);
    setRequest(null);
    forgetSeat();
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

  // Game over: announce the winner / reason and stop here (the reconnect loop is already halted).
  if (gameOver) {
    return (
      <GameOverScreen info={gameOver} colors={geometry.playerColors} inLobbyFlow={!!gameId} />
    );
  }

  // Waiting room (lobby flow): seats are pre-assigned; show who's connected and let the host start.
  if (roster && roster.phase === "waiting") {
    return (
      <WaitingRoom
        roster={roster}
        mySeat={mySeat}
        isHost={isHost}
        colors={geometry.playerColors}
        onStart={startGame}
      />
    );
  }

  // Setup phase: choose seats before the game starts (unless the user opted to just watch).
  if (roster && roster.phase === "setup" && !spectating) {
    return (
      <SeatSelect
        roster={roster}
        mySeat={mySeat}
        myName={myName}
        colors={geometry.playerColors}
        onNameChange={changeName}
        onClaim={claimSeat}
        onRelease={releaseSeat}
        onSetType={setSeatType}
        onStart={startGame}
        onResume={resumeGame}
        onSpectate={() => setSpectating(true)}
      />
    );
  }

  // Running game but no claimed seat (e.g. reconnected after a full browser close): confirm a
  // rejoin rather than silently grabbing a live seat.
  if (roster && roster.phase === "running" && !mySeat && !spectating) {
    return (
      <RejoinPrompt
        roster={roster}
        recalledSeat={recalledRef.current}
        colors={geometry.playerColors}
        onRejoin={claimSeat}
        onSpectate={() => setSpectating(true)}
      />
    );
  }

  // Live owners/units from the server when connected; otherwise the static initial ownership.
  const owners = snapshot?.owners ?? geometry.initialOwners ?? {};
  // Whose turn it is when it isn't ours (no pending decision for our seat) — a "waiting" hint.
  const waitingFor =
    roster?.phase === "running" && !request ? (snapshot?.currentPlayer ?? null) : null;
  // Our assigned seat is being played by AI (a turn-timer takeover) — offer to reclaim it.
  const takenOver =
    !!assignedSeat &&
    roster?.phase === "running" &&
    roster.seats.find((s) => s.name === assignedSeat)?.owner == null;
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
      {(request || waitingFor || mySeat || spectating) && (
        <div
          style={{
            position: "fixed",
            top: 8,
            left: "50%",
            transform: "translateX(-50%)",
            background: request ? "#163" : "#222",
            border: "1px solid #444",
            color: request ? "#bfb" : "#bbb",
            padding: "4px 14px",
            borderRadius: 14,
            fontSize: 13,
            zIndex: 20,
            pointerEvents: "none",
          }}
        >
          {request
            ? `▶ Your turn — ${request.seat}`
            : waitingFor
              ? `⏳ Waiting for ${waitingFor}…`
              : mySeat
                ? `Seated as ${mySeat}`
                : "Spectating"}
        </div>
      )}
      {takenOver ? (
        <button
          onClick={reclaim}
          title="Take your seat back from AI — you'll drive it again on your next turn"
          style={{ ...seatActionBtn, background: "#232", border: "1px solid #494", color: "#9d9" }}
        >
          ⮌ Reclaim seat
        </button>
      ) : (
        mySeat &&
        roster?.phase === "running" && (
          <button
            onClick={concede}
            title="Resign your seat to AI; the game continues for everyone else"
            style={{ ...seatActionBtn, background: "#322", border: "1px solid #944", color: "#d99" }}
          >
            Concede
          </button>
        )
      )}
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
