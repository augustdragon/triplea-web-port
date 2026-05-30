import { test, expect, type Page } from "@playwright/test";

/**
 * End-to-end coverage of the web-port politics feature against a live game server:
 *   - the app loads the map and connects to the running game as Japan;
 *   - the politics phase offers the four Pacific 1940 declarations of war;
 *   - the relationships grid shows the correct opening matrix;
 *   - staging a declaration is reversible (in-phase undo) before committing;
 *   - committing applies it (relationship flips to War) and advances to purchase.
 *
 * Run order matters: every test re-navigates and waits for Japan's politics phase, so the
 * destructive commit test is last (it ends the phase). See e2e/README.md for prerequisites — chiefly
 * a FRESH game server (the engine holds state; a committed declaration can't be taken back).
 */

const DECLARATIONS = {
  french: "Declare war on French",
  americans: "Declare war on Americans",
  allWest: "Declare war on Americans, British, ANZAC, Dutch, French",
  britishAnzacDutch: "Declare war on British, ANZAC, Dutch",
} as const;

/** Load the client and wait until Japan's politics phase is on screen (proves WS + state + request). */
async function gotoPolitics(page: Page): Promise<void> {
  await page.goto("/");
  await expect(
    page.getByText("Politics — Japanese"),
    "Politics phase didn't appear — is the game server running a FRESH game as Japanese on :8080? " +
      "Run: powershell scripts\\restart-web.ps1 -Player Japanese",
  ).toBeVisible({ timeout: 25_000 });
}

test.describe.serial("web-port politics flow", () => {
  test.beforeEach(async ({ page }) => {
    await gotoPolitics(page);
  });

  test("loads the map and connects to the live game as Japan", async ({ page }) => {
    await expect(page.locator("canvas")).toBeVisible();
    await expect(page.getByText("Pacific 1940")).toBeVisible();
    await expect(page.getByText("Japanese").first()).toBeVisible();
    // Relationships is now a dock tab (was a sidebar button + modal).
    await expect(page.getByRole("button", { name: "Relationships" })).toBeVisible();
  });

  test("offers the four Pacific declarations of war and an end-phase button", async ({ page }) => {
    for (const name of Object.values(DECLARATIONS)) {
      await expect(page.getByRole("button", { name, exact: true })).toBeVisible();
    }
    await expect(page.getByRole("button", { name: "End Politics Phase" })).toBeVisible();
  });

  test("relationships grid shows the correct opening matrix", async ({ page }) => {
    await page.getByRole("button", { name: "Relationships" }).click();
    const grid = page.getByTestId("relationships-grid");
    await expect(grid).toBeVisible();

    // Opening state: only Japan–China at war; the Western Allies cooperate; everyone else neutral.
    await expect(grid.getByTestId("rel-Japanese-Chinese")).toHaveText("War");
    await expect(grid.getByTestId("rel-Japanese-Americans")).toHaveText("Neutrality");
    await expect(grid.getByTestId("rel-British-ANZAC")).toHaveText("Allied");
    await expect(grid.getByTestId("rel-British-Dutch")).toHaveText("Custodianship");
    // Symmetric: reading the mirror cell gives the same relationship.
    await expect(grid.getByTestId("rel-Chinese-Japanese")).toHaveText("War");
  });

  test("staging a declaration is reversible before committing", async ({ page }) => {
    const french = page.getByRole("button", { name: DECLARATIONS.french, exact: true });
    await french.click();

    // It moves into the staged queue: a heading, an Undo button, and the end button shows the count.
    await expect(page.getByText("Staged — applied when you end the phase:")).toBeVisible();
    await expect(page.getByRole("button", { name: "Undo" })).toBeVisible();
    await expect(page.getByRole("button", { name: /End phase & apply 1/ })).toBeVisible();
    // ...and it's no longer in the available list.
    await expect(french).toHaveCount(0);

    // Undo restores it and resets the end button — nothing was sent to the engine.
    await page.getByRole("button", { name: "Undo" }).click();
    await expect(page.getByRole("button", { name: DECLARATIONS.french, exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "End Politics Phase" })).toBeVisible();
  });

  // DESTRUCTIVE: this declares war on France for real and ends the politics phase. Keep it last; a
  // re-run needs a fresh game server (see e2e/README.md).
  test("committing a declaration applies it and advances to purchase", async ({ page }) => {
    const french = page.getByRole("button", { name: DECLARATIONS.french, exact: true });
    test.skip(
      (await french.count()) === 0,
      "France already at war — restart the game server for a fresh run.",
    );

    await french.click();
    await page.getByRole("button", { name: /End phase & apply 1/ }).click();

    // The phase advances: the politics panel is replaced by the purchase panel.
    await expect(page.getByText("Politics — Japanese")).toBeHidden();
    await expect(page.getByText("Purchase — Japanese")).toBeVisible({ timeout: 15_000 });

    // The relationship is now War (the declaration took effect in the engine).
    await page.getByRole("button", { name: "Relationships" }).click();
    await expect(page.getByTestId("rel-Japanese-French")).toHaveText("War");
  });
});
