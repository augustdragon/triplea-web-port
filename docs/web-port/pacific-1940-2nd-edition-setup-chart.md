# Axis & Allies Pacific 1940 (2nd Edition) — Starting Setup Chart

**Source:** [axisandallies.org — Setup Chart for A&A Pacific 1940 2nd
Edition](https://www.axisandallies.org/resources-downloads/setup-chart-for-axis-allies-pacific-1940-second-edition/)
(official Wizards/Avalon Hill setup chart).

**Why this file exists:** reference for validating the web-port's initial board
state against the printed game — i.e. confirming each territory's owner and
starting units match. The digital engine's ground truth is the map XML
(`ww2pac40_2nd_edition.xml`, `<territoryOwner>` + `<unitPlacement>` elements);
this chart is the human-readable cross-check.

> ⚠️ **Provenance caveat:** the per-unit lists below were extracted from the web
> page above by an automated reader, not transcribed cell-by-cell from the
> printed chart. Treat the **owners** as reliable and **spot-check exact unit
> counts** against the official chart / rulebook PDF
> (`docs/axis-allies-rules-pacific1940-2nd-edition.pdf`) before relying on a
> specific number. The one entry verified directly against the engine XML is
> **Suiyuan** (see note at the bottom).

Terminology: **IC** = Industrial Complex (major/minor), **AAA** = anti-aircraft
gun, **SZ** = sea zone. The physical game's currency is **IPC** (Industrial
Production Certificates); the TripleA engine calls the same resource "PUs"
internally, displayed as IPC in the web client.

---

## China — 12 IPC starting income

| Territory | Starting units |
|-----------|----------------|
| Szechwan  | 6 infantry, 1 fighter |
| Yunnan    | 4 infantry |
| Hunan     | 2 infantry |
| Kweichow  | 2 infantry |
| Shensi    | 1 infantry |
| **Suiyuan** | **2 infantry** |

China cannot build units of its own beyond infantry (special China rules) and
collects no income from most of these provinces (production value 0–1 IPC).

## ANZAC — 10 IPC starting income

| Territory / SZ | Starting units |
|----------------|----------------|
| New South Wales | 2 infantry, 2 AAA, naval base, minor IC |
| Queensland | 2 infantry, 1 artillery, 1 fighter, air base, naval base |
| New Zealand | 1 infantry, 2 fighters, air base, naval base |
| Malaya (ANZAC portion) | 1 infantry |
| SZ 62 | 1 transport, 1 destroyer |
| SZ 63 | 1 cruiser |

## United Kingdom (Pacific) — 16 IPC starting income

| Territory / SZ | Starting units |
|----------------|----------------|
| India | 6 infantry, 1 artillery, 3 AAA, 1 fighter, 1 tactical bomber, air base, naval base, major IC |
| Burma | 2 infantry, 1 fighter |
| Malaya | 3 infantry, naval base |
| Kwangtung | 2 infantry, naval base |
| SZ 37 | 1 battleship |
| SZ 39 | 1 transport, 1 destroyer, 1 cruiser |

## United States — 17 IPC starting income

| Territory / SZ | Starting units |
|----------------|----------------|
| Western United States | 2 infantry, 1 mechanized infantry, 1 artillery, 2 AAA, 1 fighter, air base, naval base, major IC |
| Hawaiian Islands | 2 infantry, 2 fighters, air base, naval base |
| Midway | air base |
| Wake Island | air base |
| Guam | air base |
| Philippines | 2 infantry, 1 fighter, air base, naval base |
| SZ 10 | 1 transport, 1 destroyer, 1 cruiser, 1 carrier (1 fighter + 1 tactical bomber), 1 battleship |
| SZ 26 | 1 transport, 1 submarine, 1 destroyer, 1 cruiser |
| SZ 35 | 1 submarine, 1 destroyer |

## Japan — 26 IPC starting income

| Territory / SZ | Starting units |
|----------------|----------------|
| Japan | 6 infantry, 2 artillery, 1 tank, 3 AAA, 2 fighters, 2 tactical bombers, 2 strategic bombers, air base, naval base, major IC |
| Iwo Jima | 1 infantry |
| Okinawa | 1 infantry, 1 fighter |
| Korea | 4 infantry, 1 fighter |
| Manchuria | 6 infantry, 1 mechanized infantry, 1 artillery, 1 AAA, 2 fighters, 2 tactical bombers |
| Jehol | 2 infantry, 1 artillery |
| Shantung | 3 infantry, 1 artillery |
| Kiangsu | 3 infantry, 1 artillery, 1 fighter, 1 tactical bomber |
| Kiangsi | 3 infantry, 1 artillery |
| Kwangsi | 3 infantry, 1 artillery |
| Siam | 2 infantry |
| Formosa | 1 fighter |
| Palau Island | 1 infantry |
| Caroline Islands | 2 infantry, 1 AAA, air base, naval base |
| SZ 6 | 1 transport, 1 submarine, 2 destroyers, 1 cruiser, 2 carriers (each 1 fighter + 1 tactical bomber), 1 battleship |
| SZ 19 | 1 transport, 1 submarine, 1 destroyer, 1 battleship |
| SZ 20 | 1 transport, 1 cruiser |
| SZ 33 | 1 destroyer, 1 carrier (1 fighter + 1 tactical bomber) |

## Passive / neutral powers

The web page does not enumerate the **Soviet (Russians)**, **French**, and
**Dutch** starting positions, nor the strict/true neutral territories. In the
TripleA map XML these are present but flagged `optional="true"` (passive); they
hold territory and (for Russians/French/Dutch) some production but do not act on
Pacific 1940 unless drawn into the war. Pull their exact placements from the map
XML's `<unitPlacement owner="Russians|French|Dutch">` lines if needed.

## Downloadable references on the source page

- Rulebook PDF: `https://www.axisandallies.org/files/rules/Axis-Allies-Pacific-1940-Second-Edition.pdf`
- FAQ PDF: `https://www.axisandallies.org/files/AA-Pacific-1940-2nd-Edition-FAQ.pdf`

(A local copy of the rulebook already lives at
`docs/axis-allies-rules-pacific1940-2nd-edition.pdf`.)

---

## Note: the "Suiyuan" / "Suiyuyan" web-port bug (fixed)

The web client originally rendered **Suiyuan** as an empty, neutral-gray
territory with no units — wrong. Both this chart and the engine XML agree it is
**Chinese-owned with 2 infantry**:

```
ww2pac40_2nd_edition.xml
  <territoryOwner territory="Suiyuan" owner="Chinese"/>
  <unitPlacement unitType="infantry" territory="Suiyuan" quantity="2" owner="Chinese"/>
  <option name="production" value="1"/>   (so IPC 1, not 0)
```

Root cause was a **duplicate, misspelled polygon** in the *map data* (not the
engine): `polygons.txt` and `centers.txt` each contained both `Suiyuan` and an
extra `Suiyuyan` with identical coordinates. The web port's geometry converter
emitted the orphan `Suiyuyan` (which the engine doesn't know, so it had no owner
or units) on top of the real `Suiyuan`, masking it.

Fixed in `MapGeometryConverter.readTerritories` by dropping any polygon whose
name isn't a real territory in the loaded game data (`Suiyuyan` was the only
such orphan in this map). See that file's comment for details.
