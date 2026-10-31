// Settings' "Many agents at once": a busy command's day holds more requests than one read returns, so the daemon works
// out its peak over the whole day and the page draws that figure. Today is one table, a row per command that ran, the
// limit said once in the column's head; the commands that held nothing stand together under one "No requests" label, each
// a quiet row with the empty meter it is (Settings' goal, Oct 28: a list of names says nothing a table's shape does not),
// and the commands that hold their own value for a setting read as chips that lie across its row and wrap inside it, never
// cut (gap 457, Oct 13). Through the real page against a stub daemon.
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

/** The section of the pane under this kicker. */
const section = (page: Awaited<ReturnType<typeof open>>, kicker: string) =>
  page.locator("section.psec", { has: page.locator(`.sechead > .kicker:text-is("${kicker}")`) });

/** The pane, open, with its Today table drawn. */
async function open(width: number, height = 1000) {
  const page = await browser.newPage({ viewport: { width, height } });
  await page.goto(`http://127.0.0.1:${server.port}/settings.html`);
  await page.getByText("Many agents at once").first().click();
  await expect(page.locator(".peaks .pk[data-grow]").first()).toBeVisible({ timeout: 15_000 });
  return page;
}

test("a command whose day holds more requests than one read returns still shows its peak, and nothing says not read", async () => {
  const page = await open(1440, 900);
  try {
    const today = section(page, "Today");
    const alpha = today.locator(".pk[data-grow]").filter({ hasText: "alpha" });
    await expect(alpha.locator(".pmv b")).toHaveText("7");
    // every command shares the one limit, so the column's head says "of 12" once and no row repeats it
    await expect(today.locator(".ph")).toContainText("of 12");
    await expect(alpha.locator(".pmv")).not.toContainText("of 12");
    await expect(today.locator(".pk[data-grow]").filter({ hasText: "beta" }).locator(".pmv b")).toHaveText("3");
    await expect(today).not.toContainText(/not read/i);
    await expect(today).not.toContainText("over 2,000");
    expect(asked.sort()).toEqual(["alpha", "beta", "gamma"]);
  } finally {
    await page.close();
  }
}, 30_000);

test("a command that held nothing today stands under one No requests label as a quiet row with an empty meter and no figure", async () => {
  const page = await open(1440, 900);
  try {
    const today = section(page, "Today");
    // the label is said once, over the commands that held nothing, after the ones that did
    await expect(today.locator(".pk.pg")).toHaveText("No requests");
    const quiet = today.locator(".pk.quiet");
    await expect(quiet).toHaveCount(1);
    await expect(quiet).toContainText("gamma");
    // its meter is empty and it states no peak, no time and no count
    await expect(quiet.locator(".pm .bar b")).toHaveAttribute("data-v", "0");
    await expect(quiet.locator(".pmv")).toHaveText("");
    await expect(quiet.locator(".pclk")).toHaveText("");
    // alpha and beta, which held requests, draw their figures and stand above the label
    await expect(today.locator(".pk:not(.quiet)[data-grow]")).toHaveCount(2);
    const y = await today.evaluate((el) => {
      const top = (sel: string) => el.querySelector(sel)!.getBoundingClientRect().top;
      return { beta: top('.pk[data-grow="peak-beta"]'), label: top(".pk.pg"), gamma: top('.pk[data-grow="peak-gamma"]') };
    });
    expect(y.beta).toBeLessThan(y.label);
    expect(y.label).toBeLessThan(y.gamma);
  } finally {
    await page.close();
  }
}, 30_000);

// A command's "Waited in line" door keeps its words on one line inside the table however many digits the count has
// ("2,098" broke it into "Waited in / line" and "Open on / Requests" live at 3828, e2dfed21a).
for (const width of [3828, 1440]) {
  test(`at ${width} a five-digit Waited in line door stays one line inside its table`, async () => {
    const page = await open(width);
    try {
      const door = page.locator(".peaks .pq .pd-count").filter({ hasText: "12,345" });
      await expect(door).toBeVisible({ timeout: 15_000 });
      const m = await page.evaluate(() => {
        const d = [...document.querySelectorAll(".peaks .pq .pd-count")].find((e) => /12,345/.test(e.textContent ?? ""))!;
        const table = d.closest(".peaks")!.getBoundingClientRect(), box = d.getBoundingClientRect();
        const tops = [...d.querySelectorAll("span, b, em, svg")].map((e) => Math.round(e.getBoundingClientRect().height));
        return { lineH: parseFloat(getComputedStyle(d).fontSize) * 2, tallest: Math.max(box.height, ...tops), inside: box.left >= table.left - 1 && box.right <= table.right + 1 };
      });
      expect(m.tallest).toBeLessThan(m.lineH); // no part of the door wraps to a second line
      expect(m.inside).toBe(true);
    } finally {
      await page.close();
    }
  }, 30_000);
}

