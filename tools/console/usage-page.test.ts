// Usage on the real page: a week's figures are the daemon's totals over every request, never a count of the rows the
// read returned; a plan's requests draw their API-price value hatched, never as money, under the band's one legend for
// "On your plan, at API prices", and a key's are money; a window change draws its striped shapes until the new figures
// land, never the old ones under the new heading; and a
// breakdown row opens its requests on Requests with its filter carried. A login the daemon names (ref + label) is read by
// them alone; a daemon that does not serve them yet is drawn as before.
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
const PLAN = group("primary", { requests: 1_234_567, input_tokens: 9_000_000_000, output_tokens: 100_000_000, plan_requests: 1_234_567, plan_value_usd: 4321.5 });
const KEY = group("OPENROUTER_API_KEY:abc123", { requests: 40, input_tokens: 80_000, output_tokens: 20_000, cost_usd: 1.25 });
const row = (t: string, session: string): Row => ({ turn: t, ts: now - 60_000, outcome: "ok", model: "m", session, total: 1000, account: "primary" });
const SECOND = group("plan-selector", { requests: 7, input_tokens: 4_000, output_tokens: 1_000, plan_requests: 7, plan_value_usd: 0.5 }); // the same plan account under its selector name
const REF = "a1b2c3d4"; // the daemon's opaque id for a login; its label is the only name the page shows
const NAMED = group(REF, { label: "Primary", requests: 1_234_567, input_tokens: 9_000_000_000, output_tokens: 100_000_000, plan_requests: 1_234_567, plan_value_usd: 4321.5 });
let named = false; // while set, the daemon serves the label contract (ref + label) for the plan login
let twoLabels = false; // while set, the plan account reaches splice under two labels
const askedAccounts: (string | null)[] = []; // the account each plan read named
let slowWeek = false; // while set, the 5-hour read stays pending
const NOPROJ = group("primary", { requests: 3, input_tokens: 9_000, output_tokens: 1_000, plan_requests: 3, plan_value_usd: 0.01 }); // its requests with no project
const askedProjects: (string | null)[] = []; // the project each read named
const WEEK_DAY = new Date().toLocaleDateString("en-CA");

