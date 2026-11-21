// nd-sessions-board: sessions.html is the approved Sessions board on the kit, on live reads. Each team is a column
// with its sessions as rows; sessions on no team sit in a column of their own; the right rail and the composer are
// there and no left rail; the old Sessions page's own files are gone.
import { afterAll, beforeAll, test } from "bun:test";
import { expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { browser, CONSOLE, daemon, expectFrame, open, WIDE, world, type Daemon } from "./lib/deck.ts";

let b: Browser;
let d: Daemon;
beforeAll(async () => { b = await browser(); d = daemon(world()); }, 30_000);
afterAll(async () => { await b?.close(); d?.stop(); });

test("Sessions has the shell the prototype has: the right rail and the composer, no left rail", async () => {
  await expectFrame(b, d, "sessions.html", { current: "Sessions", railL: false, railR: true, composer: true });
}, 90_000);

test("each team is a column of its sessions, and sessions on no team have their own", async () => {
  for (const size of [undefined, WIDE]) {
    const o = await open(b, d, "sessions.html", size);
    try {
      const team = o.page.locator("#board section.team", { has: o.page.locator(".team-name", { hasText: "harbor-fix" }) });
      await expect(team).toHaveCount(1, { timeout: 10_000 });
      await expect(team.locator(".srow", { hasText: "api-fixer" })).toHaveCount(1);
      await expect(team.locator(".srow", { hasText: "map-builder" })).toHaveCount(1);
      const loose = o.page.locator("#board section.team.loose");
      await expect(loose.locator(".srow", { hasText: "docs-writer" })).toHaveCount(1);
      await expect(o.page.locator("aside.rail.rail-r")).not.toHaveText(/^\s*$/);
      expect(o.errors).toEqual([]);
    } finally {
      await o.close();
    }
  }
}, 60_000);

test("the old Sessions page's own script and stylesheet are gone", () => {
  for (const f of ["sessions.js", "sessions.css"]) expect(existsSync(join(CONSOLE, f)), f).toBe(false);
});