/** Where the four commands holding their own value sit, as chips, measured against the setting's row that holds them. */
async function chipShape(page: Awaited<ReturnType<typeof open>>) {
  return page.evaluate(() => {
    const owners = [...document.querySelectorAll(".kn .owners")].find((o) => o.querySelectorAll(".chip.own").length >= 4)!;
    const chips = [...owners.querySelectorAll(".chip.own")];
    const cells = chips.map((e) => e.getBoundingClientRect());
    const box = owners.closest(".krow")!.getBoundingClientRect();
    return {
      cols: new Set(cells.map((b) => Math.round(b.left))).size,
      rows: new Set(cells.map((b) => Math.round(b.top))).size,
      inside: cells.every((b) => b.left >= box.left - 1 && b.right <= box.right + 1),
      whole: chips.every((e) => e.scrollWidth <= e.clientWidth + 1), // no chip's name or value is cut
    };
  });
}

test("at 3828 the commands that hold their own value lie side by side as chips in their row, not stacked in one column", async () => {
  const page = await open(3828, 2130);
  try {
    await expect(page.locator(".kn .owners .chip.own")).toHaveCount(4, { timeout: 15_000 });
    const m = await chipShape(page);
    expect(m.cols).toBeGreaterThan(1); // side by side, never a column of four
    expect(m.rows).toBeLessThan(4);
    expect(m.inside).toBe(true);
    expect(m.whole).toBe(true);
  } finally {
    await page.close();
  }
}, 30_000);

test("at 1440 the same chips wrap inside their row instead of running past its edge, and none is cut", async () => {
  const page = await open(1440, 900);
  try {
    await expect(page.locator(".kn .owners .chip.own")).toHaveCount(4, { timeout: 15_000 });
    const m = await chipShape(page);
    expect(m.rows).toBeGreaterThan(1);
    expect(m.inside).toBe(true);
    expect(m.whole).toBe(true);
  } finally {
    await page.close();
  }
}, 30_000);

// A setting that carries per-command values takes the line it is on, as a section holding one setting does: its chips
// make it taller than the plain ones, and a plain setting beside it would stand with empty space under its control. Every
// row fills its section (Marcos: "why are we having all this empty space", Oct 11).
test("a setting carrying per-command values has its line to itself, and every row fills its section", async () => {
  const page = await open(3828, 2130);
  try {
    await expect(page.locator(".krow").first()).toBeVisible({ timeout: 15_000 });
    const m = await page.evaluate(() => [...document.querySelectorAll(".krows")].map((g) => {
      const rows = [...g.querySelectorAll(".krow")].map((r) => ({ box: r.getBoundingClientRect(), over: !!r.querySelector(".owners") }));
      const tops = rows.map((r) => Math.round(r.box.top));
      return {
        width: g.getBoundingClientRect().width,
        span: Math.max(...rows.map((r) => r.box.right)) - Math.min(...rows.map((r) => r.box.left)),
        anyOver: rows.some((r) => r.over),
        // a row carrying per-command values has no other row on its line
        sharedWithOver: rows.filter((r) => r.over).filter((r) => tops.filter((t) => t === Math.round(r.box.top)).length > 1).length,
      };
    }));
    expect(m.length).toBeGreaterThan(0);
    expect(m.some((g) => g.anyOver)).toBe(true);
    for (const g of m) {
      expect(g.span / g.width).toBeGreaterThan(0.98); // every section's rows fill it
      expect(g.sharedWithOver).toBe(0);
    }
  } finally {
    await page.close();
  }
}, 30_000);
