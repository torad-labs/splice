// Usage opened on a model, the way Accounts sends a login's week slice of one model: usage.html?win=7d&model=claude-fable-5.
// The daemon matches a model by the exact id a request carried, and the requests carry the id with the date a vendor stamps
// on it. Every list and figure on the page narrows to that model's requests, and none goes blank where the model has
// requests.
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
const DAY = new Date().toLocaleDateString("en-CA");
const FABLE_ID = "claude-fable-5-20260101"; // what the requests carry
const FABLE = { requests: 500, input_tokens: 2_000_000_000, cached_tokens: 1_920_000_000, output_tokens: 50_000_000, plan_requests: 500, plan_value_usd: 2832 };
const OPUS = { requests: 100, input_tokens: 400_000_000, cached_tokens: 100_000_000, output_tokens: 10_000_000, plan_requests: 100, plan_value_usd: 600 };
const sum = (key: string, parts: Row[]): Row => group(key, Object.fromEntries(Object.keys(FABLE).map((k) => [k, parts.reduce((a, p) => a + (p[k] as number), 0)])));
const askedModels: (string | null)[] = [];

const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    if (path === "/api/heads") return Response.json({ heads: [{ key: "plan", label: "plan" }] });
    if (path === "/api/models") return Response.json({ heads: [{ head: "plan", provider: "anthropic", models: [{ id: "claude-fable-5" }, { id: "claude-opus-5-5" }] }] });
    if (path === "/api/status") return Response.json({ registry: [{ key: "plan", family: "anthropic", authKind: "claude-oauth" }] });
    if (path === "/api/accounts") return Response.json({ accounts: [{ provider: "claude", display_name: "Primary", account_labels: { plan: null }, selector_key: "plan-selector", kind: "claude-oauth", heads: ["plan"], credential_present: true, plan: "max", seven_day_used_percent: 39, seven_day_reset_epoch_seconds: Math.floor(now / 1000) + 86400, seven_day_window_seconds: 604800, seven_day_current: true }] });
    if (path === "/api/keys") return Response.json({ keys: [] });
    if (path === "/api/sessions") return Response.json({ sessions: [] });
    if (path === "/api/topology") return Response.json({ topology: { providers: {} } });
    if (path.endsWith("/turns/live")) return Response.json({ turns: [] });
    if (path === "/api/perf/turns") {
      const head = url.searchParams.get("head"), held = Number(url.searchParams.get("n")), asked = url.searchParams.get("model");
      askedModels.push(asked);
      // the daemon keeps a request when its model is the one asked, exactly
      const kept = [[FABLE_ID, FABLE], [ "claude-opus-5-5", OPUS]].filter(([id]) => asked == null || id === asked) as [string, Row][];
      const totals = sum("totals", kept.map(([, g]) => g));
      const usage = { totals, accounts: kept.length ? [sum("primary", kept.map(([, g]) => g))] : [], models: kept.map(([id, g]) => group(id, g)), sessions: [], days: kept.length ? [group(DAY, totals)] : [] };
      return Response.json({ heads: [{ key: head, label: head, count: totals.requests, returned: 0, truncated: false, rows: [], ...(held > 1 ? {} : { usage }), asks: [] }] });
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

test("Usage: a model in the address narrows every list and figure to that model's requests, whatever date its requests carry", async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1200 } });
  const p = await context.newPage();
  try {
    await p.goto(`http://127.0.0.1:${server.port}/usage.html?win=7d&model=claude-fable-5&by=account`);
    // Where it went: the account's row carries the model's requests, not the other model's and not a dash
    const row = p.locator("#band-by .brow", { hasText: "Primary" });
    await expect(row).toContainText("500", { timeout: 20_000 });
    await expect(row).not.toContainText("600");
    // the door to it reads the model's tokens, and Tokens by command reads the model's cache share
    await expect(p.locator(".upl-door").first()).not.toContainText("—");
    await expect(p.locator("#band-tok")).toContainText("96%", { timeout: 20_000 });
    await expect(p.locator("#band-tok")).not.toContainText("No requests");
  } finally {
    await context.close();
  }
}, 60_000);
