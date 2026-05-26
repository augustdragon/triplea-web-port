/**
 * Display-only formatting for territory names.
 *
 * The engine and map data canonically name sea zones "16 Sea Zone" — that string is the key used
 * everywhere for routing, adjacency, ownership and unit lookups, so it must NEVER be altered in the
 * data. But the physical board labels them "Sea Zone 16", so we reformat for display only. The
 * global regex also handles composite strings such as an undo label "6 Sea Zone -> 16 Sea Zone".
 */
export function displayTerritory(name: string): string {
  return name.replace(/(\d+) Sea Zone/g, "Sea Zone $1");
}
