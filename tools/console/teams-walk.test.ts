// Marlin's Teams-by-project walk of Oct 12 (78056fbbd), fin's cuts and hitstop's eye pass, through the real Teams page
// against a stub daemon answering in the routes' own shapes: a member last heard on another day reads that day's weekday;
// a role that already reads Lead carries no Lead tag; a member with no requests shows no tokens; an empty chat and an
// empty activity panel are as tall as their one line; in day theme a tab's needs dot takes the key's day ink.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
type Row = Record<string, unknown>;
const proj = { id: "/work/website", name: "website", label: "website", source: "folder", folder: "/work" };
const yesterday = new Date(); yesterday.setDate(yesterday.getDate() - 1); yesterday.setHours(16, 19, 0, 0);
const sess = (id: string, over: Row = {}): Row => ({ session_id: id, name: id, head: "alpha", availability: "live", status: "idle",
  cwd: "/work/website", updated_at: yesterday.toISOString(), project: proj, ...over });
const SESSIONS = [sess("aaaaaaaa-1"), sess("bbbbbbbb-1"), sess("cccccccc-1", { waiting_for: "input" })];
const slot = (id: string, role: string, session: string, lead = false): Row => ({ id, role, head: "alpha", lead, session, project: proj });
const TEAMS = [
  { id: "t1", name: "Site crew", repo: "/work/website", project: proj,
    slots: [slot("lead", "Lead", "aaaaaaaa-1", true), slot("builder", "Builder", "bbbbbbbb-1")] },
  { id: "t2", name: "Asking crew", repo: "/work/website", project: proj, slots: [slot("planner", "Planner", "cccccccc-1", true)] },
];
const tokens = { input: 1000, cache_read: 0, cache_write: 0, output: 200 };
const ECONOMICS = { team_id: "t1", slots: [
  { slot: "lead", turns: 0, tokens: { input: 0, cache_read: 0, cache_write: 0, output: 0 }, moved_turns: 0, priced_moved_turns: 0 },
  { slot: "builder", turns: 3, tokens, moved_turns: 3, priced_moved_turns: 0 },
] };
const EMPTY_DAY = { team_id: "t1", state: "on", messages: [], rows: [] };

const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/sessions") return Response.json({ sessions: SESSIONS, reading: true });
    if (path === "/api/teams") return Response.json({ teams: TEAMS });
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "alpha" }] });
    if (/^\/api\/teams\/t\d\/economics$/.test(path)) return Response.json(ECONOMICS);
    if (/^\/api\/teams\/t\d\/(chat|activity)$/.test(path)) return Response.json(EMPTY_DAY);
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
async function onTeams(check: (p: Page) => Promise<void>, theme = "night", width = 3828) {
  // the page and this file read the same clock, so a time is the same time on both sides
  const ctx = await browser.newContext({ viewport: { width, height: 2000 }, timezoneId: Intl.DateTimeFormat().resolvedOptions().timeZone });
  await ctx.addInitScript((t) => localStorage.setItem("splice-console-theme", t), theme);
  const p = await ctx.newPage();
  try {
    await p.goto(`http://127.0.0.1:${server.port}/teams.html`);
    await expect(p.locator('article[data-key="m:lead"]')).toBeVisible({ timeout: 20_000 });
    await check(p);
  } finally {
    await ctx.close();
  }
}
const member = (p: Page, id: string) => p.locator(`article[data-key="m:${id}"]`);

test("a member last heard on another day reads its weekday with the time", () => onTeams(async (p) => {
  const day = yesterday.toLocaleDateString("en-US", { weekday: "short" });
  await expect(member(p, "builder").locator(".when")).toHaveText(`${day} 4:19 PM`);
}), 60_000);

test("a role that already reads Lead carries no Lead tag", () => onTeams(async (p) => {
  await expect(member(p, "lead").locator(".lead")).toHaveCount(0);
}), 60_000);

test("a member with no requests shows no tokens, and one with requests does", () => onTeams(async (p) => {
  await expect(member(p, "lead").locator(".use")).toContainText("0 requests");
  await expect(member(p, "lead").locator(".use")).not.toContainText("tokens");
  await expect(member(p, "builder").locator(".use")).toContainText("tokens");
}), 60_000);

test("an empty chat and an empty activity panel are as tall as their one line, not the screen", () => onTeams(async (p) => {
  await expect(p.locator(".talk .empty")).toHaveText("No messages");
  for (const panel of [".talk", ".feed"]) {
    const box = await p.locator(panel).boundingBox();
    bunExpect(box!.height).toBeLessThan(200);
  }
}), 60_000);

test("in day theme a tab's needs dot takes the key's day ink", () => onTeams(async (p) => {
  const dot = await p.locator(".tab.needs").first().evaluate((el) => getComputedStyle(el, "::before").backgroundColor);
  bunExpect(dot).toBe("rgb(138, 90, 18)");
}, "day", 1440), 60_000);
