// One unnamed login carries one name on every page (Marcos' console-wide naming, finish pass of Oct 29: a login nobody named reads
// "Account", told apart from the others by one ordinal from one order). The order is the daemon's accounts list, counted over
// every unnamed login in it, whatever page names them and however that page groups them; where only one login is unnamed it
// reads plain "Account"; a login a person named keeps its name. Through the real Accounts and Usage pages against one stub
// daemon, which holds two unnamed logins on one command and a third on another provider, so a page that numbers per provider
// or per list reads differently from one that numbers over the whole roster.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const sec = (ms: number) => Math.floor(ms / 1000);
const HOUR = 3_600_000;
const win = { five_hour_used_percent: 10, five_hour_reset_epoch_seconds: sec(now + 2 * HOUR), five_hour_window_seconds: 18_000,
  seven_day_used_percent: 20, seven_day_reset_epoch_seconds: sec(now + 50 * HOUR), seven_day_window_seconds: 604_800, observed_at_epoch_seconds: sec(now - 60_000) };
const login = <E extends { heads: string[] }>(ref: string, name: string, extra: E) => ({
  label: ref, display_name: name, ref, selector_key: ref, account_labels: {}, label_default: name === "Account", kind: "client",
  credential_present: true, identity_verified: true, can_rename: true, ...win, ...extra,
});
// the daemon's order: two unnamed on claude, a named one, then an unnamed on codex
const ROSTER = [
  login("aaaa1111", "Account", { heads: ["claude"], provider: "anthropic", account_labels: { claude: "aaaa1111" } }),
  login("bbbb2222", "Work", { heads: ["claude"], provider: "anthropic", account_labels: { claude: "bbbb2222" }, label_default: false }),
  login("cccc3333", "Account", { heads: ["claude"], provider: "anthropic", account_labels: { claude: "cccc3333" } }),
  login("dddd4444", "Account", { heads: ["codex"], provider: "openai", account_labels: { codex: "dddd4444" } }),
];
const WHERE = new Map([["aaaa1111", "claude"], ["cccc3333", "claude"], ["dddd4444", "codex"]]);
const answers: Record<string, unknown> = {
  "/api/status": { registry: [{ key: "claude", label: "claude-splice", family: "anthropic", authKind: "client" }, { key: "codex", label: "codex", family: "openai", authKind: "oauth" }] },
  "/api/accounts": { accounts: ROSTER, head_pools: {} },
  "/api/keys": { keys: [] },
  "/api/budgets": { budgets: [] },
  "/api/models": { heads: [] },
  "/api/heads": { heads: [{ key: "claude", label: "claude-splice" }, { key: "codex", label: "codex" }] },
  "/api/topology": { topology: { providers: {} } },
  "/api/sessions": { sessions: [] },
  "/api/economics": { heads: [] },
};
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path in answers) return Response.json(answers[path]);
    if (/^\/api\/auth\/[^/]+\/order$/.test(path)) return Response.json({ order: [], effective_order: ROSTER.filter((r) => r.heads[0] === path.split("/")[3]).map((r) => r.label), next_target: null, following_target: null });
    if (path === "/api/perf/turns") {
      const url = new URL(req.url), head = url.searchParams.get("head"), held = Number(url.searchParams.get("n"));
      // requests the daemon read for logins the roster no longer holds (a ref with no login), on the claude command
      const g = (key: string, requests: number) => ({ key, requests, input_tokens: requests * 1000, cached_tokens: 0, output_tokens: requests * 10, cost_usd: null, plan_requests: requests, plan_value_usd: null,
        unpriced_uncounted_requests: 0, unpriced_undeclared_requests: 0, unpriced_local_requests: 0, unanswered_requests: 0 });
      const accounts = goneRefs && head === "claude" ? goneRefs.map((r, i) => g(r, 100 + i)) : [];
      const totals = g("totals", accounts.reduce((a, x) => a + x.requests, 0));
      const usage = { totals, accounts, models: [], sessions: [], days: [] };
      return Response.json({ heads: head ? [{ key: head, label: head, count: totals.requests, returned: 0, truncated: false, rows: [], ...(held > 1 ? {} : { usage }), asks: [] }] : [] });
    }
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "accounts.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : Response.json({}, { status: 404 });
  },
});
void WHERE;
let goneRefs: string[] | null = null; // refs the daemon read requests for that no roster row holds, when a test sets them

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox", "--disable-background-networking"] });
}, 60_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

async function namesOn(open: (page: Page) => Promise<string[]>) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1200 } });
  try {
    return (await open(page)).map((t) => t.replace(/\s+/g, " ").trim()).sort();
  } finally {
    await page.close();
  }
}

const EXPECTED = ["Account 1", "Account 2", "Account 3", "Work"];

test("Accounts reads each unnamed login by one ordinal from the whole roster, a named one by its name", async () => {
  const names = await namesOn(async (page) => {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    await expect(page.locator("article.card .name").first()).toBeVisible({ timeout: 15_000 });
    await expect(page.locator("article.card .name")).toHaveCount(4, { timeout: 15_000 });
    return page.locator("article.card .name").allInnerTexts();
  });
  expect(names).toEqual(EXPECTED);
}, 40_000);

test("Usage reads the same logins by the same names as Accounts", async () => {
  const names = await namesOn(async (page) => {
    await page.goto(`http://127.0.0.1:${server.port}/usage.html`);
    await expect(page.locator(".nm").first()).toBeVisible({ timeout: 20_000 });
    return [...new Set(await page.locator(".nm").allInnerTexts())];
  });
  expect(names).toEqual(EXPECTED);
}, 40_000);

test("where only one login is unnamed it reads plain Account, never an ordinal", async () => {
  const lone = ROSTER.map((r) => (r.ref === "cccc3333" || r.ref === "dddd4444" ? { ...r, display_name: "Home", label_default: false } : r));
  const saved = answers["/api/accounts"];
  answers["/api/accounts"] = { accounts: lone, head_pools: {} };
  try {
    const names = await namesOn(async (page) => {
      await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
      await expect(page.locator("article.card .name")).toHaveCount(4, { timeout: 15_000 });
      return page.locator("article.card .name").allInnerTexts();
    });
    expect(names).toEqual(["Account", "Home", "Home", "Work"]);
  } finally {
    answers["/api/accounts"] = saved;
  }
}, 40_000);

test("signed-out logins the roster no longer holds are numbered on after its logins, and no two share a name", async () => {
  goneRefs = ["eeee5555", "ffff6666"];
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1600 } });
    try {
      await page.goto(`http://127.0.0.1:${server.port}/usage.html`);
      await expect(page.getByText("Signed out").first()).toBeVisible({ timeout: 20_000 });
      const read = () => page.evaluate(() => [...document.querySelectorAll("article.pcard.out .nm")].map((n) => (n.textContent ?? "").replace(/\s+/g, " ").trim()));
      await expect.poll(async () => (await read()).length, { timeout: 20_000 }).toBeGreaterThanOrEqual(2);
      const names = (await read()).sort();
      expect(new Set(names).size).toBe(names.length);
      expect(names).toEqual(["Account 4", "Account 5"]);
    } finally {
      await page.close();
    }
  } finally {
    goneRefs = null;
  }
}, 60_000);
