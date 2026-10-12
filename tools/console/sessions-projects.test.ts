// Sessions grouped by project. He has two jobs on this page: see who is stuck on him, anywhere, and find the one
// session in the project he is working in. The grouping serves the second and must never cost the first, so what is
// checked here is the order, the header's counts, that a folded group still shows what waits on him, that a project
// name matches every session in it, and that one project alone keeps the flat page. Through the real page.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HOUR = 3_600_000;
type Row = Record<string, unknown>;
/** One /api/sessions row: [p] is the project the daemon resolved, null for a session in none. */
const row = (id: string, name: string, p: string | null, over: Row = {}): Row => ({
  session_id: id, name, head: "alpha", route: "head", availability: "live", status: "idle",
  cwd: `/work/${p ?? "loose"}/${id}`, title: null,
  last: { role: "assistant", tool: null, text: `${name} said its last line.`, ts: 1 },
  updated_at: new Date(Date.now() - 2 * HOUR).toISOString(),
  ...(p ? { project: { id: `/work/${p}`, name: p, label: p, source: "folder", folder: "/work" } } : {}),
  ...over,
});
const needs = (id: string, name: string, p: string | null) => row(id, name, p, { waiting_for: "input", status: "idle" });
const working = (id: string, name: string, p: string | null) => row(id, name, p, { status: "working" });
const ended = (id: string, name: string, p: string | null) => row(id, name, p, { availability: "gone" });

let sessions: Row[] = [];
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/sessions") return Response.json({ sessions, reading: true });
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-alpha" }] });
    if (path === "/api/models") return Response.json({ heads: [] });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "sessions.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({
    executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined,
    args: ["--no-sandbox", "--disable-background-networking"],
  });
}, 60_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

/** The page, with the daemon answering [rows]. */
async function page(rows: Row[]): Promise<Page> {
  sessions = rows;
  const p = await browser.newPage({ viewport: { width: 1440, height: 1200 } });
  await p.goto(`http://127.0.0.1:${server.port}/sessions.html`);
  await expect(p.locator(".list")).toBeVisible({ timeout: 15_000 });
  return p;
}
const names = (p: Page) => p.locator(".pgroup .pname").allTextContents();

const THREE = [
  working("w-1", "api", "website"),
  row("i-1", "notes", "website"),
  needs("n-1", "asking", "temple"),
  working("w-2", "builder", "temple"),
  row("l-1", "stray", null),
];

// ORDER: the group with the most urgent card first, No project last, whatever the activity times say.
test("groups come in the order of their most urgent card, with No project last", async () => {
  const p = await page(THREE);
  try {
    expect(await names(p)).toEqual(["temple", "website", "No project"]);
  } finally {
    await p.close();
  }
}, 60_000);

// The header is the line he finds at a glance: the name, a lamp and count per live state present, then the total.
test("a group's header counts each live state it holds, and totals its sessions", async () => {
  const p = await page([...THREE, ended("e-1", "done", "temple")]);
  try {
    const temple = p.locator(".pgroup", { hasText: "temple" }).first();
    await expect(temple.locator(".pc.needs b")).toHaveText("1");
    await expect(temple.locator(".pc.working b")).toHaveText("1");
    await expect(temple.locator(".ptotal")).toHaveText("3"); // the ended one is in the total, not a lamp
    await expect(temple.locator(".pgroup .pc.stalled")).toHaveCount(0);
  } finally {
    await p.close();
  }
}, 60_000);

// NOTHING WAITING ON HIM IS EVER HIDDEN BY A FOLD (Marlin): the second job must not cost the first.
test("a folded group still shows the cards that wait on him, and hides the rest", async () => {
  const p = await page(THREE);
  try {
    const temple = p.locator(".pgroup", { hasText: "temple" }).first();
    await expect(temple.locator(".card.s")).toHaveCount(2);
    await temple.locator(".pfold").click();
    await expect(temple.locator(".card.s")).toHaveCount(1);
    await expect(temple.locator(".card.s")).toContainText("asking");
  } finally {
    await p.close();
  }
}, 60_000);

