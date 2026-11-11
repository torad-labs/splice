// A login its provider blocked has an act of its own on its card: Try again (Marcos, Nov 11, 2026: "I did sign in again
// because it said it was blocked, I realized my payument failed, so I paid again"). Until that day the card read
// "Blocked until its organization allows subscription access" and offered only Sign in again, in its menu, which lifts
// no block. Through the real page against a stub daemon in the routes' own shapes (AskAgainRoute.kt, AccountsRoute.kt):
// the blocked card offers Try again, pressing it has splice end that login's hold, and the card goes on reading Blocked,
// now with "its next turn asks again" and nothing left to press, since only its provider's next answer lifts a block
// (it read as a login a turn could take for the three minutes before Anthropic refused it again, Nov 11, 11:36 AM CT);
// and a press splice refuses says so on the card, which stays as it was.
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
const BLOCKED = "Blocked until its organization allows subscription access";
const windows = {
  five_hour_used_percent: 20, five_hour_reset_epoch_seconds: sec(now + 2 * HOUR), five_hour_window_seconds: 18_000,
  seven_day_used_percent: 30, seven_day_reset_epoch_seconds: sec(now + 50 * HOUR), seven_day_window_seconds: 604_800,
  observed_at_epoch_seconds: sec(now - 60_000),
};
// The logins splice holds a block on, by the label the pool knows each under, those of them still held (a hold ends at
// its time or at a Try again, and the block stays named), and what it answers a Try again with.
const blocked = new Set<string>();
const held = new Set<string>();
let refuses: string | null = null;
const login = (name: string, label: string) => ({
  label, selector_key: label, display_name: name, ref: label.padEnd(8, "0").slice(0, 8), heads: [HEAD], provider: "anthropic", kind: "client",
  credential_present: true, identity_verified: true, can_rename: true, pinned_heads: [], ...windows,
  available: !held.has(label), owner_block: blocked.has(label) ? BLOCKED : null,
  auth_excluded_until_epoch_millis: held.has(label) ? now + HOUR : null,
});

const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (req.method === "POST" && path === `/api/auth/${HEAD}/ask`) {
      if (refuses) return Response.json({ error: refuses }, { status: 400 });
      held.delete((await req.json()).label);
      return Response.json({ ok: true });
    }
    const answers: Record<string, unknown> = {
      "/api/status": { registry: [{ key: HEAD, label: HEAD, family: "anthropic", authKind: "client" }] },
      "/api/accounts": { accounts: [login("work", "account-2"), login("torad", "account-3")] },
      "/api/auth": { [HEAD]: { account_pool: { selected_label: null, next_target_label: "account-2", accounts: [] } } },
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
  await browser.close();
  server.stop(true);
});

const card = (page: Page, label: string) => page.locator(`article[data-a="${HEAD}/${label}"]`);
// [answer]: what splice says to a Try again; null lets go of the login
async function onPage(check: (page: Page) => Promise<void>, answer: string | null = null) {
  blocked.clear(); held.clear();
  blocked.add("account-3"); held.add("account-3");
  refuses = answer;
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, colorScheme: "dark" });
  await context.addInitScript(() => localStorage.setItem("splice-console-theme", "night"));
  const page = await context.newPage();
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    await expect(card(page, "account-3")).toContainText(BLOCKED, { timeout: 15_000 });
    await check(page);
  } finally {
    await context.close();
  }
}

test("a blocked login offers Try again, and pressing it leaves the card Blocked, saying its next turn asks again", () => onPage(async (page) => {
  // the login nothing blocks has no such act: it is Use now there
  await expect(card(page, "account-2").getByRole("button", { name: "Try again" })).toHaveCount(0);
  await expect(card(page, "account-3")).not.toContainText("its next turn asks again");
  await card(page, "account-3").getByRole("button", { name: "Try again" }).click();
  await expect(card(page, "account-3")).toContainText("its next turn asks again", { timeout: 10_000 });
  await expect(card(page, "account-3")).toContainText(BLOCKED);
  // nothing is left to press, and it is not offered as a login to use now: its provider has not served it
  await expect(card(page, "account-3").getByRole("button", { name: "Try again" })).toHaveCount(0);
  await expect(card(page, "account-3").getByRole("button", { name: "Use now" })).toHaveCount(0);
}), 30_000);

test("a Try again splice refuses says so on the card, which stays blocked and held", () => onPage(async (page) => {
  await card(page, "account-3").getByRole("button", { name: "Try again" }).click();
  await expect(card(page, "account-3")).toContainText("Not asked", { timeout: 10_000 });
  await expect(card(page, "account-3")).toContainText(BLOCKED);
  await expect(card(page, "account-3")).not.toContainText("its next turn asks again");
  await expect(card(page, "account-3").getByRole("button", { name: "Try again" })).toBeVisible();
}, "head 'claude-splice' has no account pool to ask"), 30_000);
