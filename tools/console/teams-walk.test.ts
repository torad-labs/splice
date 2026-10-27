// Marlin's Teams-by-project walk of Oct 12 (78056fbbd), fin's cuts and hitstop's eye pass, through the real Teams page
// against a stub daemon answering in the routes' own shapes: a member last heard on another day reads that day's weekday;
// a role that already reads Lead carries no Lead tag; a member with no requests shows no tokens; an empty activity
// panel is as tall as its one line, and an empty chat only as tall as the column beside it; and a tab's needs dot takes
// the needs-you ink in either theme, never the key light.
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

// Beside the members the chat runs the whole height of the column next to it, so no hole opens under either side
// (splice-sessions, Oct 13); empty, it still never takes a screen of empty card, and an empty activity panel is as tall
// as its one line (hitstop's eye pass, Oct 12). At 3828 the column beside it is the members; at 1440, the members with
// the activity under them.
for (const width of [3828, 1440]) {
  test(`at ${width} an empty chat runs only the height of the column beside it, and an empty activity panel is its one line`, () => onTeams(async (p) => {
    await expect(p.locator(".talk .empty")).toHaveText("No messages");
    await expect(p.locator(".feed .flow > .empty")).toBeVisible();
    const m = await p.evaluate(() => {
      const box = (s: string) => {
        const b = document.querySelector(s)!.getBoundingClientRect();
        return { left: b.left, top: b.top, bottom: b.bottom, height: b.height };
      };
      return { members: box(".members"), talk: box(".talk"), feed: box(".feed") };
    });
    bunExpect(m.feed.height).toBeLessThan(200);
    const column = [m.members, m.feed].filter((b) => b.left < m.talk.left); // what reads to the chat's left
    bunExpect(Math.abs(m.talk.top - m.members.top)).toBeLessThanOrEqual(1);
    bunExpect(Math.abs(m.talk.bottom - Math.max(...column.map((b) => b.bottom)))).toBeLessThanOrEqual(1);
  }, "night", width), 60_000);
}

// NEEDS-YOU IS ITS OWN COLOUR, NEVER THE KEY LIGHT (the foundation, Oct 26: `--needs` is `#F6B352` night and `#9C5410`
// day, "its own colour now, split off the key"): the key light marks the one act a view wants pressed, so a page with
// three waiting members would otherwise carry four key lights and none of them would mean anything.
for (const [theme, ink, key] of [["day", "rgb(156, 84, 16)", "rgb(138, 90, 18)"], ["night", "rgb(246, 179, 82)", "rgb(255, 215, 154)"]] as const) {
  test(`in ${theme} theme a tab's needs dot takes the needs-you ink, never the key light`, () => onTeams(async (p) => {
    // read in one step from the live document: the page redraws its tabs when a read answers, and a tab it replaced
    // between finding it and measuring it is detached, whose computed style is the empty string
    const read = () => p.evaluate(() => {
      const el = document.querySelector(".tab.needs");
      return el ? getComputedStyle(el, "::before").backgroundColor : "";
    });
    await expect.poll(read, { timeout: 15_000 }).toBe(ink);
    bunExpect(await read()).not.toBe(key);
  }, theme, 1440), 60_000);
}
