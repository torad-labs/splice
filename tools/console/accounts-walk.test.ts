// Marlin's Accounts walk of Oct 12, ruled via splice-lead, through the real page against a stub daemon that answers in
// the routes' own shapes: failed requests that reported no usage are failed, never "with no price"; the key in use shows
// its masked tail from /api/auth, and its date only when its variable has held two keys, the key it replaced folded into
// one line on its card; a window whose reset went by
// reads "Ended"; a plan card with no reading reads "Not read", and "No limits reported" only when the provider answered
// with none; the order rail's numbers keep night's ink in day theme, where their socket is still dark.
// Changed Oct 29 by the console-wide naming fix (anchors of accounts-login-cards): a login nobody named reads "Account", not its
// provider's word, so the unnamed card is found by that word.
// Changed Oct 29 by the console-wide naming fix (anchors of accounts-login-cards): a login nobody named reads "Account", not its
// provider's word, so the unnamed card is found by that word.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HOUR = 3_600_000;
const now = Date.now();
const dayStart = new Date(now).setHours(0, 0, 0, 0);
const hour = Math.ceil(dayStart / HOUR) * HOUR;
const sec = (ms: number) => Math.floor(ms / 1000);
const failedHour = { hour, turns: 71, cost_usd: 0, unpriced_turns: 0, failed_turns: 71 };
const plan = (name: string, extra: Record<string, unknown>) => ({
  label: name, display_name: name, ref: name.padEnd(8, "0").slice(0, 8), heads: ["claude-splice"], provider: "anthropic",
  credential_present: true, identity_verified: true, can_rename: true, ...extra,
});
const answers: Record<string, unknown> = {
  "/api/status": { registry: [
    { key: "claude-splice", label: "claude-splice", family: "anthropic", authKind: "oauth" },
    { key: "claude-deepseek", label: "claude-deepseek", family: "deepseek", authKind: "api-key" },
    { key: "deepseek-split", label: "deepseek-split", family: "deepseek", authKind: "api-key" },
  ] },
  "/api/accounts": { accounts: [
    plan("work", { five_hour_used_percent: 40, five_hour_reset_epoch_seconds: sec(now - HOUR), five_hour_window_seconds: 18_000,
      seven_day_used_percent: 12, seven_day_reset_epoch_seconds: sec(now + 50 * HOUR), seven_day_window_seconds: 604_800 }),
    plan("quiet", { no_usage_at_epoch_seconds: sec(now - 60_000) }),
    // the unproved login place: the daemon names it "Account" and it holds no reading yet
    plan("Account", { label: "claude-splice", label_default: true, identity_verified: false, can_rename: false, login_place: { id: "claude-splice" } }),
    // seven Claude accounts in all, as desk c had in the walk (item 8)
    ...["lab", "spare", "team", "side"].map((name, i) => plan(name, { five_hour_used_percent: 5 + i, five_hour_reset_epoch_seconds: sec(now + 2 * HOUR),
      five_hour_window_seconds: 18_000, seven_day_used_percent: 9 + i, seven_day_reset_epoch_seconds: sec(now + 50 * HOUR), seven_day_window_seconds: 604_800 })),
  ] },
  "/api/keys": { keys: [
    { name: "DEEPSEEK_API_KEY", heads: [{ head: "claude-deepseek", source: "store" }], first_seen_epoch_seconds: sec(now - 2 * HOUR),
      replaced: [{ fingerprint: "ab12cd34", first_seen_epoch_seconds: sec(now - 30 * 24 * HOUR) }] },
    { name: "SPLIT_KEY", heads: [{ head: "deepseek-split", source: "store" }], first_seen_epoch_seconds: sec(now - 2 * HOUR) },
  ] },
  "/api/auth": {
    "claude-deepseek": { kind: "api-key", login: "manual", present: true, env_var: "DEEPSEEK_API_KEY", key_source: "store", api_key_masked: "sk-a…9c21" },
    "deepseek-split": { kind: "api-key", login: "manual", present: true, env_var: "SPLIT_KEY", key_source: "store", api_key_masked: "set" },
  },
  "/api/budgets": {
    budgets: [
      { head: "claude-deepseek", daily_usd: 5, action: "block", used_usd: 0, unpriced_turns: 0, failed_turns: 71, unpriced_reason: "undeclared" },
      { head: "deepseek-split", daily_usd: 5, action: "block", used_usd: 0, unpriced_turns: 0, failed_turns: 71, unpriced_reason: "undeclared" },
    ],
    day_started_at_epoch_ms: dayStart, day_resets_at_epoch_ms: dayStart + 24 * HOUR,
  },
  "/api/models": { heads: [] },
  "/api/heads": [],
  "/api/economics": { heads: [
    { key: "claude-splice", label: "claude-splice", unpriced_reason: "plan", not_read_hours: [], buckets: [] },
    { key: "claude-deepseek", label: "claude-deepseek", unpriced_reason: "undeclared", not_read_hours: [], buckets: [failedHour] },
    { key: "deepseek-split", label: "deepseek-split", all_accounts_on_plan: false, unpriced_reason: "undeclared", not_read_hours: [],
      buckets: [{ ...failedHour, plan_turns: 0, plan_value_usd: 0, plan_unpriced_turns: 0 }] },
  ] },
};
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path in answers) return Response.json(answers[path]);
    if (/^\/api\/auth\/[^/]+\/order$/.test(path)) return Response.json({ order: [], effective_order: [], next_target: null, following_target: null });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "accounts.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : Response.json({}, { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox", "--disable-background-networking"] });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

