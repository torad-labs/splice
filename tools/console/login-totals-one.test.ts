// Every page counts the same logins when it says how many stand at their limit (lead's look at the installed console, Oct 29:
// the top bar read "5 of 17", Usage "5 of 14 logins at their limit" and Settings "5 of 9 logins at 80% or more", and a
// first-time reader could not tell why the totals differed). The whole is the logins whose limits splice reads: a signed-out
// login, a login whose provider reports no limits and a login splice no longer holds are not among them. And a login splice
// carried requests for but no longer holds reads as no longer in splice, with what it sent kept, never as a signed-out login
// to sign in again; a login of the roster with no credential still reads Signed out beside its Sign in. Through the real
// Accounts, Usage and Settings pages in one browser, against one stub daemon.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type BrowserContext } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const sec = (ms: number) => Math.floor(ms / 1000);
const HOUR = 3_600_000;
const win = (five: number | null, week: number | null) => ({
  five_hour_used_percent: five, five_hour_reset_epoch_seconds: five == null ? null : sec(now + 2 * HOUR), five_hour_window_seconds: 18_000,
  seven_day_used_percent: week, seven_day_reset_epoch_seconds: week == null ? null : sec(now + 50 * HOUR), seven_day_window_seconds: 604_800,
  observed_at_epoch_seconds: five == null && week == null ? null : sec(now - 60_000),
});
const login = (ref: string, name: string, head: string, windows: ReturnType<typeof win>, extra: Record<string, unknown> = {}) => ({
  label: ref, display_name: name, ref, selector_key: ref, account_labels: { [head]: ref }, label_default: false, kind: "client",
  heads: [head], provider: head === "codex" ? "openai" : "anthropic", credential_present: true, identity_verified: true, can_rename: true,
  ...windows, ...extra,
});
// three logins whose limits splice reads, one of them at its limit; then a signed-out login whose last reading was high,
// and a login whose provider reports no limits
const ROSTER = [
  login("aaaa1111", "Held", "claude", win(30, 100)),
  login("bbbb2222", "Work", "claude", win(20, 40)),
  login("cccc3333", "Codex", "codex", win(50, 10)),
  login("dddd4444", "Old laptop", "claude", win(90, 95), { credential_present: false }),
  login("ffff6666", "Quiet", "claude", win(null, null)),
];
const READ = 3, AT_LIMIT = 1;
// a login splice carried requests for that the roster no longer holds
const GONE = "eeee5555";
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
  "/api/config": { layers: {}, effective: {} },
  "/api/upgrade": {},
  "/api/history": {},
};
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path in answers) return Response.json(answers[path]);
    if (/^\/api\/auth\/[^/]+\/order$/.test(path)) return Response.json({ order: [], effective_order: ROSTER.filter((r) => r.heads[0] === path.split("/")[3]).map((r) => r.label), next_target: null, following_target: null });
    if (path === "/api/perf/turns") {
      const url = new URL(req.url), head = url.searchParams.get("head"), held = Number(url.searchParams.get("n"));
      const g = (key: string, requests: number) => ({ key, requests, input_tokens: requests * 1000, cached_tokens: 0, output_tokens: requests * 10, cost_usd: null, plan_requests: requests, plan_value_usd: null,
        unpriced_uncounted_requests: 0, unpriced_undeclared_requests: 0, unpriced_local_requests: 0, unanswered_requests: 0 });
      const accounts = head === "claude" ? [g("aaaa1111", 300), g(GONE, 240)] : [];
      const totals = g("totals", accounts.reduce((a, x) => a + x.requests, 0));
      const usage = { totals, accounts, models: [], sessions: [], days: [] };
      return Response.json({ heads: head ? [{ key: head, label: head, count: totals.requests, returned: 0, truncated: false, rows: [], ...(held > 1 ? {} : { usage }), asks: [] }] : [] });
    }
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "accounts.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : Response.json({}, { status: 404 });
  },
});

let browser: Browser;
let context: BrowserContext;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox", "--disable-background-networking"] });
  context = await browser.newContext({ viewport: { width: 1440, height: 1400 } });
}, 60_000);
afterAll(async () => {
  await context.close();
  await browser.close();
  server.stop(true);
});
const flat = (s: string) => s.replace(/\s+/g, " ").trim();
const base = () => `http://127.0.0.1:${server.port}`;

test("the top bar, Usage and Settings count the same logins: the ones whose limits splice reads", async () => {
  const page = await context.newPage();
  try {
    // Accounts draws the top bar's count, and every later page in this browser draws it from there
    await page.goto(`${base()}/accounts.html`);
    const nav = page.locator('.side .nav a[href="accounts.html"] .nmark');
    await expect.poll(async () => flat(await nav.innerText().catch(() => "")), { timeout: 20_000 }).toContain(`of ${READ}`);
    await expect(nav.locator("b")).toHaveText(String(AT_LIMIT));

    await page.goto(`${base()}/usage.html`);
    await expect.poll(async () => flat(await page.locator(".upb-hero").innerText().catch(() => "")), { timeout: 20_000 })
      .toBe(`${AT_LIMIT} of ${READ} logins at their limit`);
    await expect.poll(async () => flat(await nav.innerText().catch(() => ""))).toContain(`of ${READ}`);

    await page.goto(`${base()}/settings.html#plan`);
    await expect.poll(async () => flat(await page.locator(".hn").first().innerText().catch(() => "")), { timeout: 20_000 })
      .toMatch(new RegExp(`^\\d+ of ${READ} logins at \\d+% or more$`));
  } finally {
    await page.close();
  }
}, 90_000);

test("a login splice no longer holds reads as no longer in splice, with no Sign in; a signed-out login of the roster keeps its Sign in", async () => {
  const page = await context.newPage();
  try {
    await page.goto(`${base()}/usage.html`);
    const gone = page.locator("article.pcard.gone");
    await expect(gone).toHaveCount(1, { timeout: 20_000 });
    await expect(gone).toContainText("No longer in splice");
    await expect(gone).not.toContainText("Signed out");
    await expect(gone.getByRole("button", { name: "Sign in" })).toHaveCount(0);
    // what it sent stays on the board, in its row under Where it went
    const row = page.locator(".brow.gone");
    await expect(row).toHaveCount(1, { timeout: 20_000 });
    await expect(row).toContainText("No longer in splice");
    await expect(row).not.toContainText("Signed out");
    await expect(row.getByRole("link", { name: /Sign in/ })).toHaveCount(0);
    // the roster's own login with no credential is the one to sign in again
    const out = page.locator("article.pcard", { hasText: "Old laptop" });
    await expect(out).toContainText("Signed out");
    await expect(out.getByRole("button", { name: "Sign in" })).toHaveCount(1);
  } finally {
    await page.close();
  }
}, 60_000);
