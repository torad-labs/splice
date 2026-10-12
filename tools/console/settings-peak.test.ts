// Settings' "Many agents at once": a busy command's day holds more requests than one read returns, so the daemon works
// out its peak over the whole day and the page draws that figure. The commands that ran lead as cards, the ones that
// held nothing collapse to a line, and a setting some commands override spreads those commands across the width
// (Marcos's verdict on this pane, Oct 11). Through the real page against a stub daemon.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const day = Date.now() - 3_600_000;
const PEAK: Record<string, number> = { alpha: 7, beta: 3, gamma: 0 };
// four commands with their own "Requests waiting in line": enough to tell a row across the width from a stack
const OVER = ["one-cmd", "two-cmd", "three-cmd", "four-cmd"];
const perHead = Object.fromEntries(OVER.map((c, i) => [c, { maxQueued: 100 + i }]));
// the daemon's answer to a peak question: the whole day counted, the rows only the newest one
const turns = (key: string) => ({
  heads: [{ key, label: key, count: 3_400, returned: 1, truncated: true, rows: [], at_once: { peak: PEAK[key] ?? 0, at: day, counted: 3_400, queued: key === "alpha" ? 12_345 : 0 } }],
});
const asked: string[] = [];
const server = Bun.serve({
  port: Number(process.env.SETTINGS_PEAK_PORT ?? 0),
  fetch(req) {
    const url = new URL(req.url);
    const path = url.pathname;
    if (path === "/api/heads") return Response.json({ heads: Object.keys(PEAK).map((key) => ({ key, label: key })) });
    if (path === "/api/models") return Response.json({ heads: [] });
    if (path === "/api/config") return Response.json({ layers: { perHead } });
    if (path === "/api/perf/turns") {
      if (url.searchParams.get("peak") === "1") asked.push(url.searchParams.get("head") ?? "");
      return Response.json(turns(url.searchParams.get("head") ?? ""));
    }
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "settings.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({
    executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined,
    args: ["--no-sandbox", "--disable-background-networking"],
  });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

/** The pane, open, with its Today section drawn. */
async function open(width: number, height = 1000) {
  const page = await browser.newPage({ viewport: { width, height } });
  await page.goto(`http://127.0.0.1:${server.port}/settings.html`);
  await page.getByText("Many agents at once").first().click();
  await expect(page.locator(".pcards .pcard").first()).toBeVisible({ timeout: 15_000 });
  return page;
}

test("a command whose day holds more requests than one read returns still shows its peak, and nothing says not read", async () => {
  const page = await open(1440, 900);
  try {
    const today = page.locator(".tally").filter({ hasText: "Today" }).first();
    const alpha = today.locator(".pcard").filter({ hasText: "alpha" });
    await expect(alpha.locator(".pbig b")).toHaveText("7");
    await expect(alpha.locator(".pof")).toContainText("at once");
    await expect(today.locator(".pcard").filter({ hasText: "beta" }).locator(".pbig b")).toHaveText("3");
    await expect(today).not.toContainText("not read");
    await expect(today).not.toContainText("over 2,000");
    expect(asked.sort()).toEqual(["alpha", "beta", "gamma"]);
  } finally {
    await page.close();
  }
}, 30_000);

test("a command that held nothing today is named on one line, with no card and no bar of its own", async () => {
  const page = await open(1440, 900);
  try {
    const today = page.locator(".tally").filter({ hasText: "Today" }).first();
    await expect(today.locator(".pidle")).toContainText("1 command held nothing today");
    await expect(today.locator(".pidle")).toContainText("gamma");
    expect(await today.locator(".pcard").count()).toBe(2); // alpha and beta, never gamma
    await expect(today.locator(".pcard").filter({ hasText: "gamma" })).toHaveCount(0);
  } finally {
    await page.close();
  }
}, 30_000);

// A command's "Waited in line" door keeps its words on one line inside its card however many digits the count has
// ("2,098" broke it into "Waited in / line" and "Open on / Requests" live at 3828, e2dfed21a).
for (const width of [3828, 1440]) {
  test(`at ${width} a five-digit Waited in line door stays one line inside its card`, async () => {
    const page = await open(width);
    try {
      const door = page.locator(".pcard .door").filter({ hasText: "12,345" });
      await expect(door).toBeVisible({ timeout: 15_000 });
      const m = await page.evaluate(() => {
        const d = [...document.querySelectorAll(".pcard .door")].find((e) => /12,345/.test(e.textContent ?? ""))!;
        const card = d.closest(".pcard")!.getBoundingClientRect(), box = d.getBoundingClientRect();
        const tops = [...d.querySelectorAll("span, b, em")].map((e) => Math.round(e.getBoundingClientRect().height));
        return { lineH: parseFloat(getComputedStyle(d).fontSize) * 2, tallest: Math.max(...tops), inside: box.right <= card.right + 1 };
      });
      expect(m.tallest).toBeLessThan(m.lineH); // no part of the door wraps to a second line
      expect(m.inside).toBe(true);
    } finally {
      await page.close();
    }
  }, 30_000);
}

test("at 3828 the commands that override a setting lie across the row, not stacked in a column at its left", async () => {
  const page = await open(3828, 2130);
  try {
    await expect(page.locator(".kn .over").first()).toBeVisible({ timeout: 15_000 });
    const m = await page.evaluate(() => {
      const ctl = [...document.querySelectorAll(".kn .ctl.col")].find((c) => c.querySelectorAll(".over").length >= 4)!;
      const cells = [...ctl.querySelectorAll(".over")].map((e) => e.getBoundingClientRect());
      const form = ctl.closest(".form")!.getBoundingClientRect();
      return {
        rows: new Set(cells.map((b) => Math.round(b.top))).size,
        reach: (Math.max(...cells.map((b) => b.right)) - form.left) / form.width,
        inside: cells.every((b) => b.right <= form.right + 1),
      };
    });
    expect(m.rows).toBe(1); // all four on one line
    expect(m.reach).toBeGreaterThan(0.6); // and that line uses the width it was given
    expect(m.inside).toBe(true);
  } finally {
    await page.close();
  }
}, 30_000);

test("at 1440 the same commands wrap inside the form instead of running past its edge", async () => {
  const page = await open(1440, 900);
  try {
    await expect(page.locator(".kn .over").first()).toBeVisible({ timeout: 15_000 });
    const m = await page.evaluate(() => {
      const ctl = [...document.querySelectorAll(".kn .ctl.col")].find((c) => c.querySelectorAll(".over").length >= 4)!;
      const cells = [...ctl.querySelectorAll(".over")].map((e) => e.getBoundingClientRect());
      const form = ctl.closest(".form")!.getBoundingClientRect();
      return { rows: new Set(cells.map((b) => Math.round(b.top))).size, inside: cells.every((b) => b.right <= form.right + 1) };
    });
    expect(m.rows).toBeGreaterThan(1);
    expect(m.inside).toBe(true);
  } finally {
    await page.close();
  }
}, 30_000);
