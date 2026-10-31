// Usage, Results by command: the open row's flag names a burst of failures only when the burst falls inside the window the
// table reads. A burst of failures from an hour and a half ago is named under 24 hours and not under 1 hour, where the
// table's own rate for that window reads no failures.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
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
const BURST = now - 90 * 60_000; // three failures within a minute, an hour and a half ago
const rows = [0, 1, 2].map((i) => ({ turn: `f${i}`, ts: BURST + i * 20_000, first_byte: 100, total: 500, outcome: "error:overloaded", model: "m", account: "primary", session: "s1" }));
const ok = Array.from({ length: 6 }, (_, i) => ({ turn: `o${i}`, ts: now - 30_000 * (i + 1), first_byte: 100, total: 500, outcome: "ok", model: "m", account: "primary", session: "s1" }));

const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    if (path === "/api/heads") return Response.json({ heads: [{ key: "plan", label: "claude-plan" }] });
    if (path === "/api/models") return Response.json({ heads: [{ head: "plan", provider: "anthropic", models: [] }] });
    if (path === "/api/status") return Response.json({ registry: [{ key: "plan", family: "anthropic", authKind: "claude-oauth" }] });
    if (path === "/api/perf/summary") {
      const w = url.searchParams.get("window");
      // under 1 hour the table reads no failures; under 24 hours and 7 days it reads the burst
      const bad = w === "1h" ? 0 : 3, count = 100;
      return Response.json({ heads: [{ key: "plan", count, empty: false, coverage_known: true, clamped: false, outcomes: bad ? { ok: count - bad, "error:overloaded": bad } : { ok: count }, failure_share: bad / count, failure_shares: bad ? { "error:overloaded": bad / count } : {}, models: {}, time_before_first_byte_ms: { count, p50: 100, p95: 200, max: 300 } }] });
    }
    if (path === "/api/perf/turns") {
      const held = Number(url.searchParams.get("n"));
      const g = group("primary", { requests: 9, input_tokens: 9000, output_tokens: 900, plan_requests: 9, plan_value_usd: 1 });
      const usage = { totals: group("totals", g), accounts: [g], models: [], sessions: [], days: [group(new Date().toLocaleDateString("en-CA"), g)] };
      const out = held > 1 ? [...rows, ...ok] : [];
      return Response.json({ heads: [{ key: "plan", label: "plan", count: 9, returned: out.length, truncated: false, rows: out, ...(held > 1 ? {} : { usage }), asks: [] }] });
    }
    if (path === "/api/accounts") return Response.json({ accounts: [] });
    if (path === "/api/keys") return Response.json({ keys: [] });
    if (path === "/api/sessions") return Response.json({ sessions: [] });
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

test("Usage: Results names a burst of failures only inside the window its table reads", async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1400 } });
  const p = await context.newPage();
  try {
    await p.goto(`http://127.0.0.1:${server.port}/usage.html`);
    await p.locator("#band-perf [data-act=perfwin][data-v='24h']").click();
    await p.locator("#band-perf [data-act=perfopen][data-k=plan]").click();
    await expect(p.locator("#band-perf .flag.failing")).toContainText("Failing", { timeout: 20_000 });
    await p.locator("#band-perf [data-act=perfwin][data-v='1h']").click();
    await expect(p.locator("#band-perf .pd-hero")).toContainText("No failures", { timeout: 20_000 });
    await expect(p.locator("#band-perf .flag")).toHaveCount(0);
  } finally {
    await context.close();
  }
}, 90_000);
