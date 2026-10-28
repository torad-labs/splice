// Requests orders Newest or Highest price (fin's words, hitstop 20bfada; Marlin took price over tokens). Through the real
// page against a daemon that keeps each command's highest priced when asked order=price and says so in `order`
// (splice-builder): the list runs dearest first with unpriced last, no hour marks and a day on each row, a plan row draws
// its worth at API prices in its cost column, hollow, with the list's key saying once what hollow means, and the held
// line (a meter and "N highest priced of M requests", its rule under the pointer) says the highest priced were kept. A
// daemon that does not echo the order kept the newest, and the page says Newest over them, never Highest price.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const turn = (id: string, agoS: number, price: { cost?: number; plan?: number }) => ({
  turn: id, turn_id: id, ts: now - agoS * 1000, outcome: "ok", model: "m", session: "0c3b07d2", total: 2_000, attempts: 1, retries: 0,
  ...(price.cost != null ? { cost_usd: price.cost } : {}),
  ...(price.plan != null ? { plan_value_usd: price.plan, cost_reason: "plan" } : {}),
});
// newest to oldest: a cheap key request, a dear plan request, an unpriced one, the dearest key request
const ROWS = [turn("cheap", 30, { cost: 0.02 }), turn("plan", 90, { plan: 0.5 }), turn("none", 150, {}), turn("dear", 5_000, { cost: 1.2 })];
const asked: string[] = [];
let echo = true;
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-alpha" }] });
    if (path === "/api/heads/retired") return Response.json({ retired: [] });
    if (path === "/api/perf/turns") {
      asked.push(url.search);
      const price = url.searchParams.get("order") === "price";
      const rows = Number(url.searchParams.get("n")) === 1 ? [] : ROWS;
      return Response.json({ heads: [{ key: "alpha", label: "alpha", ...(echo ? { order: price ? "price" : "newest" } : {}),
        count: 2_400, returned: rows.length, truncated: true, rows, usage: { models: [], accounts: [], sessions: [] } }] });
    }
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

async function look(query: string) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/requests.html${query}`);
    await page.locator('article[data-open="alpha:cheap"]').waitFor({ timeout: 15_000 });
    return {
      order: await page.locator(".rq").evaluateAll((els) => els.map((e) => e.getAttribute("data-open"))),
      pressed: await page.locator('.switch.order [aria-pressed="true"]').innerText(),
      hours: await page.locator(".list .hour").count(),
      // a plan's worth at API prices stands in its cost column as a hollow bar, and the list's key says once what hollow
      // means, where its top line said "On your plan" with the worth beside it (Requests' goal, Oct 26)
      plan: await page.locator('article[data-open="alpha:plan"] .top').innerText(),
      planCost: await page.locator('article[data-open="alpha:plan"] .co.plan .v').innerText(),
      key: await page.locator(".lkey").innerText(),
      costHead: await page.getByText("Est. cost", { exact: true }).count(),
      foot: (await page.locator(".foot.held").innerText()).replace(/\s+/g, " "),
      footRule: await page.locator(".foot.held").getAttribute("title"),
    };
  } finally {
    await page.close();
  }
}

test("Highest price runs dearest first, unpriced last, with no hour marks, a plan's worth in its cost column, hollow, and says so", async () => {
  echo = true;
  asked.length = 0;
  const seen = await look("?order=price");
  expect(asked.some((q) => q.includes("order=price"))).toBe(true);
  expect(seen.order).toEqual(["alpha:dear", "alpha:plan", "alpha:cheap", "alpha:none"]);
  expect(seen.pressed).toBe("Highest price");
  expect(seen.hours).toBe(0);
  expect(seen.planCost).toBe("$0.500"); // no "≈" on the cell: its column's head says the cost is estimated
  expect(seen.costHead).toBeGreaterThan(0);
  expect(seen.key).toContain("On your plan, at API prices");
  expect(seen.foot).toBe("4 highest priced of 2,400 requests");
  expect(seen.footRule).toBe("The highest priced 2,000 of each command");
}, 30_000);

test("Newest keeps time order, its hour marks and one line on a plan row", async () => {
  echo = true;
  const seen = await look("");
  expect(seen.order).toEqual(["alpha:cheap", "alpha:plan", "alpha:none", "alpha:dear"]);
  expect(seen.pressed).toBe("Newest");
  expect(seen.hours).toBeGreaterThan(0);
  expect(seen.plan).not.toContain("at API prices");
  expect(seen.foot).toContain("newest");
}, 30_000);

test("a daemon that does not say it applied the order reads Newest over its newest rows", async () => {
  echo = false;
  const seen = await look("?order=price");
  expect(seen.pressed).toBe("Newest");
  expect(seen.order).toEqual(["alpha:cheap", "alpha:plan", "alpha:none", "alpha:dear"]);
}, 30_000);
