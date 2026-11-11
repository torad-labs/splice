// + Add account adds a login beside the ones a command has (Marcos, Nov 11, 2026: "I had issues before like this adding
// new accounts, removing"). A sign-in splice is sent with no label is the command's FIRST login signing in again, and it
// lands over that login's file (LoginIo.persistIfSignedIn); a place of its own is asked for as "auto"
// (OAuthAccountFiles.loginAccount). Since Oct 13 only Muse and Kimi commands asked for one, so on a ChatGPT or Grok
// command + Add account, signed in as another account, left one card holding the new account where the first had been.
// An add that comes back as the account the first login already holds adds nothing, where it filed a twin of it.
// Through the real page against a stub daemon that files a sign-in the way splice does.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HOUR = 3_600_000;
const now = Date.now();
const sec = (ms: number) => Math.floor(ms / 1000);
const windows = {
  five_hour_used_percent: 20, five_hour_reset_epoch_seconds: sec(now + 2 * HOUR), five_hour_window_seconds: 18_000,
  seven_day_used_percent: 30, seven_day_reset_epoch_seconds: sec(now + 50 * HOUR), seven_day_window_seconds: 604_800,
  observed_at_epoch_seconds: sec(now - 60_000),
};
// the commands whose sign-in is an OAuth flow splice runs itself, by the words /api/status and /api/accounts use for them
const COMMANDS = [
  { head: "claudex", family: "openai", provider: "codex", kind: "chatgpt-oauth" },
  { head: "claude-grok", family: "xai", provider: "xai", kind: "grok-oauth" },
];
type Login = { head: string; label: string | null; name: string; primary: boolean; present: boolean };
// The logins splice holds. Each command starts with its first login alone, which carries no label.
let logins: Login[] = [];
const row = (l: Login) => {
  const c = COMMANDS.find((x) => x.head === l.head)!;
  return {
    label: l.label, display_name: l.name, ref: l.name.padEnd(8, "0").slice(0, 8), heads: [l.head], provider: c.provider, kind: c.kind,
    primary: l.primary, credential_present: l.present, identity_verified: true, can_rename: true, can_remove: true, pinned_heads: [],
    account_labels: {}, available: l.present, refusal: l.present ? null : "signed out", ...(l.present ? windows : {}),
  };
};
// what splice says of an add that came back as the first login's account (OAuthAccountFiles.writeTokenDerived)
const FIRST_REASON = "that account is already this command's first login, so nothing was added. To add another account, finish the sign-in page as that other account";
// The account the sign-in page's browser holds: another one, "second", or the one the command's first login is for.
let browserHolds: "second" | "first" = "second";
const ended = (kind: string, reason: string) =>
  ({ id: crypto.randomUUID(), state: "failed", failure_kind: kind, failure_reason: reason, label: null, user_code: null, verification_uri: null, browser_url: null });
// One finished sign-in, in LoginRoutes' shape.
function signIn(head: string, label: unknown) {
  const first = logins.find((l) => l.head === head && l.primary)!;
  let filed: string | null = null;
  if (label === "auto" && first.present && browserHolds === "first") return ended("already_added", FIRST_REASON);
  if (label === "auto" && first.present) { // a place of its own, beside the first login
    filed = `${head}-2`;
    // an account already in the pool lands on its own login's file again, under the label it has (OAuthAccountWrites)
    if (!logins.some((l) => l.head === head && l.label === filed)) logins.push({ head, label: filed, name: "second", primary: false, present: true });
  } else if (label === "auto") { // splice refuses a place in a pool whose first login holds no file
    return ended("config", "sign in without --label first to create the primary account");
  } else { // no label: the first login signs in again, and its file now holds the account that signed in
    Object.assign(first, { name: "second", present: true });
  }
  return { id: crypto.randomUUID(), state: "signed_in", label: filed, failure_kind: null, failure_reason: null, user_code: null, verification_uri: null, browser_url: null };
}

const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    const head = path.match(/^\/api\/auth\/([^/]+)\/login$/)?.[1];
    if (req.method === "POST" && head) return Response.json(signIn(head, (await req.json().catch(() => null))?.label));
    if (path === "/api/status") return Response.json({ registry: COMMANDS.map((c) => ({ key: c.head, label: c.head, family: c.family, authKind: c.kind })) });
    if (path === "/api/accounts") return Response.json({ accounts: logins.map(row) });
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

const card = (page: Page, name: string) => page.locator("article.card").filter({ has: page.locator(".name", { hasText: new RegExp(`^${name}(?: \\d+)?$`) }) });
// [present]: whether each command's first login holds its file
async function onPage(check: (page: Page) => Promise<void>, present = true, holds: typeof browserHolds = "second") {
  browserHolds = holds;
  logins = COMMANDS.map((c) => ({ head: c.head, label: null, name: `first-${c.head}`, primary: true, present }));
  const context = await browser.newContext({ viewport: { width: 1440, height: 1200 }, colorScheme: "dark" });
  await context.addInitScript(() => localStorage.setItem("splice-console-theme", "night"));
  const page = await context.newPage();
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    await expect(card(page, `first-${COMMANDS[0]!.head}`)).toBeVisible({ timeout: 15_000 });
    await check(page);
  } finally {
    await context.close();
  }
}
async function add(page: Page, head: string) {
  await page.locator(`[data-act="add"][data-c="${head}"]`).click();
  await page.locator(`[data-act="add-go"][data-c="${head}"]`).click();
}

for (const c of COMMANDS) {
  test(`+ Add account on ${c.head} adds a login beside its first one, which keeps its account`, () => onPage(async (page) => {
    await add(page, c.head);
    await expect(card(page, "second")).toBeVisible({ timeout: 10_000 });
    await expect(card(page, `first-${c.head}`)).toBeVisible();
  }), 30_000);
}

test("+ Add account that comes back as the account the command's first login holds says Already added, and adds no twin", () => onPage(async (page) => {
  const c = COMMANDS[0]!;
  await add(page, c.head);
  const tile = page.locator(`[data-key="add:${c.head}"]`);
  await expect(tile.locator(".result", { hasText: "Already added" })).toBeVisible({ timeout: 10_000 });
  // the card that account already has pulses once, so the eye lands on it
  await expect(card(page, `first-${c.head}`)).toHaveClass(/pulse-once/);
  await expect(tile.locator(".reason")).toHaveText(FIRST_REASON);
  await expect(page.locator("article.card")).toHaveCount(COMMANDS.length);
}, true, "first"), 30_000);

test("+ Add account that comes back as an added login already there adds nothing, and that login's card pulses once", () => onPage(async (page) => {
  const c = COMMANDS[0]!;
  await add(page, c.head);
  await expect(card(page, "second")).toBeVisible({ timeout: 10_000 });
  await add(page, c.head);
  await expect(card(page, "second")).toHaveClass(/pulse-once/, { timeout: 10_000 });
  await expect(page.locator("article.card")).toHaveCount(COMMANDS.length + 1);
  await expect(page.locator(".result.bad")).toHaveCount(0);
}), 30_000);

test("+ Add account on a command whose first login is signed out signs that one in, since it has no pool to join yet", () => onPage(async (page) => {
  const c = COMMANDS[0]!;
  await add(page, c.head);
  await expect(card(page, "second")).toBeVisible({ timeout: 10_000 });
  await expect(page.locator(".result", { hasText: "Config error" })).toHaveCount(0);
}, false), 30_000);