test("a project's name finds every session in it, and the groups stay", async () => {
  const p = await page(THREE);
  try {
    await p.locator("#q").fill("website");
    await expect(p.locator(".pgroup")).toHaveCount(1, { timeout: 10_000 });
    await expect(p.locator(".pgroup .pname")).toHaveText("website");
    await expect(p.locator(".pgroup .card.s")).toHaveCount(2); // both, though neither is named "website"
  } finally {
    await p.close();
  }
}, 60_000);

// A first-time user with one project keeps the page he had: headers are what two groups buy, not one.
test("one project and nothing outside it stays a flat list", async () => {
  const p = await page([working("w-1", "api", "website"), row("i-1", "notes", "website")]);
  try {
    await expect(p.locator(".pgroup")).toHaveCount(0);
    await expect(p.locator(".list .card.s")).toHaveCount(2);
  } finally {
    await p.close();
  }
}, 60_000);

// ONE NAME FOR WHERE A SESSION IS (Marlin, Oct 12): the open pane names the project its group wears, never the folder,
// which reads "repo" for most of his sessions.
test("the open session names its project, the same name its group wears", async () => {
  const p = await page(THREE);
  try {
    await p.locator(".pgroup", { hasText: "temple" }).first().locator(".card.s .name", { hasText: "builder" }).click();
    const meta = p.locator(".pane .meta").first();
    await expect(meta).toContainText("temple", { timeout: 10_000 });
    await expect(meta).not.toContainText("w-2"); // its folder
  } finally {
    await p.close();
  }
}, 60_000);

// In no project the folder is the only clue, so that card names it, from home, as the daemon spells it.
test("a session in no project names its folder from home on its card", async () => {
  const p = await page([...THREE.slice(0, 4), row("l-1", "stray", null, { place: "~/notes" })]);
  try {
    const loose = p.locator(".pgroup", { hasText: "No project" }).first();
    await expect(loose.locator(".card.s .where")).toHaveText("~/notes");
    await expect(p.locator(".pgroup", { hasText: "temple" }).first().locator(".card.s .where")).toHaveCount(0);
  } finally {
    await p.close();
  }
}, 60_000);

// The header says where a session is, so the card does not repeat it; unnamed, the card takes the first line of its
// first message, which the daemon reads for it, and never the folder.
test("inside a group a card is titled by what was asked of it, never by its folder", async () => {
  const p = await page([
    row("u-1", "", "temple", { name: null, title: "Fix the retry ladder in the gate" }),
    working("w-1", "api", "website"),
  ]);
  try {
    const temple = p.locator(".pgroup", { hasText: "temple" }).first();
    await expect(temple.locator(".card.s .name")).toHaveText("Fix the retry ladder in the gate");
    await expect(temple.locator(".card.s")).not.toContainText("u-1");
  } finally {
    await p.close();
  }
}, 60_000);

// ---------- the pick: one project, or every project, the same pick on every page ----------
/** The page opened at [query], e.g. "?project=/work/temple", in a fresh browser so no earlier pick carries over. */
async function pageAt(rows: Row[], query = ""): Promise<Page> {
  sessions = rows;
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1200 } });
  const p = await ctx.newPage();
  await p.goto(`http://127.0.0.1:${server.port}/sessions.html${query}`);
  await expect(p.locator(".list")).toBeVisible({ timeout: 15_000 });
  return p;
}

test("picking a project shows only its cards, with no header, and keeps the pick in the address", async () => {
  const p = await pageAt(THREE);
  try {
    await p.locator(".ppick").click();
    await p.locator(".pmenu button", { hasText: "website" }).click();
    await expect(p.locator(".pgroup")).toHaveCount(0);
    await expect(p.locator(".list .card.s")).toHaveCount(2);
    await expect(p.locator(".ppick .pname")).toHaveText("website");
    expect(new URL(p.url()).searchParams.get("project")).toBe("/work/website");
    expect(await p.evaluate(() => localStorage.getItem("splice.project"))).toBe("/work/website");
  } finally {
    await p.context().close();
  }
}, 60_000);

