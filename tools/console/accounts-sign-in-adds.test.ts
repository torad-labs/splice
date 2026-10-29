// Signing in from the console ADDS an account (Marcos, Oct 27, 2026: "SPlice console login ADDs an account, it doesn't
// replace it"). He pressed Sign in on claude-splice's card "Account", which read Signed out, and got a bare red "In use"
// with nothing to act on. Through the real page against a stub daemon in the routes' own shapes: a sign-in splice
// refuses says its word where it was pressed, on a login's card and on + Add account alike, and splice's own reason
// stands printed under the word; and a command's own login that is signed out and never proved an account draws no card, since
// it holds no account to sign in again (one that proved its account keeps its card, as Marlin ruled on Oct 10).
// Changed Oct 29 by the accounts-login-cards pass: splice's reason is no longer folded behind its word; it is printed in place
// beside the refused act (the word alone where splice gave no reason, never an invented one), so it reads with no press.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
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
// what splice says when the command's own login holds no token (ClaudeNativeAuth.refusal)
const noToken = (command: string) => `No usable access token. Sign in again: run ${command} and use /login.`;
// the reasons the stub's sign-ins end with, as splice words them (ClaudeAccountSignIn, NativeClaudeAuth)
const WRAP_REASON = "claude is the splice launch shim and the wrap state /x is missing";
const ADDED_REASON = "that account is already on this command as 'work'";
const login = (name: string, extra: Record<string, unknown>) => ({
  label: name, display_name: name, ref: name.padEnd(8, "0").slice(0, 8), heads: [HEAD], provider: "anthropic", kind: "client",
  credential_present: true, identity_verified: true, can_rename: true, ...extra,
});
const answers: Record<string, unknown> = {
  "/api/status": { registry: [{ key: HEAD, label: HEAD, family: "anthropic", authKind: "client" }] },
  "/api/accounts": { accounts: [
    // an account added to claude-splice, signed in
    login("work", { ...windows, label: "account-2", selector_key: "account-2" }),
    // the plain claude command's own login: it proved its account, then its token went. Its name is its address, which
    // the console never uses as a title, so its card reads by provider ("Claude")
    login("me@example.invalid", { ...windows, label: "claude", selector_key: "native:claude", email: "me@example.invalid",
      refusal: noToken("claude"), login_place: { id: "claude" }, edit_target: { kind: "native", id: "claude" } }),
    // claude-splice's own login: its file is there with no token in it, and no profile ever proved an account
    login("Account", { label: "claude-splice", selector_key: "native:claude-splice", ref: null, label_default: true,
      identity_verified: false, can_rename: false, refusal: noToken("claude-splice"), login_place: { id: "claude-splice" },
      edit_target: { kind: "native", id: "claude-splice" } }),
  ] },
};
const failed = (kind: string, reason: string, label: string | null) =>
  ({ id: crypto.randomUUID(), state: "failed", failure_kind: kind, failure_reason: reason, label, user_code: null, verification_uri: null, browser_url: null });
// The sign-ins the page started, by route. Each ends the way splice ends a sign-in it refuses.
const started: string[] = [];
const signIns: Record<string, () => unknown> = {
  "/api/claude-logins/claude/login": () => failed("config", WRAP_REASON, null),
  [`/api/auth/${HEAD}/login`]: () => failed("already_added", ADDED_REASON, "account-2"),
};
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (req.method === "POST" && path in signIns) {
      started.push(path);
      return Response.json(signIns[path]!());
    }
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

async function onPage(check: (page: Page) => Promise<void>, width = 1440) {
  started.length = 0;
  const context = await browser.newContext({ viewport: { width, height: 1000 }, colorScheme: "dark" });
  await context.addInitScript(() => localStorage.setItem("splice-console-theme", "night"));
  const page = await context.newPage();
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    await expect(card(page, "work")).toBeVisible({ timeout: 15_000 });
    await check(page);
  } finally {
    await context.close();
  }
}
const card = (page: Page, name: string) => page.locator("article.card").filter({ has: page.locator(".name", { hasText: new RegExp(`^${name}$`) }) });

test("a command's own login that is signed out and never proved an account draws no card; one that proved its account keeps its card", () => onPage(async (page) => {
  await expect(card(page, "Claude")).toContainText("Signed out");
  await expect(card(page, "Claude").getByRole("button", { name: "Sign in" })).toBeVisible();
  await expect(card(page, "Account")).toHaveCount(0);
  await expect(page.locator("article.card", { hasText: "Signed out" })).toHaveCount(1);
}), 30_000);

test("a Sign in splice refuses on a login's card says its word there, and splice's reason is printed under it", () => onPage(async (page) => {
  const there = card(page, "Claude");
  await there.getByRole("button", { name: "Sign in" }).click();
  const word = there.locator(".result", { hasText: "Config error" });
  await expect(word).toBeVisible({ timeout: 10_000 });
  bunExpect(started).toEqual(["/api/claude-logins/claude/login"]);
  // the reason stands printed under its word with no press, and the word is plain text, not an act
  await expect(there.locator(".reason")).toHaveText(WRAP_REASON);
  await expect(there.getByRole("button", { name: "Config error" })).toHaveCount(0);
  // a config refusal fails again until it is fixed, so it offers no Try again, and Cancel closes it
  await expect(there.getByRole("button", { name: "Try again" })).toHaveCount(0);
  await there.getByRole("button", { name: "Cancel" }).click();
  await expect(there.getByRole("button", { name: "Sign in" })).toBeVisible();
}), 30_000);

test("+ Add account that signs in an account already there says Already added, and splice's reason is printed under it", () => onPage(async (page) => {
  await page.locator(`[data-act="add"][data-c="${HEAD}"]`).click();
  await page.locator(`[data-act="add-go"][data-c="${HEAD}"]`).click();
  const tile = page.locator(`[data-key="add:${HEAD}"]`);
  const word = tile.locator(".result", { hasText: "Already added" });
  await expect(word).toBeVisible({ timeout: 10_000 });
  bunExpect(started).toEqual([`/api/auth/${HEAD}/login`]);
  await expect(tile.locator(".reason")).toHaveText(ADDED_REASON);
}), 30_000);
