// Usage, the header's door to Results by command: it carries the Results table's own answer beside two doors that read
// the page's window and the page's project pick. The table reads every request over its own window (1 hour, 24 hours or
// 7 days; the page's 5 hours as 24 hours and its 30 days as 7 days), and its head says "All requests" when a project or
// filter is picked. Goal: the door says what its figure covers wherever that differs from the doors beside it, in the
// band's own words: the table's window when it is not the page's, and all requests when a project is picked. Where the
// two agree it reads as before, and its qualifier stays on one line.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
type Row = Record<string, unknown>;
const group = (key: string, over: Row): Row => ({
  key, requests: 0, input_tokens: 0, cached_tokens: 0, output_tokens: 0, cost_usd: null, plan_requests: 0, plan_value_usd: null,
  unpriced_uncounted_requests: 0, unpriced_undeclared_requests: 0, unpriced_local_requests: 0, unanswered_requests: 0, ...over,
});
const proj = (p: string) => ({ id: `/work/${p}`, name: p, label: p, source: "folder", folder: "/work" });
const rows = Array.from({ length: 6 }, (_, i) => ({ turn: `o${i}`, ts: now - 30_000 * (i + 1), first_byte: 100, total: 500, outcome: "ok", model: "m", account: "primary", session: "s1", project: proj("website") }));

const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    if (path === "/api/heads") return Response.json({ heads: [{ key: "plan", label: "claude-plan" }] });
    if (path === "/api/models") return Response.json({ heads: [{ head: "plan", provider: "anthropic", models: [] }] });
    if (path === "/api/status") return Response.json({ registry: [{ key: "plan", family: "anthropic", authKind: "claude-oauth" }] });
    if (path === "/api/perf/summary") {
      const bad = 3, count = 100;
      return Response.json({ heads: [{ key: "plan", count, empty: false, coverage_known: true, clamped: false, outcomes: { ok: count - bad, "error:overloaded": bad }, failure_share: bad / count, failure_shares: { "error:overloaded": bad / count }, models: {}, time_before_first_byte_ms: { count, p50: 100, p95: 200, max: 300 } }] });
    }
    if (path === "/api/perf/turns") {
      const held = Number(url.searchParams.get("n"));
      const g = group("primary", { requests: 6, input_tokens: 6000, output_tokens: 600, plan_requests: 6, plan_value_usd: 1 });
      const usage = { totals: group("totals", g), accounts: [g], models: [], sessions: [], days: [group(new Date().toLocaleDateString("en-CA"), g)] };
      const out = held > 1 ? rows : [];
      return Response.json({ heads: [{ key: "plan", label: "plan", count: 6, returned: out.length, truncated: false, rows: out, ...(held > 1 ? {} : { usage }), asks: [] }] });
    }
    if (path === "/api/sessions") return Response.json({ sessions: [{ session_id: "s1-full", name: "api", head: "plan", availability: "live", status: "idle", cwd: "/work/website/repo", updated_at: new Date(now - 60_000).toISOString(), project: proj("website") }] });
    if (path === "/api/accounts") return Response.json({ accounts: [] });
    if (path === "/api/keys") return Response.json({ keys: [] });
    if (path === "/api/topology") return Response.json({ topology: { providers: {} } });
    if (path.endsWith("/turns/live")) return Response.json({ turns: [] });
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

const door = (p: Page) => p.locator("#doors [data-act=door][data-v=perf]");
const said = (p: Page) => door(p).locator(".upl-dq");
async function open(query: string, run: (p: Page) => Promise<void>) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1400 } });
  const p = await context.newPage();
  try {
    await p.goto(`http://127.0.0.1:${server.port}/usage.html${query}`);
    // 3 of 100 requests failed, however the page rounds it
    await expect(door(p).locator("[data-tn=door-failed]")).toHaveText(/^3(\.0)?%$/, { timeout: 20_000 });
    await run(p);
  } finally {
    await context.close();
  }
}

test("Usage: where the Results table and the page agree, the door reads its figure as failed and nothing more", async () => {
  await open("", async (p) => {
    await expect(said(p)).toHaveText("failed");
  });
}, 90_000);

test("Usage: the Results door names the table's window when the page's window is one the table does not keep", async () => {
  await open("", async (p) => {
    await p.locator("#win [data-act=win][data-v='5h']").click();
    await expect(p.locator("#band-perf [data-act=perfwin][aria-pressed=true], #band-perf [data-act=perfwin][aria-checked=true]")).toContainText("24 hours", { timeout: 20_000 });
    await expect(said(p)).toContainText("24 hours", { timeout: 20_000 });
    await p.locator("#win [data-act=win][data-v='30d']").click();
    await expect(said(p)).toContainText("7 days", { timeout: 20_000 });
    // the table's own switch moves the door with it
    await p.locator("#band-perf [data-act=perfwin][data-v='1h']").click();
    await expect(said(p)).toContainText("1 hour", { timeout: 20_000 });
    expect(await said(p).evaluate((el) => el.getClientRects().length)).toBe(1);
  });
}, 90_000);

test("Usage: with a project picked, the Results door says its figure is over all requests, as the band's head does", async () => {
  await open("?project=/work/website", async (p) => {
    await expect(p.locator("#band-perf .bandhead")).toContainText("All requests", { timeout: 20_000 });
    await expect(said(p)).toContainText(/all requests/i, { timeout: 20_000 });
    expect(await said(p).evaluate((el) => el.getClientRects().length)).toBe(1);
  });
}, 90_000);
