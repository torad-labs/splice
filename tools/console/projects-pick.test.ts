// The pick is one pick (PROJECTS.md): Teams and Requests take the project Sessions groups by. Requests draws the
// picker on its own line above the filters, asks the daemon for the project's requests, keeps time order with no
// group headers, and names each row's session as its Sessions card does. Teams clusters its tabs under each project's
// name in Sessions' order, and a pick shows every team with a member in the project. Through the real pages.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const proj = (p: string) => ({ id: `/work/${p}`, name: p, label: p, source: "folder", folder: "/work" });
type Row = Record<string, unknown>;
const sess = (id: string, name: string | null, p: string | null, over: Row = {}): Row => ({
  session_id: id, name, head: "alpha", availability: "live", status: "idle", cwd: `/work/${p ?? "loose"}/repo`,
  updated_at: new Date(now - 3_600_000).toISOString(), ...(p ? { project: proj(p) } : {}), ...over,
});
const SESSIONS = [
  sess("aaaaaaaa-1", "api", "website", { status: "working" }),
  sess("cccccccc-1", "asking", "temple", { waiting_for: "input" }),
  sess("dddddddd-1", "builder", "temple", { status: "working" }),
  sess("eeeeeeee-1", "stray", null),
];
const turn = (t: string, agoS: number, session: string, p: string | null, over: Row = {}): Row => ({
  turn: t, turn_id: t, ts: now - agoS * 1000, outcome: "ok", model: "m", session, total: 1000, attempts: 1, retries: 0,
  project: p ? proj(p) : null, title: null, ...over,
});
const TURNS = [
  turn("t-web", 20, "aaaaaaaa", "website"),
  turn("t-old", 40, "bbbbbbbb", "temple", { title: "Fix the retry ladder in the gate" }), // a session no longer live
  turn("t-ask", 60, "cccccccc", "temple"),
];
const slot = (id: string, session: string | null, p: string | null, lead = false): Row =>
  ({ id, role: id, head: "alpha", lead, session, project: p ? proj(p) : null });
const TEAMS = [
  { id: "t1", name: "Site crew", repo: "/work/website", project: proj("website"), slots: [slot("lead", "aaaaaaaa-1", "website", true)] },
  // no repo: under its lead's project, temple, and with a member in website it shows on website's pick too
  { id: "t2", name: "Gate crew", repo: "", project: null, slots: [slot("lead", "dddddddd-1", "temple", true), slot("helper", "aaaaaaaa-1", "website")] },
  { id: "t3", name: "Loose crew", repo: "", project: null, slots: [slot("lead", "eeeeeeee-1", null, true)] },
];

const asked: string[] = [];
let sessions: Row[] = SESSIONS, teams: Row[] = TEAMS, liveTurns: Row[] = [];
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    if (path === "/api/sessions") return Response.json({ sessions, reading: true });
    if (path === "/api/heads/alpha/turns/live") return Response.json({ turns: liveTurns });
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "alpha" }] });
    if (path === "/api/teams") return Response.json({ teams });
    if (path === "/api/models") return Response.json({ heads: [] });
    if (path === "/api/keys") return Response.json({ keys: [] });
    if (path === "/api/topology") return Response.json({ topology: { providers: {} } });
    if (path === "/api/perf/turns") {
      const p = url.searchParams.get("project");
      if (url.searchParams.get("n") !== "1") asked.push(p ?? "");
      const rows = TURNS.filter((r) => !p || (r.project as Row | null)?.id === p);
      return Response.json({ heads: [{ key: "alpha", label: "alpha", count: rows.length, returned: rows.length, truncated: false, rows, usage: { models: [], accounts: [], sessions: [] } }] });
    }
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox", "--disable-background-networking"] });
}, 60_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});
async function open(page: string, query = "", width = 1440): Promise<Page> {
  const ctx = await browser.newContext({ viewport: { width, height: 1200 } });
  const p = await ctx.newPage();
  await p.goto(`http://127.0.0.1:${server.port}/${page}${query}`);
  return p;
}
const card = (p: Page, t: string) => p.locator(`article[data-open="alpha:${t}"]`);

