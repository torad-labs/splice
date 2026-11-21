// Signing in on Accounts (Night Deck), in the login's glass: a login whose provider refused its credential reads that it
// needs signing in; Sign in starts splice's sign-in for that login, the page splice opens is a link, the code it shows is
// pasted back to splice; Cancel ends the sign-in on splice; a failed one says what failed in splice's words with Try
// again; Add account starts a sign-in with no label, so it adds a login beside the others. Through the real page against
// a stub daemon in the routes' shapes (LoginRoutes.kt), made-up rows. (Rewritten from accounts-sign-in-paste,
// accounts-sign-in-cancel, accounts-try-again, accounts-signed-out-read, accounts-sign-in-adds and accounts-add-joins,
// which pinned the old page's cards.)
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { expect, type Browser, type Page } from "@playwright/test";
import { launch, stub, type Stub } from "./deck-stub.ts";

const HOUR = 3_600_000, NOW = Date.now(), sec = (ms: number) => Math.floor(ms / 1000);
const login = (label: string, name: string, over: Record<string, unknown> = {}) => ({
  provider: "anthropic", heads: ["claude"], account_labels: { claude: label }, kind: "client", label, selector_key: label,
  display_name: name, email: `${label}@made-up.invalid`, ref: null, credential_present: true, can_rename: true, can_remove: true,
  edit_target: { kind: "pool", id: label }, available: true, selected: false, observed_at_epoch_seconds: sec(NOW), ...over,
});
const ROWS = [
  login("harbor", "Harbor work", { selected: true }),
  login("quay", "Quay old", { last_read_failure: { reason: "signed_out", at_epoch_seconds: sec(NOW - HOUR) } }),
];
const WAITING = { id: "login-1", state: "waiting", user_code: null, verification_uri: null, browser_url: "https://sign-in.made-up.invalid/authorize", paste_code: true };
let failNext = false;

let s: Stub;
let browser: Browser;
beforeAll(async () => {
  s = stub({
    "/api/heads": { heads: [{ key: "claude", label: "claude-splice", gate: {} }] },
    "/api/status": { registry: [{ key: "claude", label: "claude-splice", family: "anthropic" }] },
    "/api/models": { heads: [] },
    "/api/accounts": { accounts: ROWS },
    "/api/auth/claude/order": { order: ["harbor", "quay"], next_target: "harbor", following_target: null },
    "POST /api/auth/claude/login": () => (failNext
      ? { ...WAITING, state: "failed", failure_kind: "network", failure_reason: "the sign-in page did not answer" }
      : WAITING),
    "/api/auth/claude/login/login-1": WAITING,
    "DELETE /api/auth/claude/login/login-1": { ok: true },
    "/api/auth/claude/login/login-1/code": WAITING,
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
  await expect(page.locator(".lane")).toHaveCount(2, { timeout: 10_000 });
  return page;
}
const sent = (method: string, path: string) => s.calls.filter((c) => c.method === method && c.path === path);
const glass = (page: Page) => page.locator(".lens:not([hidden])");

test("a refused credential reads as needing sign-in; Sign in starts it for that login, and the pasted code goes to splice", async () => {
  const page = await at();
  try {
    const quay = page.locator(".lane", { has: page.locator(".mark .nm", { hasText: "Quay old" }) });
    await expect(quay).toContainText("needs sign-in");
    await quay.locator('[data-act="signin"]').click();
    await expect.poll(() => sent("POST", "/api/auth/claude/login").map((c) => c.body)).toEqual([{ label: "quay" }]);
    await expect(glass(page).getByRole("link", { name: "Open the sign-in page" })).toHaveAttribute("href", WAITING.browser_url);
    await glass(page).locator("input").fill("pasted-code-1");
    await glass(page).locator("input").press("Enter");
    await expect.poll(() => sent("POST", "/api/auth/claude/login/login-1/code").map((c) => c.body)).toEqual([{ code: "pasted-code-1" }]);
    await glass(page).locator('[data-act="cancel-signin"]').click();
    await expect.poll(() => sent("DELETE", "/api/auth/claude/login/login-1").length).toBe(1);
    await expect(glass(page).locator("input")).toHaveCount(0);
  } finally {
    await page.close();
  }
}, 30_000);

test("a failed sign-in says what failed in splice's words, and Try again starts a new one", async () => {
  failNext = true;
  const page = await at();
  try {
    await page.locator(".lane .mark .nm", { hasText: "Quay old" }).click();
    const before = sent("POST", "/api/auth/claude/login").length;
    await glass(page).locator('.acts [data-act="signin"]').click();
    await expect(glass(page).locator(".sign-word")).toHaveText("Network error");
    await expect(glass(page).locator(".sign-why")).toHaveText("the sign-in page did not answer");
    failNext = false;
    await glass(page).getByRole("button", { name: "Try again" }).click();
    await expect.poll(() => sent("POST", "/api/auth/claude/login").length).toBe(before + 2);
    await expect(glass(page).locator("input")).toBeVisible();
  } finally {
    failNext = false;
    await page.close();
  }
}, 30_000);

test("Add account starts a sign-in with no label, so splice adds a login beside the others", async () => {
  const page = await at();
  try {
    const before = sent("POST", "/api/auth/claude/login").length;
    await page.getByRole("button", { name: "Add account" }).click();
    await glass(page).locator('[data-act="signin"]').click();
    await expect.poll(() => sent("POST", "/api/auth/claude/login").slice(before).map((c) => c.body)).toEqual([{}]);
    bunExpect(await glass(page).locator("h3").innerText()).toBe("Add account");
  } finally {
    await page.close();
  }
}, 30_000);
