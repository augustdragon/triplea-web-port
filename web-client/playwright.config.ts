import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright config for the TripleA web client (web-port).
 *
 * What it drives: the DOM-based decision flow — the politics phase, the relationships grid, staged
 * declarations + undo, and the advance into purchase. The map itself is a <canvas>, so move / place
 * / battle (which need pixel-coordinate clicks) are out of scope here; see e2e/README.md.
 *
 * Prerequisites (the test asserts these and fails fast with guidance if missing):
 *   1. The Java game server running a FRESH game as Japan on :8080 —
 *        powershell scripts\restart-web.ps1 -Player Japanese
 *      (fresh because committing a declaration is one-way; see e2e/README.md).
 *   2. web-client/public/geometry.json present (exported by :game-web-server:exportGeometry).
 *
 * The Vite dev server (:5173) is started automatically by the webServer block below, reusing one if
 * it's already running.
 */
export default defineConfig({
  testDir: "./e2e",
  // The politics → relationships → commit tests share one game's server state, so run serially.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: [["list"], ["html", { open: "never" }]],
  timeout: 30_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: "http://localhost:5173",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "npm run dev",
    url: "http://localhost:5173",
    reuseExistingServer: true,
    timeout: 60_000,
  },
});