test("Requests: picking a project asks the daemon for its requests, in time order with no headers, and the pick is kept", async () => {
  const p = await open("requests.html");
  try {
    await expect(card(p, "t-web")).toBeVisible({ timeout: 20_000 });
    await expect(p.locator("#pickline .ppick .pname")).toHaveText("Every project");
    await p.locator("#pickline .ppick").click();
    await p.locator(".pmenu button", { hasText: "temple" }).click();
    // the page draws its loading rows until the daemon answers, so the rows are read once the project's own are drawn, not
    // when the old row leaves
    await expect(card(p, "t-old")).toBeVisible({ timeout: 10_000 });
    await expect(card(p, "t-ask")).toBeVisible({ timeout: 10_000 });
    await expect(card(p, "t-web")).toHaveCount(0);
    expect(asked.at(-1)).toBe("/work/temple");
    expect(await p.locator("article.card.rq").evaluateAll((els) => els.map((e) => e.getAttribute("data-open")))).toEqual(["alpha:t-old", "alpha:t-ask"]);
    await expect(p.locator(".pgroup")).toHaveCount(0);
    expect(new URL(p.url()).searchParams.get("project")).toBe("/work/temple");
    expect(await p.evaluate(() => localStorage.getItem("splice.project"))).toBe("/work/temple");
  } finally {
    await p.context().close();
  }
}, 60_000);

test("Requests: a row names its session as its Sessions card does, by the first thing asked when it has no name", async () => {
  const p = await open("requests.html");
  try {
    await expect(card(p, "t-old").locator(".name")).toHaveText("Fix the retry ladder in the gate", { timeout: 20_000 });
    await expect(card(p, "t-ask").locator(".name")).toHaveText("asking");
  } finally {
    await p.context().close();
  }
}, 60_000);

test("Requests: while one project is picked, one where someone waits is a door to it", async () => {
  const p = await open("requests.html", "?project=/work/website");
  try {
    const door = p.locator("#pickline .pdoor");
    await expect(door).toContainText("temple", { timeout: 20_000 });
    await expect(door.locator(".pc.needs b")).toHaveText("1");
    await door.click();
    await expect(p.locator("#pickline .ppick .pname")).toHaveText("temple");
    await expect(card(p, "t-ask")).toBeVisible();
  } finally {
    await p.context().close();
  }
}, 60_000);

test("Requests: an older door that names the project by its name opens on that project", async () => {
  const p = await open("requests.html", "?project=temple");
  try {
    await expect(p.locator("#pickline .ppick .pname")).toHaveText("temple", { timeout: 20_000 });
    await expect(card(p, "t-web")).toHaveCount(0);
    await expect(card(p, "t-ask")).toBeVisible();
  } finally {
    await p.context().close();
  }
}, 60_000);

test("Teams: the tabs sit under their project's name in Sessions' order, a team with no repo under its lead's", async () => {
  const p = await open("teams.html");
  try {
    await expect(p.locator(".tabs .tproj").first()).toBeVisible({ timeout: 20_000 });
    expect(await p.locator(".tabs .tproj").allTextContents()).toEqual(["temple", "website", "No project"]);
    await expect(p.locator(".tcluster", { hasText: "temple" }).locator(".tab")).toHaveText(["Gate crew"]);
  } finally {
    await p.context().close();
  }
}, 60_000);

test("Teams: a pick shows every team with a member in the project, wherever its repo is", async () => {
  const p = await open("teams.html", "?project=/work/website");
  try {
    await expect(p.locator(".ppick .pname")).toHaveText("website", { timeout: 20_000 });
    await expect(p.locator(".tabs .tab .tname")).toHaveText(["Site crew", "Gate crew"]);
    await expect(p.locator(".tabs .tproj")).toHaveCount(0);
    await p.locator(".pdoor", { hasText: "temple" }).click();
    await expect(p.locator(".tabs .tab .tname")).toHaveText(["Gate crew"]);
    await expect(p.locator('.tab[aria-current="true"] .tname')).toHaveText("Gate crew");
  } finally {
    await p.context().close();
  }
}, 60_000);

