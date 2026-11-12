// Accounts says why a pinned login is not serving, on every load, through the real page against a stub daemon in the
// routes' own shapes (Nov 3, 2026). Marcos pressed Use now on account 3 ("clicking 'use now' also didnt work"); the pin
// now outlives a restart, and the pool's projection (/api/auth's account_pool) carries why the pinned login serves no
// turn, or which models it cannot serve yet. Goal: the pinned card says it under the card, as a page load reads it, not
// only in the moment after the press; a pin that serves every model, and a card that is not the pin, say nothing.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HOUR = 3_600_000;
const now = Date.now();
const sec = (ms: number) => Math.floor(ms / 1000);
const HEAD = "claude-splice";
const windows = {
  five_hour_used_percent: 20, five_hour_reset_epoch_seconds: sec(now + 2 * HOUR), five_hour_window_seconds: 18_000,
  seven_day_used_percent: 30, seven_day_reset_epoch_seconds: sec(now + 50 * HOUR), seven_day_window_seconds: 604_800,
  observed_at_epoch_seconds: sec(now - 60_000),
};
const login = (name: string, label: string, pinned: boolean) => ({
  label, selector_key: label, display_name: name, ref: label.padEnd(8, "0").slice(0, 8), heads: [HEAD], provider: "anthropic", kind: "client",
  credential_present: true, identity_verified: true, can_rename: true, pinned_heads: pinned ? [HEAD] : [], ...windows,
});
const REFUSAL = "5-hour plan limit reached: 'account-3' serves no turn until 12:20 PM; turns use 'account-2' until then";
const FABLE_BACK = sec(now + 90 * HOUR);
// the head's pool as /api/auth projects it; each test sets the pin's state
let pool: Record<string, unknown> = {};
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    const answers: Record<string, unknown> = {
      "/api/status": { registry: [{ key: HEAD, label: HEAD, family: "anthropic", authKind: "client" }] },
      "/api/accounts": { accounts: [login("work", "account-2", false), login("home", "account-3", pool.pinned_label === "account-3")] },
      "/api/auth": { [HEAD]: { account_pool: { selected_label: null, next_target_label: "account-2", accounts: [], ...pool } } },
      [`/api/auth/${HEAD}/order`]: { order: [], effective_order: ["account-2", "account-3"], next_target: "account-2", following_target: "account-3" },
    };
    if (path in answers) return Response.json(answers[path]);
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
  console.error("[accounts-pin-reason.test.ts afterAll] browser.close: start");
  await browser.close();
  console.error("[accounts-pin-reason.test.ts afterAll] browser.close: resolved");
  server.stop(true);
  console.error("[accounts-pin-reason.test.ts afterAll] server.stop(true): returned");
});

async function onPage(check: (page: Page) => Promise<void>) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, colorScheme: "dark" });
  await context.addInitScript(() => localStorage.setItem("splice-console-theme", "night"));
  const page = await context.newPage();
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    await expect(page.locator(".abl-sw")).toBeVisible({ timeout: 15_000 });
    await check(page);
  } finally {
    await context.close();
  }
}

const card = (page: Page, label: string) => page.locator(`article[data-a="${HEAD}/${label}"]`);

test("a pinned login held for every model says why under its card when the page loads", () => {
  pool = { pinned_label: "account-3", pin_refusal: REFUSAL };
  return onPage(async (page) => {
    await expect(card(page, "account-3").locator(".reason")).toHaveText(REFUSAL);
    await expect(card(page, "account-2").locator(".reason")).toHaveCount(0);
  });
}, 30_000);

test("a pinned login held for one model's week names the model and when it serves again", () => {
  pool = { pinned_label: "account-3", pin_models_held: [{ model: "claude-fable-5-1", until_epoch_seconds: FABLE_BACK }] };
  return onPage(async (page) => {
    await expect(card(page, "account-3").locator(".reason")).toContainText(/Fable 5\.1 is at its weekly limit here until .+, so its turns use another login\./);
  });
}, 30_000);

test("a pin that serves every model says nothing", () => {
  pool = { pinned_label: "account-3" };
  return onPage(async (page) => {
    await expect(card(page, "account-3")).toBeVisible();
    await expect(page.locator("article .reason")).toHaveCount(0);
  });
}, 30_000);
