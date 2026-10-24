// Compare models sends are no project's work and are not "No project" either: Requests lists them as a group of their own,
// just before No project, only while the window holds some, titles each by that name, gives it no Open session, and asks
// the daemon for them with ?project=compare. Through the real page against a stub daemon.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
type Row = Record<string, unknown>;
const proj = (p: string) => ({ id: `/work/${p}`, name: p, label: p, source: "folder", folder: "/work" });
const sess = (id: string, name: string, p: string | null): Row => ({
  session_id: id, name, head: "alpha", availability: "live", status: "idle", cwd: `/work/${p ?? "loose"}/repo`,
  updated_at: new Date(now - 3_600_000).toISOString(), ...(p ? { project: proj(p) } : {}),
});
const turn = (t: string, agoS: number, session: string | null, p: string | null, compare = false): Row => ({
  turn: t, turn_id: t, ts: now - agoS * 1000, outcome: "ok", model: "m", session, total: 1000, attempts: 1, retries: 0,
  project: p ? proj(p) : null, title: null, compare,
});
const TURNS = [turn("t-web", 20, "aaaaaaaa", "website"), turn("t-loose", 30, "eeeeeeee", null), turn("t-cmp", 40, null, null, true)];
const SESSIONS = [sess("aaaaaaaa-1", "api", "website"), sess("eeeeeeee-1", "stray", null)];
const asked: string[] = [];
let withCompare = true;
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    if (path === "/api/sessions") return Response.json({ sessions: SESSIONS, reading: true });
    if (path === "/api/heads/alpha/turns/live") return Response.json({ turns: [] });
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "alpha" }] });
    if (path === "/api/models") return Response.json({ heads: [] });
    if (path === "/api/keys") return Response.json({ keys: [] });
    if (path === "/api/topology") return Response.json({ topology: { providers: {} } });
    if (path === "/api/perf/turns") {
      const p = url.searchParams.get("project");
      if (url.searchParams.get("n") !== "1") asked.push(p ?? "");
      const all = withCompare ? TURNS : TURNS.filter((r) => !r.compare);
      const rows = all.filter((r) => !p || (p === "compare" ? r.compare : p === "none" ? !r.project && !r.compare : (r.project as Row | null)?.id === p));
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
async function open(): Promise<Page> {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1200 } });
  const p = await ctx.newPage();
  await p.goto(`http://127.0.0.1:${server.port}/requests.html`);
  return p;
}
const card = (p: Page, t: string) => p.locator(`article[data-open="alpha:${t}"]`);

test("Compare models is a group before No project, titles its rows by that name, has no Open session, and is asked for as project=compare", async () => {
  withCompare = true;
  const p = await open();
  try {
    await expect(card(p, "t-cmp")).toBeVisible({ timeout: 20_000 });
    await expect(card(p, "t-cmp").locator(".name")).toHaveText("Compare models");
    await p.locator("#pickline .ppick").click();
    const items = await p.locator(".pmenu button").allTextContents();
    const names = items.map((t) => t.replace(/\s*\d+$/, "").trim());
    expect(names.indexOf("Compare models")).toBeGreaterThan(-1);
    expect(names.indexOf("Compare models")).toBeLessThan(names.indexOf("No project"));
    await p.locator(".pmenu button", { hasText: "Compare models" }).click();
    await expect(card(p, "t-web")).toHaveCount(0, { timeout: 10_000 });
    expect(asked.at(-1)).toBe("compare");
    expect(await p.locator("article.card.rq").evaluateAll((els) => els.map((e) => e.getAttribute("data-open")))).toEqual(["alpha:t-cmp"]);
    await card(p, "t-cmp").locator(".name").click();
    await expect(p.locator("a", { hasText: "Open session" })).toHaveCount(0);
    expect(new URL(p.url()).searchParams.get("project")).toBe("compare");
  } finally {
    await p.context().close();
  }
}, 60_000);

test("No project lists only the project-less requests, never a Compare send", async () => {
  withCompare = true;
  const p = await open();
  try {
    await expect(card(p, "t-cmp")).toBeVisible({ timeout: 20_000 });
    await p.locator("#pickline .ppick").click();
    await p.locator(".pmenu button", { hasText: "No project" }).click();
    await expect(card(p, "t-web")).toHaveCount(0, { timeout: 10_000 });
    expect(await p.locator("article.card.rq").evaluateAll((els) => els.map((e) => e.getAttribute("data-open")))).toEqual(["alpha:t-loose"]);
  } finally {
    await p.context().close();
  }
}, 60_000);

test("with no Compare send in the window the group is not drawn", async () => {
  withCompare = false;
  const p = await open();
  try {
    await expect(card(p, "t-web")).toBeVisible({ timeout: 20_000 });
    await p.locator("#pickline .ppick").click();
    await expect(p.locator(".pmenu button", { hasText: "Compare models" })).toHaveCount(0);
  } finally {
    await p.context().close();
  }
}, 60_000);