async function onPage<T>(check: (page: Page) => Promise<T>, theme = "night", width = 1440): Promise<T> {
  // the page and this file read the same clock, so a day is the same day on both sides: bun's test runner reads UTC,
  // where a key first seen at 7:25 PM CT is already the next day, and the card's "Sep 26" met "Sep 27" (Oct 26, 7:25 PM CT)
  const context = await browser.newContext({ viewport: { width, height: 1000 }, timezoneId: Intl.DateTimeFormat().resolvedOptions().timeZone });
  await context.addInitScript((t) => localStorage.setItem("splice-console-theme", t), theme);
  const page = await context.newPage();
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    await expect(page.locator('[data-meter="claude-deepseek"]')).toBeVisible({ timeout: 15_000 });
    return await check(page);
  } finally {
    await context.close();
  }
}
const text = async (page: Page, selector: string) => (await page.locator(selector).first().innerText()).replace(/\s+/g, " ");
const card = (page: Page, name: string) => page.locator("article.card").filter({ has: page.locator(".name", { hasText: new RegExp(`^${name}$`) }) });

test("a day of failed requests reads $0 of the budget with the failures beside it, never with no price", () => onPage(async (page) => {
  for (const meter of ["claude-deepseek", "deepseek-split"]) {
    const day = await text(page, `[data-meter="${meter}"]`);
    bunExpect(day).toContain("$0 of $5");
    bunExpect(day).toContain("71 failed");
    bunExpect(day).not.toContain("no price");
  }
}), 30_000);

test("the key in use shows its masked tail, a date only where its variable has held two keys, and the key it replaced as one line on its card", () => onPage(async (page) => {
  const dayOf = (ms: number) => new Date(ms).toLocaleDateString("en-US", { month: "short", day: "numeric" });
  const names = (await page.locator("article.card .name").allInnerTexts()).map((t) => t.replace(/\s+/g, " ").trim());
  bunExpect(names).toContain(`DEEPSEEK_API_KEY …9c21 · ${dayOf(now - 2 * HOUR)}`);
  // a replaced key has nothing to act on, so it folds into one quiet line on the card of the key now in use (marlin, Oct 12)
  bunExpect(names.filter((n) => n.startsWith("DEEPSEEK_API_KEY"))).toHaveLength(1);
  await expect(page.locator("article.card", { hasText: "DEEPSEEK_API_KEY" })).toContainText(`Replaced an earlier key · ${dayOf(now - 30 * 24 * HOUR)}`);
  bunExpect(names).toContain("SPLIT_KEY");
  bunExpect(names.join(" ")).not.toContain("sk-a");
}), 30_000);

test("a window whose reset went by reads Ended, and a card with no reading reads Not read", () => onPage(async (page) => {
  await expect(card(page, "work")).toContainText("Ended");
  await expect(card(page, "work")).not.toContainText(/\bReset\b/);
  await expect(card(page, "Account")).toContainText("Not read");
  await expect(card(page, "Account")).not.toContainText("No limits reported");
  await expect(card(page, "Account").locator(".name-it")).toHaveCount(0);
  await expect(card(page, "Account")).toContainText("Use now");
  await expect(card(page, "quiet")).toContainText("No limits reported");
}), 30_000);

test("in day theme the order rail's numbers keep night's ink in their dark socket", () => onPage(async (page) => {
  const socket = page.locator("article.card:not(.serving) .socket").first();
  await expect(socket).toHaveCSS("color", "rgb(156, 143, 112)");
}, "day"), 30_000);

test("at 3828 a provider with seven accounts spreads over columns, its order carrying on; at 1440 it keeps one", async () => {
  const wide = await onPage(async (page) => page.locator(".provider.wide .rails > .abl-col > .rail").evaluateAll((rails) =>
    rails.map((r) => [...r.querySelectorAll(".card .socket")].map((x) => Number(x.textContent)))), "night", 3828);
  bunExpect(wide.length).toBeGreaterThanOrEqual(2);
  bunExpect(wide.flat()).toEqual([1, 2, 3, 4, 5, 6, 7]);
  bunExpect(wide.every((col) => col.length >= 1)).toBe(true);
  const narrow = await onPage(async (page) => page.locator(".provider.wide").count());
  bunExpect(narrow).toBe(0);
}, 60_000);

test("before the daemon reports failed requests, the meter draws no failed line at all, not even 0 failed", async () => {
  const saved = { budgets: answers["/api/budgets"], economics: answers["/api/economics"] };
  const strip = (o: unknown) => JSON.parse(JSON.stringify(o, (k, v) => (k === "failed_turns" ? undefined : v)));
  answers["/api/budgets"] = strip(saved.budgets);
  answers["/api/economics"] = strip(saved.economics);
  try {
    await onPage(async (page) => {
      for (const meter of ["claude-deepseek", "deepseek-split"]) {
        const day = await text(page, `[data-meter="${meter}"]`);
        bunExpect(day).toContain("$5");
        bunExpect(day).not.toContain("failed");
        bunExpect(day).not.toContain("no price");
      }
    });
  } finally {
    answers["/api/budgets"] = saved.budgets;
    answers["/api/economics"] = saved.economics;
  }
}, 30_000);
