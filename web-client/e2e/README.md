# Web-port end-to-end tests (Playwright)

`politics.spec.ts` drives the running web client through the politics feature:
the politics phase, the relationships grid, staged declarations + undo, and the
advance into purchase.

## Scope

These are **DOM-driven** tests. The map is a `<canvas>`, so the phases that need
pixel-coordinate clicks — combat/non-combat **move**, **place**, **battle
casualties/retreat**, and the **air-can't-land** warning (which you reach by
stranding a plane via canvas moves) — are intentionally **out of scope** here.
They'd need canvas coordinate math or a unit-placement fixture; the engine logic
behind them is exercised by the Java unit tests instead.

## Prerequisites

1. **A fresh game server as Japan on :8080.** Committing a declaration of war is
   one-way (the engine has no political undo), so the suite assumes the game is
   at Japan's politics phase with nothing declared yet. Start/restart it:

   ```powershell
   powershell scripts\restart-web.ps1 -Player Japanese
   ```

   Re-run the restart before each full suite run (the last test declares war on
   France and ends the phase).

2. **`public/geometry.json` present** (the client needs it to render). Export it
   if missing:

   ```powershell
   $env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
   .\gradlew :game-web-server:exportGeometry
   # then copy the produced geometry.json into web-client/public/
   ```

The Vite dev server (:5173) is started automatically by `playwright.config.ts`
(`webServer`), reusing one that's already running.

## Running

Playwright isn't a dependency of this project yet. Two options:

### Option A — install the test runner here (simplest)

```powershell
cd web-client
npm install -D @playwright/test
npx playwright install chromium   # reuses the shared browser cache if present
npx playwright test               # runs e2e/ ; add --headed or --ui to watch
```

### Option B — use the Playwright checkout at d:\dev\playwright

That folder is the Playwright **framework source monorepo**, so its already
downloaded Chromium can be reused without a fresh download. Point this project's
runner at that browser cache (default cache is shared, so often nothing extra is
needed):

```powershell
cd web-client
npm install -D @playwright/test
# If browsers were installed under the d:\dev\playwright checkout rather than the
# shared cache, point at them:
$env:PLAYWRIGHT_BROWSERS_PATH = "$env:USERPROFILE\AppData\Local\ms-playwright"
npx playwright test
```

## What a green run proves

- The React client loads the map and connects to the live game over WebSocket as
  Japan.
- The politics phase offers exactly the four Pacific 1940 declarations.
- The relationships grid reflects the engine's opening matrix (Japan–China at
  War, the Western Allies Allied/Custodianship, everyone else Neutral).
- Staging a declaration is reversible client-side; nothing reaches the engine
  until you end the phase.
- Committing a declaration flips the relationship to War and advances the turn
  to the purchase phase.
