# How game state is stored & tracked (engine + web port)

A reference for anyone working on the web port. Describes how the **reused**
`game-app/game-core` engine represents game state, and exactly which parts the
web port reads. Nothing here is web-port-specific storage — we add none.

## TL;DR

- Live state is an **in-memory Java object graph** rooted at `GameData`. **No database.**
- A **territory** stores its own `owner`, a `water` flag, a `UnitCollection`, and a
  bag of **attachments** (the definitional data parsed from the map XML).
- **Units are first-class objects.** Nationality, type, and per-unit state live on the
  `Unit`, *not* on the region. A territory just holds a list of units; "how many
  Japanese infantry are here" is **derived** by querying that list, never stored as a count.
- State changes go through a **command pattern** (`Change` objects via `ChangeFactory`),
  which makes them undoable and network-serializable.
- Persistence is **Java serialization to a gzipped file** (`.tsvg` save game) — a snapshot
  of the object graph. Still no database.
- The web port only **reads** this graph: `geometry.json` (static map shape, exported once)
  and `StateSnapshot` (a live JSON projection broadcast per step over WebSocket).

## 1. The in-memory object graph

While a game runs, everything is live objects in RAM:

```
GameData (root)
├── GameMap            territories + adjacency graph (connections)
│   └── Territory[]    each: owner, water?, UnitCollection, attachments
├── PlayerList         GamePlayer[] (the nations)
├── UnitsList          all Unit objects
├── GameSequence       rounds → steps (whose turn, which phase)
├── attachments        TerritoryAttachment / UnitAttachment / PlayerAttachment / rules
└── properties         ~130 game options (edition switches, house rules)
```

There is no DB and no per-entity persistence. The whole graph lives or dies with the
process unless it is serialized to a save file (§5).

## 2. A Territory's "properties" come in two flavors

`Territory extends NamedAttachable` and has only a few live fields
(`engine/data/Territory.java`):

```java
private final boolean water;                 // sea zone vs land — fixed at load
private GamePlayer owner;                     // who CONTROLS the territory (mutable: conquest)
private final UnitCollection unitCollection;  // the units physically located here
```

Everything else descriptive is an **attachment**, not a field. `NamedAttachable` keeps a
`Map<String, IAttachment>` (`engine/data/NamedAttachable.java`). The map XML is parsed at
load into:

- **`TerritoryAttachment`** — production (PU) value, capital, victory city, original owner,
  unit-stacking limits, etc.
- **`UnitAttachment`** — per *unit type*: movement, attack/defense, transport capacity,
  `isAir`/`isSea`, and so on.

So the split is:

| Kind | Where | Mutability |
|---|---|---|
| Definitional / rules | **Attachments** (`Map<String,IAttachment>`) | Mostly fixed; tech/politics alter a few |
| Dynamic state | **Live fields** + the `UnitCollection` | Changes constantly during play |

This split is why a base's movement bonus isn't a stored number on the territory — it's
computed from attachments at query time (`Unit.getMovementLeft()`).

## 3. Units are objects, NOT properties of a region

This is the most common misconception, so it's worth stating plainly.

Each `Unit` (`engine/data/Unit.java`) carries its **own** identity and state:

```java
private GamePlayer owner;          // nationality lives on the UNIT
// type (UnitType), alreadyMoved (BigDecimal), transportedBy, hits, ...
```

A territory only holds a `UnitCollection`, which is essentially a `List<Unit>`
(`engine/data/UnitCollection.java`). Therefore:

- **Nationality is on the unit, not the region.** A territory has exactly one `owner` (its
  controller), independent of who owns the units standing on it — that's how an allied
  fighter lands in your territory, or two powers' ships share a contested sea zone.
- **Quantity is derived, not stored.** "Japanese infantry in Japan" = a query over the list
  (`getUnitCount(type, owner)`), not a counter on the territory.
- **Each unit is a distinct object with an ID.** "2 infantry" is two `Unit` instances, not
  `count=2`. That's why move code resolves real `Unit` objects rather than passing numbers.

The web client *does* show counts (e.g. `UnitStack{owner,type,count}`), but those are
**aggregated for display** by `StateProjector` — the engine still tracks individuals.

## 4. State mutates via a command pattern (Change objects)

Callers don't set fields directly. They build `Change` objects with `ChangeFactory`
(`engine/data/changefactory/ChangeFactory.java`) and apply them with
`data.performChange(change)`:

```java
ChangeFactory.addUnits(holder, units)
ChangeFactory.removeUnits(holder, units)
ChangeFactory.changeOwner(territory, newOwner)
ChangeFactory.moveUnits(start, end, units)
```

Changes are **reversible** (undo) and **serializable**. That's what lets the engine run
headless, keep networked clients in sync (changes are sent over the wire), and record a
replayable history. It's also why a `game-core` data change is a stop-and-reassess event:
it can break save compatibility and the `@RemoteActionCode` RPC contract.

## 5. Persistence = serialized object graph, no DB

A save game is literally the serialized graph. `GameDataManager`
(`engine/framework/GameDataManager.java`) writes the engine version + the whole `GameData`
through `ObjectOutputStream` → `GZIPOutputStream` (the `.tsvg` file), plus each delegate's
`saveState`; loading reads it back. Autosaves fire at round boundaries (the
`getAutoSaveFileUtils` path the headless host must support).

The `serialVersionUID` on these classes is a hard reason to leave `game-core` data shapes
alone: changing a field breaks every existing save.

## 6. What the web port reads (and what it does NOT add)

We run the same in-memory `GameData` in our headless process and add **nothing** to the
storage model. Two read paths only:

| Path | What | When | Authority |
|---|---|---|---|
| `geometry.json` | Map *shape*: polygons, centers + a few static attachment values (water, production, capital) | Exported **once**, offline (`exportGeometry`); served as a static file | Never changes |
| `StateSnapshot` (WebSocket) | Live projection: owners map, units-by-territory (aggregated from each `UnitCollection`), round/step/current player | `StateProjector` reads `GameData` per step; broadcast as JSON | The engine's RAM is authoritative; the browser holds only a copy |

The browser never holds state of record — just this projection. The runner publishes a
snapshot after each engine step; additionally, because a move phase runs entirely inside one
step, `WebPlayer.handleMove` re-projects and broadcasts after **each accepted move** (via
`bridge.publishState`), so the map reflects unit movement immediately rather than only at
step boundaries. The browser still stores nothing new — it just renders the latest
projection. If the process dies without a save, in-memory state is gone.

## Key files

- `engine/data/GameData.java` — the root object graph.
- `engine/data/Territory.java`, `UnitCollection.java`, `Unit.java` — territory/unit state.
- `engine/data/NamedAttachable.java` + `*Attachment` classes — definitional properties.
- `engine/data/changefactory/ChangeFactory.java` — the mutation command pattern.
- `engine/framework/GameDataManager.java` — save/load serialization.
- Web port: `map/MapGeometryConverter` → `geometry.json`; `game/StateProjector` → `StateSnapshot`.
