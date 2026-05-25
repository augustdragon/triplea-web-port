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

/** A decision the active human seat must answer. payload shape depends on kind. */
export interface DecisionRequest {
  requestId: string;
  kind: string;
  payload: PurchaseRequest;
}
