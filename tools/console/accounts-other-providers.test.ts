// Accounts (Night Deck), the other providers: each login of a provider that is not Claude opens the account glass from
// the right rail, and from it is switched to, renamed and signed out on the routes of its own command, never Claude's;
// a command with one login offers no Use now; a provider that signs in with a login takes another one from Add login,
// and one that takes an API key has none. The old Accounts page managed every provider's logins this way; the page as
// first rebuilt managed Claude's alone and only listed the rest (Nov 21, 2026). Through the real page against a stub
// daemon with made-up rows.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { expect, type Browser, type Page } from "@playwright/test";
import { launch, stub, type Stub } from "./deck-stub.ts";

const NOW = Date.now(), sec = (ms: number) => Math.floor(ms / 1000);
const login = (head: string, provider: string, kind: string, label: string, name: string, over: Record<string, unknown> = {}) => ({
  provider, heads: [head], account_labels: { [head]: label }, kind, label, selector_key: label, display_name: name,
  email: `${label}@made-up.invalid`, ref: null, credential_present: true, identity_verified: true, can_rename: true, can_remove: true,
  edit_target: { kind: "pool", id: label }, plan: "plus", available: true, selected: false, observed_at_epoch_seconds: sec(NOW), ...over,
});
const ROWS = [
  login("claude", "anthropic", "client", "harbor", "Harbor work", { selected: true }),
  login("acme", "openai", "chatgpt-oauth", "north", "North desk", { selected: true }),
  login("acme", "openai", "chatgpt-oauth", "south", "South desk", { ref: "50f7" }),
  login("globex", "xai", "grok-oauth", "only", "Only one"),
];

let s: Stub;
let browser: Browser;
beforeAll(async () => {
  s = stub({
    "/api/heads": { heads: [{ key: "claude", label: "claude-splice" }, { key: "acme", label: "claude-acme" }, { key: "globex", label: "claude-globex" }, { key: "keyed", label: "claude-keyed" }] },
    "/api/status": { registry: [
      { key: "claude", label: "claude-splice", family: "anthropic", authKind: "client" },
      { key: "acme", label: "claude-acme", family: "openai", authKind: "chatgpt-oauth" },
      { key: "globex", label: "claude-globex", family: "xai", authKind: "grok-oauth" },
      { key: "keyed", label: "claude-keyed", family: "deepseek", authKind: "api-key" },
    ] },
    "/api/models": { heads: [] },
    "/api/accounts": { accounts: ROWS },
    "/api/economics": { heads: [] },
    "/api/sessions": { sessions: [] },
    "/api/keys": { keys: [{ name: "KEYED_API_KEY", head: "keyed", source: "keyring" }] },
    "POST /api/auth/acme/login": { id: "s-1", state: "waiting", browser_url: "https://signin.example.invalid/start" },
    "/api/auth/acme/login/s-1": { id: "s-1", state: "waiting", browser_url: "https://signin.example.invalid/start" },
  });
  browser = await launch();
}, 30_000);
afterAll(async () => {
  await browser?.close();
  s?.stop();
});

async function at(): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  await page.goto(`${s.url}/accounts.html`);
  await expect(page.locator(".rail-r .acct[data-id]")).toHaveCount(3, { timeout: 10_000 });
  return page;
}
const row = (page: Page, name: string) => page.locator(".rail-r .acct[data-id]", { hasText: name });
const glass = (page: Page) => page.locator(".lens:not([hidden])");
const sent = (method: string, path: string) => s.calls.filter((c) => c.method === method && c.path === path);

test("a login of another provider opens its glass, and Use now switches its own command to it", async () => {
  const page = await at();
  try {
    await expect(row(page, "North desk")).toContainText("serving");
    await row(page, "South desk").click();
    await expect(glass(page).locator(".login")).toHaveText("south@made-up.invalid");
    await glass(page).locator('[data-act="use"]').click();
    await expect.poll(() => sent("POST", "/api/auth/acme/switch").map((c) => c.body)).toEqual([{ label: "south" }]);
    bunExpect(sent("POST", "/api/auth/claude/switch")).toEqual([]);
  } finally {
    await page.close();
  }
}, 30_000);

test("a command with one login has no Use now in its glass", async () => {
  const page = await at();
  try {
    await row(page, "Only one").click();
    await expect(glass(page).locator('[data-act="signout"]')).toBeVisible();
    await expect(glass(page).locator('[data-act="use"]')).toHaveCount(0);
  } finally {
    await page.close();
  }
}, 30_000);

test("Sign out removes the login from its own command on one press, and Rename names it by its ref", async () => {
  const page = await at();
  try {
    await row(page, "South desk").click();
    await glass(page).locator('[data-act="rename"]').click();
    await page.keyboard.press("ControlOrMeta+a");
    await page.keyboard.type("South away");
    await page.keyboard.press("Enter");
    await expect.poll(() => sent("PUT", "/api/accounts/names/50f7").map((c) => c.body)).toEqual([{ name: "South away" }]);
    await row(page, "North desk").click();
    await glass(page).locator('[data-act="signout"]').click();
    await expect.poll(() => sent("DELETE", "/api/auth/acme/accounts/north").length).toBe(1);
  } finally {
    await page.close();
  }
}, 30_000);

test("Add login starts a sign-in for that provider's command; a provider that takes a key has none", async () => {
  const page = await at();
  try {
    await expect(page.locator('.rail-r [data-add="keyed"]')).toHaveCount(0);
    await page.locator('.rail-r [data-add="acme"]').click();
    await expect(glass(page)).toContainText("A ChatGPT login for claude-acme");
    await glass(page).locator('[data-act="signin"]').click();
    await expect.poll(() => sent("POST", "/api/auth/acme/login").map((c) => c.body)).toEqual([{}]);
    await expect(glass(page).locator("a", { hasText: "Open the sign-in page" })).toBeVisible();
  } finally {
    await page.close();
  }
}, 30_000);
