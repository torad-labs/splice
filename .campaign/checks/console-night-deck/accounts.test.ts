// nd-accounts: accounts.html is the approved Accounts page on the kit, on live reads. Each Claude account is a lane on
// the time axis; pressing its name grows its glass, titled with that name; the left rail holds the account serving now
// and the right rail the other providers; both rails and the composer are there; the old page's own files are gone.
import { afterAll, beforeAll, test } from "bun:test";
import { expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { browser, CONSOLE, daemon, expectFrame, lens, open, WIDE, world, type Daemon } from "./lib/deck.ts";

let b: Browser;
let d: Daemon;
beforeAll(async () => { b = await browser(); d = daemon(world()); }, 30_000);
afterAll(async () => { await b?.close(); d?.stop(); });

test("Accounts has the shell the prototype has: both rails and the composer", async () => {
  await expectFrame(b, d, "accounts.html", { current: "Accounts", railL: true, railR: true, composer: true });
}, 90_000);

test("each account is a lane, shown by its name, and its name opens its glass", async () => {
  for (const size of [undefined, WIDE]) {
    const o = await open(b, d, "accounts.html", size);
    try {
      await expect(o.page.locator(".lane")).toHaveCount(2, { timeout: 10_000 });
      await expect(o.page.locator(".lane .mark .nm", { hasText: "Acme work" })).toHaveCount(1);
      await expect(o.page.locator(".lane .mark .nm", { hasText: "Acme home" })).toHaveCount(1);
      await expect(o.page.locator("#serving")).toBeVisible();
      await o.page.locator(".lane .mark .nm", { hasText: "Acme home" }).click();
      const l = await lens(o.page);
      await expect(l.locator("h3")).toHaveText("Acme home");
      await o.page.keyboard.press("Escape");
      await expect(o.page.locator(".lens:not([hidden])")).toHaveCount(0, { timeout: 5_000 });
      expect(o.errors).toEqual([]);
    } finally {
      await o.close();
    }
  }
}, 60_000);

test("the old Accounts page's own script and stylesheet are gone", () => {
  for (const f of ["accounts.js", "accounts.css"]) expect(existsSync(join(CONSOLE, f)), f).toBe(false);
});
