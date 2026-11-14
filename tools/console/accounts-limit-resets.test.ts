// A Claude login's limit resets on Accounts (Nov 13, 2026), through the real page against a stub daemon that answers in
// the routes' own shapes (LimitResetRoutes.kt): the login's menu opens them, each kind says how many are left, and a
// reset is spent only on a second press of the same act, which first says what it clears. A kind the provider will not
// spend now says why and offers no act.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HOUR = 3_600_000;
const now = Date.now();
const sec = (ms: number) => Math.floor(ms / 1000);
const HEAD = "claude";
const login = (label: string, name: string, used: number) => ({
  label, display_name: name, ref: label.padEnd(8, "0").slice(0, 8), heads: [HEAD], provider: "anthropic",
  account_labels: { [HEAD]: label }, credential_present: true, identity_verified: true,
  can_rename: true, can_remove: true, edit_target: { kind: "pool", id: label },
  five_hour_used_percent: used, five_hour_reset_epoch_seconds: sec(now + 2 * HOUR), five_hour_window_seconds: 18_000,
  seven_day_used_percent: 100, seven_day_reset_epoch_seconds: sec(now + 50 * HOUR), seven_day_window_seconds: 604_800,
  observed_at_epoch_seconds: sec(now), pinned_heads: [],
});
// what the provider holds for each login: a reset spent here is gone from the next read
const left: Record<string, Record<string, number>> = { first: { fable: 2, weekly: 1 } };
const resetsOf = (label: string) => ({
  label, offered: true,
  kinds: [
    { kind: "fable", name: "Fable reset", left: left[label]?.fable ?? 0, total: 3, usable_now: true },
    { kind: "weekly", name: "Weekly reset", left: left[label]?.weekly ?? 0, total: 1, usable_now: false,
      not_now: "Your weekly limit isn't reached yet" },
  ],
});
const answers: Record<string, unknown> = {
  "/api/status": { registry: [{ key: HEAD, label: HEAD, family: "anthropic", authKind: "oauth" }] },
  "/api/accounts": { accounts: [login("first", "First", 40)], head_pools: {} },
  "/api/keys": { keys: [] },
  "/api/budgets": { budgets: [] },
  "/api/models": { heads: [] },
  "/api/heads": [],
  "/api/economics": { heads: [{ key: HEAD, label: HEAD, unpriced_reason: "plan", not_read_hours: [], buckets: [] }] },
  "/api/auth": {},
  "/api/sessions": { sessions: [] },
};
const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    if (path === `/api/auth/${HEAD}/resets`) return Response.json({ head: HEAD, logins: [resetsOf(url.searchParams.get("label") ?? "")] });
    if (req.method === "POST" && path === `/api/auth/${HEAD}/reset`) {
      const { label, kind } = (await req.json()) as { label: string; kind: string };
      const held = (left[label] ??= {});
      held[kind] = (held[kind] ?? 0) - 1;
      return Response.json({ ok: true, label, kind, outcome: "reset", said: "Fable reset spent; the limit is clear.", left: held[kind], cleared: ["fable"], read_again: true });
    }
    if (/^\/api\/auth\/[^/]+\/order$/.test(path)) return Response.json({ order: [], effective_order: ["first"], next_target: "first" });
    if (/^\/api\/heads\/[^/]+\/turns\/live$/.test(path)) return Response.json({ turns: [] });
    if (path in answers) return Response.json(answers[path]);
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "accounts.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
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

const card = (page: Page) => page.locator("article.card").filter({ has: page.locator(".name", { hasText: /^First$/ }) });
async function openResets(check: (page: Page) => Promise<void>) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    await expect(card(page)).toBeVisible({ timeout: 15_000 });
    await card(page).getByRole("button", { name: "More" }).click();
    await card(page).getByRole("menuitem", { name: "Limit resets" }).click();
    await expect(card(page).locator(".resets .rs-row")).toHaveCount(2, { timeout: 10_000 });
    await check(page);
  } finally {
    await page.close();
  }
}

test("a reset is spent on the second press of its act, and the login reads one fewer after it", () => openResets(async (page) => {
  const fable = card(page).locator(".resets .rs-row").filter({ hasText: "Fable reset" });
  await expect(fable).toContainText("2 of 3 left");
  await fable.getByRole("button", { name: "Use one" }).click();
  // the first press spends nothing: it says what the second clears, and the count stands
  await expect(card(page).locator(".resets .rs-arm")).toContainText("Clears the Fable limit on First now, 1 left after");
  await expect(fable).toContainText("2 of 3 left");
  await fable.getByRole("button", { name: "Reset Fable now" }).click();
  await expect(card(page).locator(".resets")).toContainText("Fable reset spent; the limit is clear.", { timeout: 10_000 });
  await expect(card(page).locator(".resets .rs-row").filter({ hasText: "Fable reset" })).toContainText("1 of 3 left");
}), 30_000);

test("a kind the provider will not spend now says why and offers no act", () => openResets(async (page) => {
  const weekly = card(page).locator(".resets .rs-row").filter({ hasText: "Weekly reset" });
  await expect(weekly).toContainText("Your weekly limit isn't reached yet");
  await expect(weekly.getByRole("button")).toHaveCount(0);
}), 30_000);
