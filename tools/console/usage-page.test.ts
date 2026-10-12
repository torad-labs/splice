// Usage on the real page: a week's figures are the daemon's totals over every request, never a count of the rows the
// read returned; a plan's requests read "On your plan" with their API-price value and never as money, a key's are
// money; a window change reads "Reading" until the new figures land, never the old ones under the new heading; and a
// breakdown row opens its requests on Requests with its filter carried.
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
// a plan head answering 1,234,567 requests with only 3 rows returned, and a key head that spent money
const REF = "a1b2c3d4"; // the daemon's opaque id for the login; its label is the only name the page shows
const PLAN = group(REF, { label: "Primary",  requests: 1_234_567, input_tokens: 9_000_000_000, output_tokens: 100_000_000, plan_requests: 1_234_567, plan_value_usd: 4321.5 });
const KEY = group("OPENROUTER_API_KEY:abc123", { requests: 40, input_tokens: 80_000, output_tokens: 20_000, cost_usd: 1.25 });
const row = (t: string, session: string): Row => ({ turn: t, ts: now - 60_000, outcome: "ok", model: "m", session, total: 1000, account: REF, account_label: "Primary" });
const askedAccounts: (string | null)[] = []; // the account each plan read named
let slowWeek = false; // while set, the 5-hour read stays pending
const WEEK_DAY = new Date().toLocaleDateString("en-CA");

const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    if (path === "/api/heads") return Response.json({ heads: [{ key: "plan", label: "plan" }, { key: "keyed", label: "keyed" }] });
    if (path === "/api/models") return Response.json({ heads: [{ head: "plan", provider: "openai", models: [] }, { head: "keyed", provider: "openrouter", models: [] }] });
    if (path === "/api/status") return Response.json({ registry: [{ key: "plan", family: "openai", authKind: "chatgpt-oauth" }, { key: "keyed", family: "openrouter", authKind: "api-key" }] });
    if (path === "/api/accounts") return Response.json({ accounts: [{ provider: "codex", label: "Primary", label_default: false, ref: REF, kind: "chatgpt-oauth", heads: ["plan"], credential_present: true, plan: "pro", seven_day_used_percent: 39, seven_day_reset_epoch_seconds: Math.floor(now / 1000) + 86400, seven_day_window_seconds: 604800, seven_day_current: true }] });
    if (path === "/api/keys") return Response.json({ keys: [{ name: "OPENROUTER_API_KEY", fingerprint: "abc123", first_seen_epoch_seconds: Math.floor(now / 1000) - 5 * 86400, replaced: [], heads: [{ head: "keyed", source: "env" }] }] });
    if (path === "/api/sessions") return Response.json({ sessions: [] });
    if (path === "/api/topology") return Response.json({ topology: { providers: {} } });
    if (path.endsWith("/turns/live")) return Response.json({ turns: [] });
    if (path === "/api/perf/turns") {
      const head = url.searchParams.get("head"), held = Number(url.searchParams.get("n"));
      if (url.searchParams.get("since") && slowWeek && head === "plan" && held === 1 && url.searchParams.getAll("ask").length) await Bun.sleep(4000);
      if (head === "plan") askedAccounts.push(url.searchParams.get("account"));
      const g = head === "plan" ? PLAN : KEY;
      const totals = group("totals", g);
      const usage = { totals, accounts: [g], models: [], sessions: [], days: [group(WEEK_DAY, g)] };
      const rows = held > 1 && head === "plan" ? [row("a", "s1"), row("b", "s1"), row("c", "s1")] : [];
      return Response.json({ heads: [{ key: head, label: head, count: g.requests, returned: rows.length, truncated: held > 1 && rows.length < (g.requests as number), rows, ...(held > 1 ? {} : { usage }), asks: [] }] });
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
async function open(query = ""): Promise<Page> {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1200 } });
  const p = await ctx.newPage();
  await p.goto(`http://127.0.0.1:${server.port}/usage.html${query}`);
  return p;
}

