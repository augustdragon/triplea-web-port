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
  /** Production / IPC value; 0 for sea zones and valueless land. */
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

/** How one player relates to another: the engine type name, plus a war/allied/neutral category. */
export interface RelationshipCell {
  /** Engine relationship type name (e.g. "War", "Neutrality", "Custodianship"). */
  type: string;
  /** Collapsed archetype for coloring: "war" | "allied" | "neutral". */
  category: string;
}

/** One resource's amount on hand + estimated next-turn income (a Players-tab PU/token cell). */
export interface ResourceCell {
  name: string;
  amount: number;
  income: number;
}

/** One national objective (Objectives tab): its section, HTML description, and satisfied state. */
export interface ObjectiveItem {
  section: string;
  text: string;
  satisfied: boolean;
}

/** Per-player summary stats (mirrors StatPanel/EconomyPanel columns); drives the info tabs. */
export interface PlayerStat {
  player: string;
  /** Alliances this power belongs to — used to render alliance total rows. */
  alliances: string[];
  /**
   * The engine's "optional" player flag — the passive minor powers (Pacific's Russians/French/Dutch)
   * that never produce/move/fight. The Players tab collapses these into one "Other" group.
   */
  passive: boolean;
  pus: number;
  production: number;
  units: number;
  tuv: number;
  victoryCities: number;
  /** Every (non-VP) resource's amount + income, in the engine's order (PU cell + token chips). */
  resources: ResourceCell[];
}

export interface StateSnapshot {
  round: number;
  step: string;
  currentPlayer: string | null;
  owners: Record<string, string>;
  /** Territory name -> its unit stacks. Only territories holding units appear. */
  units: Record<string, UnitStack[]>;
  /** The powers, in turn order — the axes of the relationship grid. */
  players: string[];
  /** Full matrix: relationships[a][b] = how a relates to b (symmetric; self omitted). */
  relationships: Record<string, Record<string, RelationshipCell>>;
  /** Per-player summary stats, in turn order. */
  playerStats: PlayerStat[];
}

// ---- Decision protocol (server <-> client over WebSocket). ----

/** One buyable production rule offered during the purchase phase. */
export interface PurchaseOption {
  name: string;
  cost: number;
  produces: string;
  quantity: number;
  /** Purchase column: "land" | "air" | "naval" | "building". */
  category: string;
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
 * A move the server previewed (computed but did not execute) from a source/destination/units the
 * client asked about — echoed back so the client can highlight the path before committing. `route`
 * is the engine's best legal path (territory names, source first) or null when there's none. `cost`
 * is that route's movement cost; `blockedTypes` are chosen unit types that can't make the distance;
 * `message` explains a null route.
 */
export interface MovePreview {
  from: string;
  to: string;
  route: string[] | null;
  cost: number;
  blockedTypes: string[];
  message: string | null;
}

/**
 * Payload of a kind:"move" request: who's moving, combat vs non-combat, the units that can still act
 * (territory -> MovableUnit[]) so the client offers only valid picks and can label them, the moves
 * already made this phase (undoableMoves), any prior rejection error, and `preview` (the route the
 * server computed for a previously-requested preview, if any). The client picks a source + units +
 * destination; replies are {previewRoute:{from,to,units}} (compute & echo a preview, no move),
 * {from, to, units:{type:count}} (perform the move — server finds the best legal route; a land→sea
 * route auto-loads onto transports in the destination sea zone), {done:true}, or {undo:index}.
 */
export interface MoveRequest {
  player: string;
  combat: boolean;
  movableUnits: Record<string, MovableUnit[]>;
  undoableMoves: UndoableMoveInfo[];
  error: string | null;
  preview: MovePreview | null;
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

/** One bought-but-unplaced unit type in the place pool, with air/sea flags for labelling. */
export interface PlaceUnit {
  type: string;
  count: number;
  air: boolean;
  sea: boolean;
}

/**
 * Payload of a kind:"place" request: place units bought this turn. `toPlace` is the remaining pool.
 * Click a territory, pick units, submit {territory, units:{type:count}} (engine validates — factory,
 * caps, sea adjacency), or {done:true} to end (leftover units are lost).
 */
export interface PlaceRequest {
  player: string;
  bid: boolean;
  toPlace: PlaceUnit[];
  error: string | null;
}

/** One political action (e.g. a declaration of war) offered during the politics phase. */
export interface PoliticalActionOption {
  /** Engine action id, sent back in the reply. */
  name: string;
  /** Concise headline (e.g. "Declare war on Americans, British") — what the acting player does. */
  summary: string;
  /** All relationship changes, pre-rendered (e.g. "Japanese → French: War") — shown on hover only. */
  changes: string[];
  /** PU cost (0 = free). */
  costPu: number;
  /** Success odds: "auto" when it always succeeds, else "hit/sides" (e.g. "3/6"). */
  chance: string;
}

/**
 * Payload of a kind:"politics" request: the first phase of a turn, where the player may declare war
 * or sign treaties. `actions` are the currently-legal actions (already condition-filtered by the
 * engine). Reply {action:"<name>"} to attempt one (the engine applies it; the server re-prompts with
 * the now-smaller list), or {done:true} to end the phase.
 */
export interface PoliticsRequest {
  player: string;
  actions: PoliticalActionOption[];
  error: string | null;
}

/**
 * Payload of a kind:"airWarning" request: ending the move phase now would strand aircraft (they
 * can't reach friendly territory and will be lost). `territories` lists where they are. Reply
 * {endAnyway:true} to end and accept the loss, or {endAnyway:false} to keep moving.
 */
export interface AirWarningRequest {
  player: string;
  territories: string[];
}

/** A decision the active human seat must answer. payload shape depends on kind. */
export interface DecisionRequest {
  requestId: string;
  kind: string;
  payload:
    | PoliticsRequest
    | PurchaseRequest
    | MoveRequest
    | CasualtyRequest
    | RetreatRequest
    | PlaceRequest
    | AirWarningRequest;
}

/**
 * A battle-result event pushed by the server's WebDisplay ({type:"battle", kind:"result"}) — one
 * per completed battle, for a historical record grouped by game round → attacking nation.
 */
export interface BattleEvent {
  kind: "result";
  gameRound: number;
  attacker: string;
  defender: string;
  location: string;
  result: string;
  attackerLosses: string;
  defenderLosses: string;
}
