# Web Port — Manual Test Script

A human-driven checklist for the browser client. Focuses on **interactive** paths (decisions,
map manipulation, multiplayer seat play, resume) — the things automated tests can't drive. Work
top to bottom; each item is an action + what to expect. Tick the box and jot anything odd.

> **Map:** World War II Pacific 1940 **2nd Edition** (the canonical file — 1st ed has different
> politics and will look like a config bug). **Turn order:** Japanese, Americans, Chinese, British,
> ANZAC (Japan acts first — the easiest seat to drive a full turn from).

---

## 0. Launch (prerequisites)

Two terminals, then a browser.

- [ ] **0.1 Game server.** In one terminal (sources Java per the project's Linux setup):
  ```
  source "$HOME/.sdkman/bin/sdkman-init.sh"; export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
  ./gradlew :game-web-server:runPlayable \
    --args="/home/gandalf/triplea-webport-work/world_war_ii_pacific-master/map/games/ww2pac40_2nd_edition.xml --port=8080 --step-delay-ms=300"
  ```
  **Expect:** it logs "Setup phase: … (5 seats)" and stays running.
- [ ] **0.2 Web client.** In a second terminal:
  ```
  source "$HOME/.nvm/nvm.sh"; nvm use 22; npm --prefix web-client run dev
  ```
  **Expect:** Vite serves on http://localhost:5173/.
- [ ] **0.3 Open** http://localhost:5173/ → the **seat-select screen** for *World War II Pacific
  1940 2nd Edition* appears, status shows **live**.

> To stop the server later: `Ctrl+C` in terminal 1 (used in the resume tests below).

---

## 1. Seat selection (setup phase)

- [ ] **1.1 Five nations, no minors.** The picker lists exactly **Japanese, Americans, Chinese,
  British, ANZAC** — Russians/French/Dutch are **absent** (inert minors run as AI).
- [ ] **1.2 Name + claim.** Type a name; click **Claim** on **Japanese**. **Expect:** the row shows
  "👤 \<name\> (you)" with a **Release**, and the counter reads "1 human seat claimed."
- [ ] **1.3 Release.** Click **Release** on Japanese → it returns to an AI dropdown; counter back to 0.
- [ ] **1.4 Assign AI types.** On an unclaimed nation, change the dropdown to **Weak / Fast / Pro
  (AI)**. **Expect:** selection sticks.
- [ ] **1.5 Watch only.** Click **Watch only** → the seat screen dismisses to the map (no game yet);
  you can re-enter setup only via a reload at this point. *(Reload to continue.)*

---

## 2. Multiplayer seat play (two browser tabs)

- [ ] **2.1 Two seats.** Tab A: claim **Japanese**. Tab B (new tab, same URL): claim **Americans**.
  **Expect:** each tab's roster updates live to show the other seat taken.
- [ ] **2.2 Start.** In either tab, set remaining nations to AI and click **Start game**. **Expect:**
  both tabs switch to the game board.
- [ ] **2.3 Turn ownership.** Tab A (Japanese, acts first) shows **"▶ Your turn — Japanese"** and gets
  the decision panels. Tab B (Americans) shows **"⏳ Waiting for Japanese…"** and **cannot** act.
- [ ] **2.4 No cross-seat control.** While Japan decides in Tab A, confirm Tab B has no actionable
  decision panel for Japan's turn (it only watches).
- [ ] **2.5 Spectator.** Open a third tab, click **Watch only** → board updates, no controls.

> For the detailed decision walkthrough (§3–§7) a **single human seat** is easiest: reload, claim
> only **Japanese**, set the rest to AI, Start.

---

## 3. Politics — declarations of war (first phase of the turn)

- [ ] **3.1 Options appear.** As Japan, the **Politics** panel offers declarations (e.g. *Declare war
  on French*, *…on Americans*, *…on British/ANZAC/Dutch*, *…on all*). Four distinct options confirm
  you're on 2nd edition.
- [ ] **3.2 Hover detail.** Hovering an option shows the full relationship ripple (who→whom: War).
- [ ] **3.3 Stage + undo (the key behavior).** Queue a declaration, then **remove it** before
  committing. **Expect:** nothing is applied until you end the phase — staging is reversible.
- [ ] **3.4 Commit.** Stage *Declare war on French* and **End the phase**. **Expect:** relationship
  flips to War; the turn advances to **Purchase**.
- [ ] **3.5 Empty commit.** (Next turn or new game) End politics with nothing staged → advances with
  no change.

---

## 4. Purchase

- [ ] **4.1 Budget + columns.** The panel shows Japan's IPC budget and buyable units grouped into
  **Land / Air / Naval / Buildings** columns (mobile AA gun should sit under **Buildings**).
- [ ] **4.2 Steppers + running cost.** Use +/- on several units. **Expect:** running total updates;
  it never lets the committed total exceed the budget (over-budget disables **Buy**).
- [ ] **4.3 Buy.** Buy a few infantry + an armour + a fighter, click **Buy**. **Expect:** advances to
  combat move. (Remember roughly what you bought — you'll place it in §7.)
- [ ] **4.4 Buy nothing.** (Another turn) **Buy nothing** advances with no purchase.

---

## 5. Combat movement (click-destination + live preview)

- [ ] **5.1 Pick a source.** Click a Japanese land territory with movable units (e.g. **Manchuria**
  or **Kiangsu**). **Expect:** it highlights; the move panel lists unit types with movement-left.
- [ ] **5.2 Pick units, then a destination.** Select some infantry, then click an enemy/adjacent
  destination (e.g. Chinese **Anhwe**). **Expect:** the server draws the **best legal route**
  highlighted, with a movement cost.
- [ ] **5.3 Unit-aware re-route.** Switch the selection to **fighters** and re-pick the destination.
  **Expect:** the route can change (air may path over sea zones); "can't reach" warns if too far.
- [ ] **5.4 Select all.** Use **Select all units** in a source → picks every eligible unit at once.
- [ ] **5.5 Move + undo.** Execute the move, then **Undo** it (and try **Undo all** with 2+ moves).
  **Expect:** units snap back on the map; you can undo any one move, not just the last.
- [ ] **5.6 Click-to-toggle.** Click a territory already in the route → it truncates/deselects
  (no Clear button needed); click the sole source again → route empties.
- [ ] **5.7 Live map updates.** After each accepted move, the **unit counts on the map** change
  immediately (units aren't frozen until phase end).
- [ ] **5.8 Transport load.** Move Japanese infantry from a coastal territory **onto a transport** in
  an adjacent sea zone. **Expect:** the infantry load; the count in the origin drops; **carried cargo
  is NOT flagged "can't reach."**
- [ ] **5.9 Island hit-testing.** Click a small **island** territory (it overlaps a large sea-zone
  polygon). **Expect:** the island is selected, not the water beneath it.
- [ ] **5.10 Done** ends the combat-move phase.

---

## 6. Combat resolution

- [ ] **6.1 Combat actually fights.** After moving Japanese units into a **defended Chinese**
  territory and ending combat move, the **Combat** phase resolves the battle (it must NOT silently
  skip with attacker + defender sitting co-located).
- [ ] **6.2 Round + forces.** The battle shows a round header with surviving attacker/defender forces.
- [ ] **6.3 Select casualties (override the default).** When you score hits, the casualty panel
  pre-fills the engine default; **change it** (e.g. lose artillery instead of infantry) and submit.
  **Expect:** the battle log reflects your choice; submit only enabled at exactly the right count.
- [ ] **6.4 Retreat.** In a multi-round battle, choose **Retreat to \<territory\>** between rounds.
  **Expect:** "retreats all units to …", battle ends, units return there. (Or **Stay and fight**.)
- [ ] **6.5 Battle log.** A completed battle appears in the **Battle Log** (sidebar), grouped by game
  round → attacking nation, with per-side losses.

---

## 7. Non-combat move, air warning, and placement

- [ ] **7.1 Non-combat move.** Same click-destination mechanic as §5, for the non-combat phase.
- [ ] **7.2 Air can't land (if applicable).** End a move phase that would strand aircraft. **Expect:**
  an **Air warning** panel lists at-risk territories as **clickable pills that pan the map** to them,
  with **Keep moving** / **End anyway (lose aircraft)**.
- [ ] **7.3 Place units.** In the **Place** phase, click a target (e.g. **Japan**), pick units from the
  pool, **Place**. **Expect:** the engine validates (factory/caps/sea adjacency); rejection shows an
  error; **Done** ends (leftover units are lost). Place the units you bought in §4.
- [ ] **7.4 Turn completes** → advances to the next nation (AI plays the others; watch the map move).

---

## 8. Information panels (bottom dock)

Tabs: **Actions · Players · Relationships · Objectives · Notes · Territory**.

- [ ] **8.1 Players.** Per-nation table (IPC, production, units, TUV, victory cities); **IPC** cell
  shows income like `26 (+36)`; tokens (kamikaze/tech) ride beside the owner; the **passive minors
  collapse into an "Other" group**; the Allies block shows a subtotal.
- [ ] **8.2 Relationships.** N×N grid colored war / allied / neutral; reflects your §3 declaration.
- [ ] **8.3 Objectives.** National objectives grouped by faction with ✓/○ markers (e.g. a satisfied
  Japanese diplomatic objective).
- [ ] **8.4 Notes.** The map's rules/notes HTML renders, scrollable.
- [ ] **8.5 Territory.** Click any territory on the map → this tab pins its name, sea/land, IPC value,
  capital, owner, and units grouped by owner.
- [ ] **8.6 Auto-focus.** When a new decision arrives, the dock auto-switches to **Actions** (a known
  minor annoyance if you were browsing an info tab — note if it bothers you).

---

## 9. Map interaction

- [ ] **9.1 Pan / zoom.** Drag to pan; wheel to zoom (cursor-anchored). Fits to view on load.
- [ ] **9.2 Hover tooltip.** Hover a territory → name / land-or-sea / IPC / capital / owner / units.
- [ ] **9.3 Visual elements.** Sea zones render **blue**; land tinted by owner; **IPC value roundels**
  in tan circles on valued land (fixed position, don't hop as units move); **capital dots**; unit
  count badges at centers.
- [ ] **9.4 Minimap.** The sidebar minimap shows an owner-tinted whole-map overview that updates as
  ownership changes. *(It's not yet click-to-navigate — that's deferred.)*

---

## 10. Persistence & resume (P4.2)

- [ ] **10.1 Autosave exists.** After playing a few phases, check a save file appears:
  `ls -la ~/.triplea-web/saves/` → `World_War_II_Pacific_1940_2nd_Edition.tsvg`, recently modified.
- [ ] **10.2 Resume after restart.** `Ctrl+C` the **server** (terminal 1), then relaunch it (0.1).
  Reload the browser. **Expect:** the seat screen now shows a **"Resume — round N"** button (with the
  saved step in its tooltip) and the start button relabels to **"Start new game."**
- [ ] **10.3 Resume continues.** Click **Resume**. **Expect:** the game comes back at the **saved
  round/phase**, not a fresh round 1; the board state matches where you left off.
- [ ] **10.4 New game replaces.** From a state with a save, **Start new game** → fresh round 1 (note:
  this overwrites the autosave on the first new step — single save slot for now).

---

## 11. Reconnection & rejoin (mid-game)

- [ ] **11.1 State restored.** Mid-game, **reload** your tab. **Expect:** the board, owners, units,
  notes, objectives, and the **seat roster** all come back.
- [ ] **11.2 Rejoin after a browser close.** Claim a seat, play to a decision, then **fully close the
  browser** (not just the tab) and reopen the page. **Expect:** a **"{game} — game in progress"**
  prompt: *"Rejoin as {your seat}? Your turn is waiting."* → **[Rejoin]** / **[Watch only]**. Click
  **Rejoin** → your decision panel appears and you can continue. (A *tab reload* mid-game keeps you
  seated without the prompt; only a full close/cross-device reconnect shows it.)
- [ ] **11.3 Take-over wording.** (Two browsers) If a *second* browser reconnects to a seat a *first*
  is still holding, the prompt reads *"… is controlled by {name}. Take it over?"* — explicit, never
  silent. Confirm it doesn't silently evict the first player without that click.
- [ ] **11.4 No dead seats.** The rejoin picker only offers **human** seats (ones a person claimed at
  launch); AI-controlled nations are not offered to rejoin.
- [ ] **11.5 Battle log restored.** After some battles, reload → the **Battle Log is repopulated**
  (not empty) — the server replays it on connect.

---

## 12. New game reset

- [ ] **12.1 Reset to setup.** Click **⟳ New game** (sidebar) mid-game. **Expect:** everyone returns
  to the seat-select screen (prior seat choices retained); a clean game can be started/resumed; the
  server stays up (no reload needed).

---

## Out of scope (not yet built — don't chase these)

- Tech panel; Pacific naval/air queries (scramble, kamikaze, shore bombardment, kamikaze suicide) —
  the engine still enforces them via defaults, but there are no interactive panels yet (Phase 3f).
- Battle Calculator / Add-attackers/defenders (Territory tab buttons) — deferred.
- Interactive (click-to-navigate) minimap — deferred.
- Auth / lobby / accounts, multiple concurrent games, turn timers, "your turn" notifications —
  Phase 4.3+ (seat claims are currently **trusted**, not authenticated).

---

## Reporting

For anything that fails or feels wrong, note: **which item**, what you did, what you expected, what
happened (a screenshot to `screenshots/` helps), and the round/phase/nation. Server logs print to
terminal 1; browser console errors (F12) are useful too.
