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

// His project tiles at 3394 (splice-lead, Oct 12): twelve projects crowd the row. A short name is never cut ("twork"
// read "tw…"), and the tile left alone on the wrapped line stops at its cap rather than crossing the page.
test("in a crowded tile row a short name is whole and a lone wrapped tile stops at its cap", async () => {
  const names = ["temple", "splice", "grailseeker-backend", "Splice-CPO", "hostshield", "assistant", "Splice-Animator",
    "Splice-Writer", "twork", "qgre-engine-labs", "grailseeker-console"];
  sessions = [...names.flatMap((p, i) => [row(100 + 2 * i, p, { status: "working" }), row(101 + 2 * i, p)]),
    { ...row(200, "loose"), project: undefined, cwd: "/home/notes" }];
  const page = await browser.newPage({ viewport: { width: 3394, height: 1889 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/sessions.html`);
    await page.locator(".ptile").nth(names.length).waitFor({ timeout: 15_000 });
    const tiles = await page.evaluate(() => {
      const rem = parseFloat(getComputedStyle(document.documentElement).fontSize);
      return [...document.querySelectorAll(".ptile")].map((t) => {
        const n = t.querySelector(".tname")!;
        return { name: n.textContent!, cut: n.scrollWidth > n.clientWidth + 1, rem: t.getBoundingClientRect().width / rem, top: t.getBoundingClientRect().top };
      });
    });
    expect(tiles.filter((t) => [...t.name].length <= 12 && t.cut).map((t) => t.name)).toEqual([]);
    const last = tiles.at(-1)!;
    expect(tiles.filter((t) => t.top === last.top)).toHaveLength(1); // the row wrapped, leaving one tile on the line
    expect(last.rem).toBeLessThanOrEqual(40.1);
  } finally {
    sessions = columns;
    await page.close();
  }
}, 30_000);
