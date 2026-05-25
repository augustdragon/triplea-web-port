// Mirrors the JSON produced by :game-web-server:exportGeometry (MapGeometry).

export interface XyPoint {
  x: number;
  y: number;
}

export interface TerritoryGeometry {
  name: string;
  polygons: XyPoint[][];
  center: XyPoint | null;
}

export interface MapGeometry {
  mapWidth: number;
  mapHeight: number;
  playerColors: Record<string, string>;
  territories: TerritoryGeometry[];
  connections: Record<string, string[]> | null;
  initialOwners: Record<string, string> | null;
}
