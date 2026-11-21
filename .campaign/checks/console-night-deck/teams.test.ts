// nd-teams: teams.html is the approved Teams page on the kit, on live reads. Each team is a band with its name and goal
// and its members on a map; pressing a member grows its glass (role, model, login, instructions); under the teams, the
// running sessions on no team and the ended teams; no rails and no composer; the old page's own files are gone.
import { afterAll, beforeAll, test } from "bun:test";
import { expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { browser, CONSOLE, daemon, expectFrame, lens, open, WIDE, world, type Daemon } from "./lib/deck.ts";

let b: Browser;
let d: Daemon;
beforeAll(async () => { b = await browser(); d = daemon(world()); }, 30_000);
afterAll(async () => { await b?.close(); d?.stop(); });

test("Teams has the shell the prototype has: no rails, no composer", async () => {
  await expectFrame(b, d, "teams.html", { current: "Teams", railL: false, railR: false, composer: false });
}, 90_000);

test("a team is a band of its name, goal and members, and a member opens its glass", async () => {
  for (const size of [undefined, WIDE]) {
    const o = await open(b, d, "teams.html", size);
    try {
      const band = o.page.locator('.band[data-team="t1"]');
      await expect(band).toHaveCount(1, { timeout: 10_000 });
      await expect(band).toContainText("harbor-fix");
      await expect(band.locator(".tc-goal")).toContainText("Ship the harbor map");
      await expect(band.locator(".mb")).toHaveCount(2);
      await expect(o.page.locator('.band[data-team="t2"]')).toHaveCount(0);
      await band.locator("[data-member]").first().click();
      const l = await lens(o.page);
      for (const row of ["Role", "Model", "Login", "Instructions"]) await expect(l.locator(".lr", { hasText: row }).first()).toBeVisible();
      await o.page.keyboard.press("Escape");
      await expect(o.page.locator('[data-open="loose"]')).toContainText("Running, not on a team");
      await expect(o.page.locator('[data-open="loose"]')).toContainText("1");
      await expect(o.page.locator('[data-open="ended"]')).toContainText("Ended teams");
      await o.page.locator('[data-open="ended"]').click();
      await expect((await lens(o.page))).toContainText("old-crew");
      expect(o.errors).toEqual([]);
    } finally {
      await o.close();
    }
  }
}, 60_000);

test("the old Teams page's own script and stylesheet are gone", () => {
  for (const f of ["teams.js", "teams.css"]) expect(existsSync(join(CONSOLE, f)), f).toBe(false);
});
