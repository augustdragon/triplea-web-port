// Mirrors the JSON produced by :game-web-server:exportGeometry (MapGeometry) and the
// per-step StateSnapshot pushed over WebSocket by :game-web-server:runSpectator.

export interface XyPoint {
  x: number;
  y: number;
}

export interface TerritoryGeometry {
  name: string;
  polygons: XyPoint[][];
  center: XyPoint | null;
  /** True for sea zones (rendered blue). */
  water: boolean;
  /** Production (PU) value; 0 for sea zones and valueless land. */
  production: number;
  /** The player whose capital this is, or null. */
  capitalOf: string | null;
}

export interface MapGeometry {
  mapWidth: number;
  mapHeight: number;
  playerColors: Record<string, string>;
  territories: TerritoryGeometry[];
  connections: Record<string, string[]> | null;
  initialOwners: Record<string, string> | null;
}

/** A group of identical units in a territory (owner + unit type + count). */
export interface UnitStack {
  owner: string;
  type: string;
  count: number;
}

export interface StateSnapshot {
  round: number;
  step: string;
  currentPlayer: string | null;
  owners: Record<string, string>;
  /** Territory name -> its unit stacks. Only territories holding units appear. */
  units: Record<string, UnitStack[]>;
}

// ---- Decision protocol (server <-> client over WebSocket). ----

/** One buyable production rule offered during the purchase phase. */
export interface PurchaseOption {
  name: string;
  cost: number;
  produces: string;
  quantity: number;
}

/** Payload of a kind:"purchase" request: who's buying, budget, options, and any prior error. */
export interface PurchaseRequest {
  player: string;
  pusAvailable: number;
  bid: boolean;
  options: PurchaseOption[];
  error: string | null;
}

/** One movable unit type in a territory: count plus display metadata (the engine validates moves). */
export interface MovableUnit {
  type: string;
  count: number;
  /** Air unit (can cross sea zones; must end able to land). */
  air: boolean;
  /** Sea unit (stays in sea zones; transports carry land units). */
  sea: boolean;
  /** Max remaining movement among this type here; 0 for cargo aboard a transport. */
  movementLeft: number;
}

/** A move already made this phase that can be undone. index is what {undo:index} refers to. */
export interface UndoableMoveInfo {
  index: number;
  /** "2 infantry, 1 armour" — the moved units grouped by type. */
  units: string;
  /** "start -> end" route. */
  label: string;
  canUndo: boolean;
}

/**
 * Payload of a kind:"move" request: who's moving, combat vs non-combat, the units that can still act
 * (territory -> MovableUnit[]) so the client offers only valid picks and can label them, the moves
 * already made this phase (undoableMoves), plus any prior rejection error. Reply is {done:true},
 * {route:[territoryNames], units:{type:count}} (a land→sea route auto-loads onto transports in the
 * destination sea zone), or {undo:index}.
 */
export interface MoveRequest {
  player: string;
  combat: boolean;
  movableUnits: Record<string, MovableUnit[]>;
  undoableMoves: UndoableMoveInfo[];
  error: string | null;
}

/**
 * Payload of a kind:"selectCasualties" request: the player taking hits picks which units die.
 * `count` is exactly how many to lose; `options` is the eligible pool (type -> available); the reply
 * is {killed:{type:count}} summing to `count`. `defaultKilled` is the engine's pre-pick.
 */
export interface CasualtyRequest {
  player: string;
  location: string | null;
  message: string;
  count: number;
  options: Record<string, number>;
  defaultKilled: Record<string, number>;
  allowMultipleHits: boolean;
}

/**
 * Payload of a kind:"retreat" request: the attacker may pull out between rounds. `options` are
 * territory names to retreat to (just the battle site when `submerge`). `attackers`/`defenders` are
 * the surviving forces so the choice is informed. Reply {retreatTo:"<name>"} or {remain:true}.
 */
export interface RetreatRequest {
  player: string;
  battleTerritory: string;
  submerge: boolean;
  options: string[];
  message: string;
  attackers: string;
  defenders: string;
}

/** A decision the active human seat must answer. payload shape depends on kind. */
export interface DecisionRequest {
  requestId: string;
  kind: string;
  payload: PurchaseRequest | MoveRequest | CasualtyRequest | RetreatRequest;
}

/**
 * A battle-log event pushed by the server's WebDisplay ({type:"battle"}). Fields are populated per
 * `kind`: start (location/attacker/defender/attackers/defenders/amphibious), dice (step/hits),
 * casualties (player/killed/damaged), retreat (player/message), end (message).
 */
export interface BattleEvent {
  kind: "start" | "round" | "dice" | "casualties" | "retreat" | "end";
  battleId?: string;
  round?: number;
  location?: string;
  attacker?: string;
  defender?: string;
  attackers?: string;
  defenders?: string;
  amphibious?: boolean;
  step?: string;
  hits?: number;
  player?: string;
  killed?: string;
  damaged?: string;
  message?: string;
}
