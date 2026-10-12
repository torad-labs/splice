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
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    if (path === "/api/sessions") return Response.json({ sessions: SESSIONS, reading: true });
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "alpha" }] });
    if (path === "/api/teams") return Response.json({ teams: TEAMS });
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
    await expect(p.locator("#pickline .ppick")).toHaveText("Every project");
    await p.locator("#pickline .ppick").click();
    await p.locator(".pmenu button", { hasText: "temple" }).click();
    await expect(card(p, "t-web")).toHaveCount(0, { timeout: 10_000 });
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
    await expect(p.locator("#pickline .ppick")).toHaveText("temple");
    await expect(card(p, "t-ask")).toBeVisible();
  } finally {
    await p.context().close();
  }
}, 60_000);

test("Requests: an older door that names the project by its name opens on that project", async () => {
  const p = await open("requests.html", "?project=temple");
  try {
    await expect(p.locator("#pickline .ppick")).toHaveText("temple", { timeout: 20_000 });
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
    await expect(p.locator(".ppick")).toHaveText("website", { timeout: 20_000 });
    await expect(p.locator(".tabs .tab .tname")).toHaveText(["Site crew", "Gate crew"]);
    await expect(p.locator(".tabs .tproj")).toHaveCount(0);
    await p.locator(".pdoor", { hasText: "temple" }).click();
    await expect(p.locator(".tabs .tab .tname")).toHaveText(["Gate crew"]);
    await expect(p.locator('.tab[aria-current="true"] .tname')).toHaveText("Gate crew");
  } finally {
    await p.context().close();
  }
}, 60_000);
