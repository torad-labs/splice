// nd-providers: providers.html is the approved Providers page on the kit, on live reads. Each command splice runs is a
// part on the field, named by its command; pressing it grows its glass with its model mapping; Add provider and Compare
// models are parts of the same field; no rails and no composer.
import { afterAll, beforeAll, test } from "bun:test";
import { expect, type Browser } from "@playwright/test";
import { browser, daemon, expectFrame, lens, open, WIDE, world, type Daemon } from "./lib/deck.ts";

let b: Browser;
let d: Daemon;
beforeAll(async () => { b = await browser(); d = daemon(world()); }, 30_000);
afterAll(async () => { await b?.close(); d?.stop(); });

test("Providers has the shell the prototype has: no rails, no composer", async () => {
  await expectFrame(b, d, "providers.html", { current: "Providers", railL: false, railR: false, composer: false });
}, 90_000);

test("each command is a part; its glass holds the model mapping; Add provider and Compare models open", async () => {
  for (const size of [undefined, WIDE]) {
    const o = await open(b, d, "providers.html", size);
    try {
      const acme = o.page.locator('[data-key="claude-acme"]');
      await expect(acme).toHaveCount(1, { timeout: 10_000 });
      await expect(acme).toContainText("claude-acme");
      await expect(o.page.locator('[data-key="claude"]')).toHaveCount(1);
      await acme.click();
      const l = await lens(o.page);
      await expect(l.locator(".lr", { hasText: "Model mapping" }).first()).toBeVisible();
      await o.page.keyboard.press("Escape");
      await expect(o.page.locator(".lens:not([hidden])")).toHaveCount(0, { timeout: 5_000 });
      await o.page.locator('[data-part="compare"]').click();
      await expect((await lens(o.page)).locator(".cmp")).toBeVisible();
      await o.page.keyboard.press("Escape");
      await expect(o.page.locator(".lens:not([hidden])")).toHaveCount(0, { timeout: 5_000 });
      await o.page.locator('[data-part="add"]').click();
      await expect((await lens(o.page))).toContainText(/API key|Sign in/);
      expect(o.errors).toEqual([]);
    } finally {
      await o.close();
    }
  }
}, 60_000);
