// A group line over a run of bursts with one cause and one reset counts them as two pills, "N bursts" and "N requests",
// and each is a door to what it counts: "bursts" brings the group's own line under the top bar, "requests" opens every burst
// of the group in place (Requests list, approved prototype, finish pass 2026-10-29). Through the real page against a
// stub daemon whose plan-limit rows form two bursts of three in one hour, sharing one reset.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const hourStart = Math.floor(Date.now() / 3_600_000) * 3_600_000;
const reset = Math.floor((Date.now() + 3 * 3_600_000) / 1000);
// two bursts of three, the second a minute before the first, all in the hour that has just ended (a burst never spans two)
const limited = (id: string, agoS: number) => ({
  turn: id, turn_id: id, ts: hourStart - agoS * 1000, outcome: "error:plan-limit", model: "m", session: "0c3b07d2", total: 300, attempts: 1, retries: 0,
  earliest_reset_epoch_seconds: reset,
});
const rows = [limited("a1", 10), limited("a2", 11), limited("a3", 12), limited("b1", 60), limited("b2", 61), limited("b3", 62)];
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-alpha" }] });
    if (path === "/api/perf/turns") return Response.json({ heads: [{ key: "alpha", label: "alpha", count: rows.length, returned: rows.length, truncated: false, rows, usage: { models: [], accounts: [], sessions: [] } }] });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox"] });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

async function open() {
  const page = await browser.newPage({ viewport: { width: 1440, height: 420 }, reducedMotion: "reduce" });
  await page.goto(`http://127.0.0.1:${server.port}/requests.html`);
  await page.locator(".rqgroup").waitFor({ timeout: 15_000 });
  return page;
}

test("the group line's requests pill opens every burst of the group", async () => {
  const page = await open();
  try {
    const pill = page.locator('.rqgroup [data-act="grp"][data-v="requests"]');
    expect(await pill.getAttribute("title")).toBe("Show these requests");
    expect(await page.locator('.rqburst[aria-expanded="true"]').count()).toBe(0);
    await pill.click();
    await expect.poll(() => page.locator('.rqburst[aria-expanded="true"]').count()).toBe(2);
    expect(await page.locator(".rq.inburst").count()).toBe(6);
  } finally {
    await page.close();
  }
}, 30_000);

test("the group line's bursts pill brings the group's line into view", async () => {
  const page = await open();
  try {
    await page.evaluate(() => scrollTo(0, 0));
    const pill = page.locator('.rqgroup [data-act="grp"][data-v="bursts"]');
    expect(await pill.getAttribute("title")).toBe("Show these bursts");
    await pill.click();
    await expect.poll(() => page.locator(".rqgroup").evaluate((e) => Math.round(e.getBoundingClientRect().top))).toBeLessThan(200);
    expect(await page.locator('.rqburst[aria-expanded="true"]').count()).toBe(0);
  } finally {
    await page.close();
  }
}, 30_000);