// ONE ORDER (Marlin): a stalled project outranks a working one on every page, read from the same live turns. Here
// website's session is the newer, so an order that missed the stall would put website above temple.
test("Sessions, Teams and Requests order projects alike, a stalled project above a working one", async () => {
  sessions = [
    sess("gggggggg-1", "asking", "garden", { waiting_for: "input" }),
    sess("tttttttt-1", "quiet", "temple", { status: "working", updated_at: new Date(now - 5 * 3_600_000).toISOString() }),
    sess("wwwwwwww-1", "busy", "website", { status: "working" }),
    sess("eeeeeeee-1", "stray", null),
  ];
  liveTurns = [{ session: "tttttttt-1", idle_ms: 20 * 60_000, seen_output: true, retries: 0 }];
  teams = ["garden", "temple", "website"].map((p, i) => ({ id: `o${i}`, name: `${p} crew`, repo: `/work/${p}`, project: proj(p), slots: [slot("lead", null, p, true)] }));
  const order = ["garden", "temple", "website", "No project"];
  try {
    const s = await open("sessions.html");
    await expect(s.locator(".pgroup .pname").first()).toBeVisible({ timeout: 20_000 });
    expect(await s.locator(".pgroup .pname").allTextContents()).toEqual(order);
    await s.context().close();
    const t = await open("teams.html");
    await expect(t.locator(".tabs .tproj").first()).toBeVisible({ timeout: 20_000 });
    expect(await t.locator(".tabs .tproj").allTextContents()).toEqual(order.slice(0, 3));
    await t.context().close();
    const r = await open("requests.html");
    await r.locator("#pickline .ppick").click({ timeout: 20_000 });
    expect(await r.locator(".pmenu button .pname").allTextContents()).toEqual(["Every project", ...order]);
    await r.context().close();
  } finally {
    sessions = SESSIONS; teams = TEAMS; liveTurns = [];
  }
}, 90_000);

// EACH PAGE COUNTS ITS OWN THING (Marlin): a tile's figure is teams on Teams and requests on Requests; the lamps stay
// the sessions', so someone waiting shows from every page.
const tileTotals = (p: Page) => p.locator("#ptiles .ptile, .ptiles .ptile").evaluateAll((els) =>
  els.map((e) => [e.querySelector(".tname")?.textContent, e.querySelector(".ptotal")?.textContent]));
test("on a wide Teams the tiles count teams, a team under every project it has a member in", async () => {
  const p = await open("teams.html", "", 3828);
  try {
    await expect(p.locator(".ptiles .ptile")).toHaveCount(4, { timeout: 20_000 });
    expect(await tileTotals(p)).toEqual([["Every project", "3"], ["temple", "1"], ["website", "2"], ["No project", "1"]]);
    await expect(p.locator(".ptiles .ptile", { hasText: "temple" }).locator(".pc.needs b")).toHaveText("1");
  } finally {
    await p.context().close();
  }
}, 60_000);
test("on a wide Requests the tiles count the requests in the window, and the filters start where the tiles do", async () => {
  const p = await open("requests.html", "", 3828);
  try {
    await expect(p.locator(".ptiles .ptotal").first()).toHaveText("3", { timeout: 20_000 });
    expect(await tileTotals(p)).toEqual([["Every project", "3"], ["temple", "2"], ["website", "1"], ["No project", "0"]]);
    const left = (sel: string) => p.locator(sel).first().evaluate((e) => Math.round(e.getBoundingClientRect().left));
    expect(await left(".filters")).toBe(await left(".ptiles"));
  } finally {
    await p.context().close();
  }
}, 60_000);

// A PROJECT'S FIGURE AT EVERY WIDTH (Marlin's walk, Oct 12): both walkers paged through thousands of rows at 1440 to
// find how many requests temple sent, a figure only the tiles carried. Below the tiles' width the picker and its menu
// carry the same figure each page's tiles do.
const menuFigures = (p: Page) => p.locator(".pmenu button").evaluateAll((els) =>
  els.map((e) => [e.querySelector(".pname")?.textContent, e.querySelector(".pfig")?.textContent ?? null]));
for (const [page, figures] of [
  ["sessions.html", [["Every project", "4"], ["temple", "2"], ["website", "1"], ["No project", "1"]]],
  ["teams.html", [["Every project", "3"], ["temple", "1"], ["website", "2"], ["No project", "1"]]],
  ["requests.html", [["Every project", "3"], ["temple", "2"], ["website", "1"], ["No project", "0"]]],
] as const) {
  test(`at 1440 ${page}'s picker and its menu carry each project's figure, as its tiles do`, async () => {
    const p = await open(page);
    try {
      await expect(p.locator(".ppick .pfig")).toHaveText(figures[0][1], { timeout: 20_000 });
      await p.locator(".ppick").click();
      await expect(p.locator(".pmenu button")).toHaveCount(figures.length);
      await expect.poll(() => menuFigures(p), { timeout: 20_000 }).toEqual(figures.map(([n, f]) => [n, f]));
      await p.locator(".pmenu button", { hasText: "temple" }).click();
      await expect(p.locator(".ppick .pfig")).toHaveText(figures[1][1]);
    } finally {
      await p.context().close();
    }
  }, 60_000);
}
