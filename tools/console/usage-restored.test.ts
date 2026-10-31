// Usage on the real page against a stub daemon: five things the page offered before the Oct 26 pass and lost in it, each
// asserted as the user sees it.
//  1. a key with no price says why on its own card (Uncounted, No price, This computer), never "Not reported"
//  2. Tokens by command: each account's input sent again and its turns splice could not mark, in the cold plate, and a
//     command's unmarked turns on its row even when none of its turns were marked cold
//  3. a command that could not be read says Not read, apart from the ones still being counted
//  4. Where it went can be ranked by cost
//  5. the cache write reads as not reported for a provider that never reports one, never as nothing written: its words say
//     so (Usage's goal, Oct 28: the Written column became a part of the bar)
//     Goal changed Oct 29 (the tokens band pass): every input bar draws all three parts, so a provider that reports no
//     cache write has its Written part drawn with a dash for its figure, where the bar once left the part out
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
type Row = Record<string, unknown>;
const group = (key: string, over: Row): Row => ({
  key, requests: 0, input_tokens: 0, cached_tokens: 0, cache_write_tokens: 0, output_tokens: 0, cost_usd: null, plan_requests: 0,
  plan_value_usd: null, unpriced_uncounted_requests: 0, unpriced_undeclared_requests: 0, unpriced_local_requests: 0,
  unanswered_requests: 0, missing_input_requests: 0, missing_output_requests: 0, cold_requests: 0, cold_input_tokens: 0,
  cold_unmarked_requests: 0, failed_requests: 0, rate_limited_requests: 0, ...over,
});
const REF = "a1b2c3d4", ALPHA = "a1a1a1a1", BETA = "b2b2b2b2";
const DAY = new Date().toLocaleDateString("en-CA");

// "base": a plan login whose provider sends no cache write, and a key splice could not price (uncounted)
// "costly": the same plan login, and a key that spent more than the plan's value with a sliver of the tokens
// "cold": two logins on one command with cold turns, and a key whose turns splice could not mark at all
// "split": the plan command still being counted while the key's command could not be read
let mode: "base" | "costly" | "cold" | "split" = "base";

function planUsage(): Row {
  if (mode === "cold") {
    const a = group(ALPHA, { label: "Alpha", requests: 600, input_tokens: 6e9, cached_tokens: 5e9, output_tokens: 1e7, plan_requests: 600, plan_value_usd: 900,
      cold_requests: 5, cold_input_tokens: 28_000_000, cold_unmarked_requests: 7 });
    const b = group(BETA, { label: "Beta", requests: 400, input_tokens: 3e9, cached_tokens: 2.5e9, output_tokens: 5e6, plan_requests: 400, plan_value_usd: 500,
      cold_requests: 3, cold_input_tokens: 15_000_000, cold_unmarked_requests: 9 });
    const totals = group("totals", { requests: 1000, input_tokens: 9e9, cached_tokens: 7.5e9, output_tokens: 1.5e7, plan_requests: 1000, plan_value_usd: 1400,
      cold_requests: 8, cold_input_tokens: 43_000_000, cold_unmarked_requests: 16 });
    return { totals, accounts: [a, b], models: [], sessions: [], days: [{ ...totals, key: DAY }] };
  }
  const g = group(REF, { label: "Primary", requests: 1000, input_tokens: 9e9, cached_tokens: 8e9, cache_write_tokens: 0, output_tokens: 1e8, plan_requests: 1000, plan_value_usd: 4321.5 });
  return { totals: group("totals", g), accounts: [g], models: [], sessions: [], days: [{ ...g, key: DAY }] };
}
function keyUsage(): Row {
  const over: Row = mode === "costly"
    ? { requests: 6, input_tokens: 100_000, cached_tokens: 0, cache_write_tokens: 5000, output_tokens: 20_000, cost_usd: 5000 }
    : mode === "cold"
      ? { requests: 6, input_tokens: 100_000, output_tokens: 20_000, cost_usd: 1, cold_requests: 0, cold_unmarked_requests: 4 }
      : { requests: 6, input_tokens: 100_000, cached_tokens: 0, cache_write_tokens: 5000, output_tokens: 20_000, cost_usd: null, unpriced_uncounted_requests: 6 };
  const g = group("DEEPSEEK_API_KEY:b4d84c0", over);
  return { totals: group("totals", g), accounts: [g], models: [], sessions: [], days: [{ ...g, key: DAY }] };
}
const accounts = () => (mode === "cold"
  ? [ALPHA, BETA].map((ref, i) => ({ provider: "codex", kind: "chatgpt-oauth", heads: ["plan"], display_name: i ? "Beta" : "Alpha", label: i ? "beta" : "alpha", label_default: false, ref, credential_present: true }))
  : [{ provider: "codex", kind: "chatgpt-oauth", heads: ["plan"], display_name: "Primary", label: "primary", label_default: false, ref: REF, credential_present: true,
    seven_day_used_percent: 39, seven_day_reset_epoch_seconds: Math.floor(now / 1000) + 86400, seven_day_window_seconds: 604800, seven_day_current: true }]);