// THE DOORS are what keep one shared pick safe: while he looks at one project, someone waiting in another is one press away.
test("while one project is picked, another with someone waiting is a door that picks it", async () => {
  const p = await pageAt(THREE, "?project=/work/website");
  try {
    const door = p.locator(".pdoor");
    await expect(door).toHaveCount(1); // temple waits on him; No project does not
    await expect(door).toContainText("temple");
    await expect(door.locator(".pc.needs b")).toHaveText("1");
    await door.click();
    await expect(p.locator(".ppick .pname")).toHaveText("temple");
    await expect(p.locator(".list .card.s", { hasText: "asking" })).toBeVisible();
    await expect(p.locator(".pdoor")).toHaveCount(0); // nobody waits anywhere else
  } finally {
    await p.context().close();
  }
}, 60_000);

test("Every project brings the groups back", async () => {
  const p = await pageAt(THREE, "?project=/work/website");
  try {
    await p.locator(".ppick").click();
    await p.locator(".pmenu button", { hasText: "Every project" }).click();
    await expect(p.locator(".pgroup")).toHaveCount(3);
    expect(new URL(p.url()).searchParams.get("project")).toBeNull();
  } finally {
    await p.context().close();
  }
}, 60_000);

test("a picked project with nothing in it says No sessions, and its doors stay", async () => {
  const p = await pageAt(THREE, "?project=/work/gone");
  try {
    await expect(p.locator(".list .nomatch")).toContainText("No sessions");
    await expect(p.locator(".pdoor")).toContainText("temple");
  } finally {
    await p.context().close();
  }
}, 60_000);

test("with one project the head names it, and there is nothing to pick", async () => {
  const p = await pageAt([working("w-1", "api", "website"), row("i-1", "notes", "website")], "?project=/work/temple");
  try {
    await expect(p.locator(".ppick")).toHaveCount(0);
    await expect(p.locator(".lhead .pname.solo")).toHaveText("website");
    await expect(p.locator(".list .card.s")).toHaveCount(2); // a pick from another desk filters nothing here
  } finally {
    await p.context().close();
  }
}, 60_000);

// ---------- the tiles: at a 150rem page and wider, every project at once, in place of the picker and the doors ----------
test("on a wide screen every project is a tile, Every project first, and pressing one is the pick", async () => {
  sessions = [...THREE, ended("e-1", "done", "temple")];
  const ctx = await browser.newContext({ viewport: { width: 3828, height: 2130 } });
  const p = await ctx.newPage();
  try {
    await p.goto(`http://127.0.0.1:${server.port}/sessions.html`);
    const tiles = p.locator("#ptiles .ptile");
    await expect(tiles).toHaveCount(4, { timeout: 15_000 });
    expect(await p.locator("#ptiles .tname").allTextContents()).toEqual(["Every project", "temple", "website", "No project"]);
    await expect(p.locator(".ppick")).toHaveCount(0); // the tiles are the picker
    const temple = tiles.filter({ hasText: "temple" });
    await expect(temple.locator(".ptotal")).toHaveText("3");
    await expect(temple.locator(".pc.needs b")).toHaveText("1");
    await expect(tiles.first().locator(".ptotal")).toHaveText("6"); // every session, the ended one too
    await expect(tiles.first()).toHaveAttribute("aria-pressed", "true");
    await temple.click();
    await expect(temple).toHaveAttribute("aria-pressed", "true");
    await expect(p.locator(".pgroup")).toHaveCount(0);
    await expect(p.locator(".list > .card.s")).toHaveCount(2);
  } finally {
    await ctx.close();
  }
}, 60_000);

test("below the tiles' width the picker stands in their place", async () => {
  const p = await pageAt(THREE);
  try {
    await expect(p.locator("#ptiles")).toBeHidden();
    await expect(p.locator(".ppick")).toBeVisible();
  } finally {
    await p.context().close();
  }
}, 60_000);
