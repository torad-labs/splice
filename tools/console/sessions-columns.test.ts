// Sessions at his width (builder3's sweep, Oct 12): one column of his 269 cards ran 17,900 px down beside the open
// session. Since Oct 15 a project is one block of one-line rows and the blocks run down as many columns as fit, so cards
// no longer pair across and 1440 is no longer one column. Since Oct 27 a session opens in place, as a plate under its own
// row spanning its block, and the page is the one scroll; the project tiles stand in equal columns that fill their row.
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

/** A session opened by pressing its name, and where its plate stands among the blocks, after the list has drawn. */
async function opened(width: number, height: number, id: string) {
  const page = await browser.newPage({ viewport: { width, height } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/sessions.html`);
    await page.locator(".list .s").nth(1).waitFor({ timeout: 15_000 });
    await page.locator(`.list .card.s[data-open="${id}"] .name`).click();
    await page.locator(".list .pane.s").waitFor({ timeout: 15_000 });
    return await page.evaluate(() => {
      const box = (e: Element) => { const r = e.getBoundingClientRect(); return { x: r.x, y: r.y + scrollY, right: r.right, bottom: r.bottom + scrollY }; };
      const scrolls = (e: Element) => /auto|scroll/.test(getComputedStyle(e).overflowY) && e.scrollHeight > e.clientHeight + 1;
      const plate = document.querySelector(".list .pane.s")!;
      const list = document.querySelector(".list")!;
      return {
        plates: document.querySelectorAll(".list .pane.s").length,
        plate: box(plate), list: box(list),
        // the project blocks before the plate's own lie above it, the sessions after it lie below it
        above: [...document.querySelectorAll(".list .card.s")].filter((c) => c.compareDocumentPosition(plate) & Node.DOCUMENT_POSITION_FOLLOWING).map((c) => box(c).y),
        below: [...document.querySelectorAll(".list .card.s")].filter((c) => c.compareDocumentPosition(plate) & Node.DOCUMENT_POSITION_PRECEDING).map((c) => box(c).y),
        innerScrollers: [plate, list, ...plate.querySelectorAll("*")].filter(scrolls).length,
        pageScrolls: document.documentElement.scrollHeight > innerHeight,
      };
    });
  } finally {
    await page.close();
  }
}

test("a session opens in place under the rows before it, across its block, and the page is the one scroll", async () => {
  // the 41st session is the first of the second block, so the first block's rows all stand before its plate
  const m = await opened(3828, 700, "s-41"); // short enough that the page runs past the fold
  expect(m.plates).toBe(1);
  expect(m.plate.right - m.plate.x).toBeGreaterThan((m.list.right - m.list.x) * 0.9); // the plate spans the board
  expect(m.above.length).toBeGreaterThan(0);
  expect(Math.max(...m.above)).toBeLessThan(m.plate.y); // everything before it is above it
  expect(Math.min(...m.below)).toBeGreaterThanOrEqual(m.plate.bottom - 1); // everything after it is below it
  expect(m.innerScrollers).toBe(0); // nothing in the plate or the list scrolls by itself
  expect(m.pageScrolls).toBe(true);
}, 30_000);

// His project tiles at 3394 (splice-lead, Oct 12; marlin, Oct 13): the tiles stand in equal columns that fill the row,
// each carrying its bar of states on the scale every tile shares, the unit said once on Every project (the whole), and a name is cut only when its own column
// cannot hold it, with the whole name in the tile's title. A tile on a wrapped line is the width of the tiles above it, and
// every line starts at the row's left edge.
test("in a crowded tile row the tiles are equal columns that fill the row, each with its bar and the unit said once on Every project, a cut name whole in its title", async () => {
  const twelve = ["temple", "splice", "grailseeker-backend", "Splice-CPO", "hostshield", "assistant", "Splice-Animator",
    "Splice-Writer", "twork", "qgre-engine-labs", "grailseeker-console"];
  const names = [...twelve, ...twelve.map((p) => `${p}-two`)];
  sessions = [...names.flatMap((p, i) => [row(100 + 2 * i, p, { status: "working" }), row(101 + 2 * i, p)]),
    { ...row(200, "loose"), project: undefined, cwd: "/home/notes" }];
  const page = await browser.newPage({ viewport: { width: 3394, height: 1889 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/sessions.html`);
    await page.locator(".ptile").nth(names.length + 1).waitFor({ timeout: 15_000 }); // Every project, each one, No project
    const { rowBox, tiles } = await page.evaluate(() => {
      const bounds = (e: Element) => { const r = e.getBoundingClientRect(); return { left: r.left, right: r.right }; };
      return {
        rowBox: bounds(document.querySelector(".ptiles")!),
        tiles: [...document.querySelectorAll<HTMLElement>(".ptile")].map((t) => {
          const n = t.querySelector(".tname")!;
          const box = t.getBoundingClientRect();
          // the width the tile's own content asks for, with nothing around it to grow or squeeze it
          t.style.width = "max-content";
          t.style.maxWidth = "none";
          const own = t.getBoundingClientRect().width;
          t.style.width = t.style.maxWidth = "";
          return { name: n.textContent!, title: t.getAttribute("title"), cut: n.scrollWidth > n.clientWidth + 1, width: box.width, own, top: box.top, left: box.left, right: box.right,
            unit: !!t.querySelector(".punit"), bar: !!t.querySelector(".pmix") };
        }),
      };
    });
    const lines = [...new Set(tiles.map((t) => t.top))];
    expect(lines.length).toBeGreaterThan(1); // the row is crowded enough to wrap
    expect(tiles.filter((t) => Math.abs(t.width - tiles[0]!.width) > 1).map((t) => t.name)).toEqual([]); // equal columns on every line
    expect(Math.max(...tiles.filter((t) => t.top === lines[0]).map((t) => t.right))).toBeGreaterThan(rowBox.right - tiles[0]!.width * 0.1); // the first line fills the row
    expect(tiles.filter((t) => t.unit).map((t) => t.name)).toEqual(["Every project"]); // what the row counts is said once, on the whole
    expect(tiles.filter((t) => !t.bar).map((t) => t.name)).toEqual([]);
    expect(tiles.filter((t) => t.title !== t.name).map((t) => t.name)).toEqual([]); // the whole name is in the title
    expect(tiles.filter((t) => t.own <= t.width + 1 && t.cut).map((t) => t.name)).toEqual([]); // cut only when its column cannot hold it
    for (const top of lines) expect(Math.min(...tiles.filter((t) => t.top === top).map((t) => t.left))).toBeCloseTo(rowBox.left, 0);
  } finally {
    sessions = columns;
    await page.close();
  }
}, 30_000);
