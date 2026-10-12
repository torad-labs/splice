// A command removed from Models keeps its history findable (marlin's ruling on Remove: "History stays" must mean
// findable). Both walkers removed claude-openrouter, read "History stays · 81 requests · 5 sessions", searched its name
// on Requests and Sessions, and got No match. Through the real pages, against a daemon that lists the command under
// /api/heads/retired: its requests are listed and found by its name, the Command filter names it Removed, a session on
// it reads its name and is found by it, and neither page asks a removed command for what only a live one answers.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const turn = (id: string, agoS: number) => ({
  turn: id, turn_id: id, ts: now - agoS * 1000, outcome: "ok", model: "m", session: "0c3b07d2", total: 2_000, attempts: 1, retries: 0,
});
const ROWS: Record<string, unknown[]> = { alpha: [turn("t-live", 30)], openrouter: [turn("t-gone", 60)] };
const asked: string[] = [];
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    if (path.startsWith("/api/")) asked.push(`${path}?${url.searchParams}`);
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-alpha" }] });
    if (path === "/api/heads/retired") return Response.json({ retired: [{ key: "openrouter", command: "claude-openrouter" }] });
    if (path === "/api/sessions") {
      return Response.json({ reading: true, sessions: [{ session_id: "0c3b07d2-0000", name: "router-notes", head: "openrouter", route: "head",
        availability: "live", status: "idle", cwd: "/work/notes", title: null, last: null, updated_at: new Date(now - 60_000).toISOString() }] });
    }
    if (path === "/api/perf/turns") {
      const head = url.searchParams.get("head") ?? "";
      const rows = ROWS[head] ?? [];
      return Response.json({ heads: [{ key: head, label: head, count: rows.length, returned: rows.length, truncated: false, rows,
        usage: { models: [], accounts: [], sessions: [] } }] });
    }
    if (/^\/api\/heads\/openrouter\/(turns\/live|capture|trace)/.test(path)) return Response.json({ error: "unknown head: openrouter" }, { status: 400 });
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
const askedRemovedLive = () => asked.filter((a) => /^\/api\/heads\/openrouter\/(turns\/live|capture|trace\/search)/.test(a));

test("Requests lists a removed command's requests, finds them by its name, and names it Removed", async () => {
  asked.length = 0;
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/requests.html?q=claude-openrouter`);
    await page.locator('article[data-open="openrouter:t-gone"]').waitFor({ timeout: 15_000 });
    expect(await page.locator('article[data-open="alpha:t-live"]').count()).toBe(0);
    await page.locator('[data-act="menu"][data-m="cmd"]').click();
    const removed = page.locator('.fmenu [data-v="claude-openrouter"]');
    expect(await removed.innerText()).toContain("Removed");
    expect(askedRemovedLive()).toEqual([]);
  } finally {
    await page.close();
  }
}, 30_000);

test("Sessions names a session on a removed command and finds it by that name", async () => {
  asked.length = 0;
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/sessions.html`);
    const card = page.locator('article[data-open="0c3b07d2-0000"]');
    await card.waitFor({ timeout: 15_000 });
    expect(await card.locator(".chip").innerText()).toContain("claude-openrouter");
    await page.locator("#q").fill("claude-openrouter");
    await card.waitFor({ timeout: 5_000 });
    expect(askedRemovedLive()).toEqual([]);
  } finally {
    await page.close();
  }
}, 30_000);