const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    if (path === "/api/heads") return Response.json({ heads: [{ key: "plan", label: "plan" }, { key: "keyed", label: "keyed" }] });
    if (path === "/api/models") return Response.json({ heads: [{ head: "plan", provider: "openai", models: [] }, { head: "keyed", provider: "deepseek", models: [] }] });
    if (path === "/api/status") return Response.json({ registry: [{ key: "plan", family: "openai", authKind: "chatgpt-oauth" }, { key: "keyed", family: "deepseek", authKind: "api-key" }] });
    if (path === "/api/accounts") return Response.json({ accounts: accounts() });
    if (path === "/api/keys") return Response.json({ keys: [{ name: "DEEPSEEK_API_KEY", fingerprint: "b4d84c0", first_seen_epoch_seconds: Math.floor(now / 1000) - 5 * 86400, replaced: [], heads: [{ head: "keyed", source: "env" }] }] });
    if (path === "/api/sessions") return Response.json({ sessions: [] });
    if (path === "/api/topology") return Response.json({ topology: { providers: {} } });
    if (path.endsWith("/turns/live")) return Response.json({ turns: [] });
    if (path === "/api/perf/turns") {
      const head = url.searchParams.get("head") ?? "", held = Number(url.searchParams.get("n"));
      if (mode === "split" && head === "keyed") return Response.json({ error: "read failed" }, { status: 500 });
      if (mode === "split" && head === "plan") return Response.json({ heads: [{ key: head, label: head, read_pending: true }] });
      const usage = head === "plan" ? planUsage() : keyUsage();
      const count = (usage.totals as Row).requests;
      return Response.json({ heads: [{ key: head, label: head, count, returned: 0, truncated: false, rows: [], ...(held > 1 ? {} : { usage }), asks: [] }] });
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
  await p.goto(`http://127.0.0.1:${server.port}/usage.html`);
  return p;
}

test("Usage: a key with no price says why on its own card, readable without a hover", async () => {
  mode = "base";
  const p = await open();
  try {
    const card = p.locator(".kcard", { hasText: "DEEPSEEK_API_KEY" });
    await expect(card).toContainText("Uncounted", { timeout: 20_000 });
    await expect(card.locator('[title="Not reported"]')).toHaveCount(0);
  } finally {
    await p.context().close();
  }
}, 60_000);

test("Usage: the cold plate gives each account its input sent again and its unmarked turns, and a row says its unmarked turns", async () => {
  mode = "cold";
  const p = await open();
  try {
    const plan = p.locator("#band-tok .trow", { hasText: "plan" }).first();
    await expect(plan).toBeVisible({ timeout: 20_000 });
    await plan.locator("[data-act=cold]").click();
    const plate = p.locator("#cold-plan");
    await expect(plate.locator(".crow", { hasText: "Alpha" })).toContainText("28M");
    await expect(plate.locator(".crow", { hasText: "Alpha" })).toContainText("7");
    await expect(plate.locator(".crow", { hasText: "Beta" })).toContainText("15M");
    await expect(plate.locator(".crow", { hasText: "Beta" })).toContainText("9");
    await expect(p.locator("#band-tok .trow", { hasText: "keyed" }).first()).toContainText("4 not marked");
  } finally {
    await p.context().close();
    mode = "base";
  }
}, 60_000);

test("Usage: a command that could not be read says Not read beside the ones still being counted", async () => {
  mode = "split";
  const p = await open();
  try {
    const chart = p.locator("#band-chart");
    await expect(chart).toContainText("Not counted yet", { timeout: 20_000 });
    await expect(chart).toContainText("Not read");
  } finally {
    await p.context().close();
    mode = "base";
  }
}, 60_000);

test("Usage: Where it went ranks by cost when asked, so a costly key with few tokens leads", async () => {
  mode = "costly";
  const p = await open();
  try {
    const first = p.locator("#band-by .brow:not(.bhead)").first();
    await expect(first).toContainText("Primary", { timeout: 20_000 });
    await p.locator("#band-by .bhead").getByRole("button", { name: /Cost/ }).click();
    await expect(p.locator("#band-by .brow:not(.bhead)").first()).toContainText("DEEPSEEK_API_KEY");
  } finally {
    await p.context().close();
    mode = "base";
  }
}, 60_000);

test("Usage: the cache write reads as not reported for a provider that sends none, and as its figure for one that does", async () => {
  mode = "base";
  const p = await open();
  try {
    const plan = p.locator("#band-tok .trow", { hasText: "plan" }).first();
    const input = plan.locator(".cell.inp .ttrack");
    // the Written part's figure is a dash, and the bar says why under the pointer and to a screen reader, never "Written 0"
    const written = ".tstack > i.s2 b, .tout .tl:has(.s2) b";
    await expect(input).toHaveAttribute("aria-label", /its provider reports no cache writes/, { timeout: 20_000 });
    await expect(input).toHaveAttribute("data-tip", /its provider reports no cache writes/);
    await expect(input.locator(written)).toHaveText("—");
    expect(await input.getAttribute("aria-label")).not.toMatch(/Written/);
    // the keyed command does report one: the part is drawn, its figure is the figure and it is in the words
    const keyed = p.locator("#band-tok .trow", { hasText: "keyed" }).first().locator(".cell.inp .ttrack");
    await expect(keyed.locator(written)).toHaveText("5K");
    await expect(keyed).toHaveAttribute("aria-label", /Written 5K/);
  } finally {
    await p.context().close();
  }
}, 60_000);
