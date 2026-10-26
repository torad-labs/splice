// Sessions at his width (builder3's sweep, Oct 12): one column of his 269 cards ran 17,900 px down beside the open
// session. The open session stays on screen as the list scrolls. Since Oct 15 a project is one block of one-line rows
// and the blocks run down as many columns as fit, so cards no longer pair across and 1440 is no longer one column.
// Through the real page, measured in the rendered DOM.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const row = (n: number, p: string, over: Record<string, unknown> = {}) => ({
  session_id: `s-${n}`, name: `${p}-${n}`, head: "alpha", route: "head", availability: "live", status: "idle",
  cwd: `/work/${p}/${n}`, title: null, last: { role: "assistant", tool: null, text: "Said its last line.", ts: 1 },
  updated_at: new Date(Date.now() - n * 60_000).toISOString(),
  project: { id: `/work/${p}`, name: p, label: p, source: "folder", folder: "/work" }, ...over,
});
const columns = [
  row(1, "temple", { waiting_for: "input" }), ...Array.from({ length: 39 }, (_, i) => row(i + 2, "temple")),
  ...Array.from({ length: 20 }, (_, i) => row(i + 41, "splice")),
];
let sessions: Record<string, unknown>[] = columns;
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/sessions") return Response.json({ sessions, reading: true });
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-alpha" }] });
    if (path === "/api/models") return Response.json({ heads: [] });
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

/** The first two cards' boxes, their lamps' boxes, and the open session's box, after scrolling [scrollY]. */
async function measure(width: number, height: number, scrollY = 0) {
  const page = await browser.newPage({ viewport: { width, height } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/sessions.html`);
    await page.locator(".list .s").nth(1).waitFor({ timeout: 15_000 });
    await page.evaluate((y) => window.scrollTo(0, y), scrollY);
    return await page.evaluate(() => {
      const box = (e: Element | null) => { const r = e?.getBoundingClientRect(); return r ? { x: r.x, y: r.y, right: r.right, bottom: r.bottom } : null; };
      const cards = [...document.querySelectorAll(".list .s")].slice(0, 2);
      return { cards: cards.map(box), lamps: cards.map((c) => box(c.querySelector(".lamp"))), pane: box(document.querySelector(".pane")), vh: innerHeight };
    });
  } finally {
    await page.close();
  }
}

test("the open session stays on screen while the list scrolls", async () => {
  const m = await measure(3828, 1889, 3000);
  expect(m.pane!.y).toBeGreaterThanOrEqual(0);
  expect(m.pane!.bottom).toBeLessThanOrEqual(m.vh);
}, 30_000);

// His project tiles at 3394 (splice-lead, Oct 12; marlin, Oct 13): each tile is its own content's width on every line,
// left-aligned and never grown to fill, so a short name is never cut ("twork" read "tw…") and a tile on the wrapped
// line never stretches across the page; only a name past 32rem is cut. Since the root type grows slower on wide screens
// (Oct 15), his twelve tiles fit on one line at 3394, so this row holds twice as many to wrap.
test("in a crowded tile row each tile is its own content's width, a short name whole, and the wrapped line's tiles never grow to fill it", async () => {
  const twelve = ["temple", "splice", "grailseeker-backend", "Splice-CPO", "hostshield", "assistant", "Splice-Animator",
    "Splice-Writer", "twork", "qgre-engine-labs", "grailseeker-console"];
  const names = [...twelve, ...twelve.map((p) => `${p}-two`)];
  sessions = [...names.flatMap((p, i) => [row(100 + 2 * i, p, { status: "working" }), row(101 + 2 * i, p)]),
    { ...row(200, "loose"), project: undefined, cwd: "/home/notes" }];
  const page = await browser.newPage({ viewport: { width: 3394, height: 1889 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/sessions.html`);
    await page.locator(".ptile").nth(names.length + 1).waitFor({ timeout: 15_000 }); // Every project, each one, No project
    const { cap, rowLeft, tiles } = await page.evaluate(() => {
      const rem = parseFloat(getComputedStyle(document.documentElement).fontSize);
      return {
        cap: 32 * rem,
        rowLeft: document.querySelector(".ptiles")!.getBoundingClientRect().left,
        tiles: [...document.querySelectorAll<HTMLElement>(".ptile")].map((t) => {
          const n = t.querySelector(".tname")!;
          const box = t.getBoundingClientRect();
          // the width the tile's own content asks for, with nothing around it to grow or squeeze it
          t.style.width = "max-content";
          t.style.maxWidth = "none";
          const own = t.getBoundingClientRect().width;
          t.style.width = t.style.maxWidth = "";
          return { name: n.textContent!, cut: n.scrollWidth > n.clientWidth + 1, width: box.width, own, top: box.top, left: box.left };
        }),
      };
    });
    const lines = [...new Set(tiles.map((t) => t.top))];
    expect(lines.length).toBeGreaterThan(1); // the row is crowded enough to wrap
    expect(tiles.filter((t) => Math.abs(t.width - Math.min(t.own, cap)) > 1).map((t) => t.name)).toEqual([]);
    expect(tiles.filter((t) => t.own <= cap && t.cut).map((t) => t.name)).toEqual([]);
    for (const top of lines) expect(Math.min(...tiles.filter((t) => t.top === top).map((t) => t.left))).toBeCloseTo(rowLeft, 0);
  } finally {
    sessions = columns;
    await page.close();
  }
}, 30_000);