const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    if (path === "/api/heads") return Response.json({ heads: [{ key: "plan", label: "plan" }, { key: "keyed", label: "keyed" }] });
    if (path === "/api/models") return Response.json({ heads: [{ head: "plan", provider: "openai", models: [] }, { head: "keyed", provider: "openrouter", models: [] }] });
    if (path === "/api/status") return Response.json({ registry: [{ key: "plan", family: "openai", authKind: "chatgpt-oauth" }, { key: "keyed", family: "openrouter", authKind: "api-key" }] });
    if (path === "/api/accounts") return Response.json({ accounts: [{ provider: "codex", ...(named ? { display_name: "Primary", label: "plan-selector", label_default: false, ref: REF } : { display_name: "Primary", account_labels: { plan: null }, selector_key: "plan-selector" }), kind: "chatgpt-oauth", heads: ["plan"], credential_present: true, plan: "pro", seven_day_used_percent: 39, seven_day_reset_epoch_seconds: Math.floor(now / 1000) + 86400, seven_day_window_seconds: 604800, seven_day_current: true }] });
    if (path === "/api/keys") return Response.json({ keys: [{ name: "OPENROUTER_API_KEY", fingerprint: "abc123", first_seen_epoch_seconds: Math.floor(now / 1000) - 5 * 86400, replaced: [], heads: [{ head: "keyed", source: "env" }] }] });
    if (path === "/api/sessions") return Response.json({ sessions: [] });
    if (path === "/api/topology") return Response.json({ topology: { providers: {} } });
    if (path.endsWith("/turns/live")) return Response.json({ turns: [] });
    if (path === "/api/perf/turns") {
      const head = url.searchParams.get("head"), held = Number(url.searchParams.get("n"));
      if (url.searchParams.get("since") && slowWeek && head === "plan" && held === 1 && url.searchParams.getAll("ask").length) await Bun.sleep(4000);
      if (head === "plan") askedAccounts.push(url.searchParams.get("account"));
      const asked = url.searchParams.get("account"), both = head === "plan" && twoLabels && !asked && !named;
      askedProjects.push(url.searchParams.get("project"));
      const g = url.searchParams.get("project") === "none" ? NOPROJ : head === "plan" ? (named ? NAMED : asked === "plan-selector" ? SECOND : PLAN) : KEY;
      const totals = group("totals", both ? { ...g, requests: (PLAN.requests as number) + (SECOND.requests as number) } : g);
      const usage = { totals, accounts: both ? [PLAN, SECOND] : [g], models: [], sessions: [], days: [group(WEEK_DAY, g)] };
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

// "On your plan" and "at API prices" are said once, in the band's legend, and "Counted by splice" once, in the board's
// head (Usage's goal, Oct 26: "Qualifiers said once: 'On your plan', 'at API prices', the window, splice's own count, in a
// header or legend"), where each plan's cost cell and each card's footer said them
test("Usage: a week's figures are the daemon's totals, not the rows returned; a plan draws its value hatched under the band's legend, a key reads money", async () => {
  const p = await open();
  try {
    const plan = p.locator(".brow", { hasText: "Primary" }).first();
    await expect(plan.locator(".num.open")).toContainText("1,234,567", { timeout: 20_000 });
    // a cost cell carries no "≈" of its own: its column's head says once that the figures are estimates
    await expect(p.locator("#band-by").getByText("Cost, estimated").first()).toBeVisible();
    await expect(plan.locator(".cost .val")).toHaveText("$4,322");
    await expect(plan.locator(".cost .vbar .plan")).toHaveCount(1);
    await expect(p.locator("#band-by .bandhead")).toContainText("On your plan, at API prices");
    await expect(plan.locator(".cost .money")).toHaveCount(0);
    const key = p.locator(".brow", { hasText: "OPENROUTER_API_KEY" }).first();
    await expect(key.locator(".cost .money")).toHaveText("$1.25");
    await expect(p.locator("#band-money .bandhead")).toContainText("Counted by splice");
    await expect(p.locator(".pcard .thru")).toContainText("1,234,567");
  } finally {
    await p.context().close();
  }
}, 60_000);

test("Usage: a window change draws its shapes until its figures land, never the old window's under the new heading", async () => {
  const p = await open();
  try {
    await expect(p.locator(".brow .num.open").first()).toContainText("1,234,567", { timeout: 20_000 });
    slowWeek = true;
    await p.locator("[data-act=win][data-v='5h']").click();
    await expect(p.locator("#usage .ghost").first()).toBeVisible(); // the reading shape (Marcos, Oct 12), never the old figures
    await expect(p.locator("#usage .brow")).toHaveCount(0);
    slowWeek = false;
    await expect(p.locator(".brow .num.open").first()).toContainText("1,234,567", { timeout: 20_000 });
  } finally {
    await p.context().close();
  }
}, 60_000);

test("Usage: one plan account under two labels is one Breakdown row, and opening it reads both labels", async () => {
  twoLabels = true;
  const p = await open();
  try {
    const rows = p.locator(".brow", { hasText: "Primary" });
    await expect(rows.first().locator(".num").nth(1)).toContainText("1,234,574", { timeout: 20_000 });
    await expect(rows).toHaveCount(1);
    await expect(rows.first().locator("a.open")).toHaveCount(0);
    askedAccounts.length = 0;
    await rows.first().click();
    await expect(p.locator(".fchips")).toContainText("Primary", { timeout: 20_000 });
    await expect.poll(() => [...new Set(askedAccounts)].filter(Boolean).sort().join(",")).toBe("plan-selector,primary");
  } finally {
    twoLabels = false;
    await p.context().close();
  }
}, 60_000);

test("Usage: a login the daemon names is read by its label alone and asked for by its ref", async () => {
  named = true;
  const p = await open();
  try {
    const rows = p.locator(".brow", { hasText: "Primary" });
    await expect(rows.first().locator(".num").nth(1)).toContainText("1,234,567", { timeout: 20_000 });
    await expect(rows).toHaveCount(1);
    await expect(p.locator(".pcard .nm")).toHaveText("Primary");
    await expect(rows.first().locator("a.open")).toHaveAttribute("href", new RegExp(`account=plan%7C${REF}`));
    askedAccounts.length = 0;
    await rows.first().click();
    await expect(p.locator(".fchips")).toContainText("Primary", { timeout: 20_000 });
    await expect.poll(() => [...new Set(askedAccounts)].filter(Boolean).join(",")).toBe(REF);
    await expect(p.locator("main")).not.toContainText("@");
  } finally {
    named = false;
    await p.context().close();
  }
}, 60_000);

test("Usage: a window change keeps the plans and the headings drawn and stripes only where the window's figures go", async () => {
  const p = await open();
  try {
    await expect(p.locator(".brow .num.open").first()).toContainText("1,234,567", { timeout: 20_000 });
    slowWeek = true;
    await p.locator("[data-act=win][data-v='5h']").click();
    await expect(p.locator(".pcard .cmds")).toBeVisible();
    await expect(p.locator(".pcard .windows")).toBeVisible();
    // the footer stripes one row per figure it will hold (Usage's report, Oct 26), and the keys are cards on the same board
    await expect(p.locator(".pcard .thru .ghost").first()).toBeVisible();
    await expect(p.locator(".kcard")).toBeVisible();
    slowWeek = false;
    // the window's own figures land in the footer, and the board's head names the window splice counted
    await expect(p.locator(".pcard .thru .ghost")).toHaveCount(0, { timeout: 20_000 });
    await expect(p.locator(".pcard .thru")).toContainText("1,234,567");
    await expect(p.locator("#band-money .bandhead")).toContainText("Counted by splice · last 5 hours");
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
    expect(href.searchParams.get("account")).toBe("plan|primary");
    expect(href.searchParams.get("win")).toBe("7d");
    expect(href.searchParams.get("order")).toBe("price");
  } finally {
    await p.context().close();
  }
}, 60_000);

test("Usage: the No project pick reads only the requests with no project, as project=none", async () => {
  askedProjects.length = 0;
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const p = await context.newPage();
  try {
    await p.goto(`http://127.0.0.1:${server.port}/usage.html?project=-`);
    await expect(p.locator(".brow .num.open").first()).toHaveText("3", { timeout: 20_000 });
    expect(askedProjects).toContain("none");
    expect(askedProjects).not.toContain("-");
  } finally {
    await context.close();
  }
}, 60_000);
