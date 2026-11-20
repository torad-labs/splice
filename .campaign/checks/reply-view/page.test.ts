// rv-page: the reader's own page, served beside the console at /reply-view/index.html, shows one session through the
// reader and its connector: its name, and its history as the reader draws it, in night and day (the console's own theme
// choice), at 375 and 1440 px, under a strict CSP. With no session named it lists the sessions to open; a browser that is
// not signed in is told how to sign in. (A reply streaming into the page and settling is rv-settle's check.)
import { afterAll, beforeAll, expect, test } from "bun:test";
import { expect as pw, type Browser } from "@playwright/test";
import { browser, DESK, open, PHONE, readable, SESSION, SESSIONS, spills, stub, THEMES, WORLD, type Stub } from "./lib/rv.ts";

let b: Browser;
let st: Stub;
beforeAll(async () => {
  b = await browser();
  st = stub({ transcripts: { [SESSION]: [...WORLD] }, answers: { "/api/sessions": SESSIONS } });
}, 30_000);
afterAll(async () => {
  await b?.close();
  st?.stop();
});

const PAGE = `/reply-view/index.html?session=${SESSION}`;

for (const size of [PHONE, DESK]) {
  test(`at ${size.width} px, night and day, the page shows the session through the reader with nothing spilling`, async () => {
    const bg: Record<string, string> = {};
    for (const theme of THEMES) {
      const o = await open(b, st, PAGE, size, theme);
      try {
        await pw(o.page.locator(".rv .rv-assistant", { hasText: "split on tabs only" })).toBeVisible({ timeout: 10_000 });
        await pw(o.page.locator("body")).toContainText("api-fixer");
        const text = await readable(o.page, "body");
        for (const never of ["system-reminder", "Made-up reminder", '{"', "command-name"]) expect(text, `shows ${never}`).not.toContain(never);
        expect(await o.page.locator(".rv details.rv-tool").evaluateAll((els) => els.every((d) => !(d as HTMLDetailsElement).open))).toBe(true);
        expect(await o.page.evaluate(() => document.documentElement.dataset.theme)).toBe(theme);
        expect(await spills(o.page)).toEqual([]);
        bg[theme] = await o.page.evaluate(() => getComputedStyle(document.body).backgroundColor);
        expect(o.errors).toEqual([]);
        expect(await o.csp()).toEqual([]);
      } finally {
        await o.close();
      }
    }
    expect(bg.night).not.toBe(bg.day);
  }, 120_000);
}

test("with no session named it lists the sessions, each opening its conversation", async () => {
  const o = await open(b, st, "/reply-view/index.html", DESK, "day");
  try {
    const link = o.page.locator(`a[href*="session=${SESSION}"]`);
    await pw(link).toContainText("api-fixer", { timeout: 10_000 });
    await pw(o.page.locator('a[href*="session=bbbb2222"]')).toContainText("map-builder");
    await link.click();
    await pw(o.page.locator(".rv .rv-assistant", { hasText: "split on tabs only" })).toBeVisible({ timeout: 10_000 });
    expect(o.errors).toEqual([]);
  } finally {
    await o.close();
  }
}, 60_000);

test("a browser that is not signed in is told how to sign in", async () => {
  const o = await open(b, st, PAGE, PHONE, "night", { key: false });
  try {
    await pw(o.page.locator("body")).toContainText("This browser is not signed in to splice.", { timeout: 10_000 });
    await pw(o.page.locator("body")).toContainText("splice console");
    expect(await spills(o.page)).toEqual([]);
    expect(o.errors).toEqual([]);
  } finally {
    await o.close();
  }
}, 60_000);

