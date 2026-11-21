// nd-activity: activity.html is the approved Activity page on the kit, on live reads. The figures above the week open
// their glass; Latest requests lists the requests, a failed one saying why in the page's words; pressing a request
// grows its glass with its timing on one bar; the address names a filter and a request; no rails, no composer.
import { afterAll, beforeAll, test } from "bun:test";
import { expect, type Browser } from "@playwright/test";
import { browser, daemon, expectFrame, lens, open, WIDE, world, type Daemon } from "./lib/deck.ts";

let b: Browser;
let d: Daemon;
beforeAll(async () => { b = await browser(); d = daemon(world()); }, 30_000);
afterAll(async () => { await b?.close(); d?.stop(); });

test("Activity has the shell the prototype has: no rails, no composer", async () => {
  await expectFrame(b, d, "activity.html", { current: "Activity", railL: false, railR: false, composer: false });
}, 90_000);

test("the latest requests are listed, a refused one says Rate limited, and a request opens with its timing", async () => {
  for (const size of [undefined, WIDE]) {
    const o = await open(b, d, "activity.html", size);
    try {
      await expect(o.page.locator(".rw")).toHaveCount(2, { timeout: 10_000 });
      await expect(o.page.locator(".rw.is-bad")).toHaveCount(1);
      await expect(o.page.locator(".rw.is-bad")).toContainText("Rate limited");
      expect(await o.page.locator("[data-fig]").count()).toBeGreaterThanOrEqual(5);
      await o.page.locator("[data-fig]").first().click();
      await lens(o.page);
      await o.page.keyboard.press("Escape");
      await expect(o.page.locator(".lens:not([hidden])")).toHaveCount(0, { timeout: 5_000 });
      await o.page.locator(".rw:not(.is-bad)").click();
      const l = await lens(o.page);
      await expect(l.locator(".rail-bar i")).toHaveCount(3);
      await expect(l).toContainText("Time to first token");
      expect(o.errors).toEqual([]);
    } finally {
      await o.close();
    }
  }
}, 60_000);

test("the address names a session to filter by, and a request to open", async () => {
  const o = await open(b, d, "activity.html?session=bbbb2222");
  try {
    await expect(o.page.locator(".ac-filter")).toBeVisible({ timeout: 10_000 });
    await expect(o.page.locator(".rw")).toHaveCount(1);
    await expect(o.page.locator(".rw")).toContainText("Rate limited");
  } finally {
    await o.close();
  }
  const r = await open(b, d, "activity.html#request=r-ok");
  try {
    await expect((await lens(r.page)).locator(".rail-bar i")).toHaveCount(3);
  } finally {
    await r.close();
  }
}, 60_000);