test("Usage: a week's figures are the daemon's totals, not the rows returned; a plan reads On your plan with its value, a key reads money", async () => {
  const p = await open();
  try {
    const plan = p.locator(".brow", { hasText: "Primary" }).first();
    await expect(plan.locator(".num.open")).toContainText("1,234,567", { timeout: 20_000 });
    await expect(plan.locator(".cost")).toContainText("On your plan");
    await expect(plan.locator(".cost")).toContainText("≈$4,321.50 at API prices");
    await expect(plan.locator(".cost .money")).toHaveCount(0);
    const key = p.locator(".brow", { hasText: "OPENROUTER_API_KEY" }).first();
    await expect(key.locator(".cost .money")).toHaveText("≈$1.25");
    await expect(p.locator(".pcard .thru")).toContainText("Counted by splice");
    await p.getByRole("button", { name: /^plan / }).click();
    await expect(p.locator(".head-card .lat-head").first()).toContainText("95th");
  } finally {
    await p.context().close();
  }
}, 60_000);

test("Usage: a window change reads Reading until its figures land, never the old window's under the new heading", async () => {
  const p = await open();
  try {
    await expect(p.locator(".brow .num.open").first()).toContainText("1,234,567", { timeout: 20_000 });
    slowWeek = true;
    await p.locator("[data-act=win][data-v='5h']").click();
    await expect(p.locator("#usage")).toContainText("Reading");
    await expect(p.locator("#usage .brow")).toHaveCount(0);
    slowWeek = false;
    await expect(p.locator(".brow .num.open").first()).toContainText("1,234,567", { timeout: 20_000 });
  } finally {
    await p.context().close();
  }
}, 60_000);

test("Usage: a login is named by its label alone, and opening it asks the daemon for its ref", async () => {
  const p = await open();
  try {
    const rows = p.locator(".brow", { hasText: "Primary" });
    await expect(rows.first().locator(".num").nth(1)).toContainText("1,234,567", { timeout: 20_000 });
    await expect(rows).toHaveCount(1);
    await expect(p.locator(".pcard .nm")).toHaveText("Primary");
    askedAccounts.length = 0;
    await rows.first().click();
    await expect(p.locator(".fchips")).toContainText("Primary", { timeout: 20_000 });
    await expect.poll(() => [...new Set(askedAccounts)].filter(Boolean).join(",")).toBe(REF);
    await expect(p.locator("main")).not.toContainText("@");
  } finally {
    await p.context().close();
  }
}, 60_000);

test("Usage: a window change keeps the plans and the headings drawn and reads Reading only where the window's figures go", async () => {
  const p = await open();
  try {
    await expect(p.locator(".brow .num.open").first()).toContainText("1,234,567", { timeout: 20_000 });
    slowWeek = true;
    await p.locator("[data-act=win][data-v='5h']").click();
    await expect(p.locator(".pcard .cmds")).toBeVisible();
    await expect(p.locator(".pcard .windows")).toBeVisible();
    await expect(p.locator(".pcard .thru")).toContainText("Reading");
    await expect(p.locator(".keys")).toBeVisible();
    slowWeek = false;
    await expect(p.locator(".pcard .thru")).toContainText("Counted by splice", { timeout: 20_000 });
  } finally {
    await p.context().close();
  }
}, 60_000);

test("Usage: a breakdown row opens its requests on Requests with the account carried", async () => {
  const p = await open();
  try {
    const link = p.locator(".brow", { hasText: "Primary" }).first().locator("a.open");
    await expect(link).toBeVisible({ timeout: 20_000 });
    const href = new URL((await link.getAttribute("href")) ?? "", "http://x/");
    expect(href.pathname).toBe("/requests.html");
    expect(href.searchParams.get("account")).toBe(`plan|${REF}`);
    expect(href.searchParams.get("win")).toBe("7d");
    expect(href.searchParams.get("order")).toBe("price");
  } finally {
    await p.context().close();
  }
}, 60_000);
