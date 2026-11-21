// nd-accounts-signin: an account that needs signing in says so on its lane, and its glass offers Sign in; pressing it
// starts splice's sign-in for that account and shows where to paste the code. The composer on Accounts asks the
// manager.
import { afterAll, beforeAll, test } from "bun:test";
import { expect, type Browser } from "@playwright/test";
import { ACCOUNTS, browser, daemon, HOUR, lens, NOW, open, world, type Daemon } from "./lib/deck.ts";

const sec = (ms: number) => Math.floor(ms / 1000);
const spare = {
  ...ACCOUNTS.accounts[0], label: "account-4", selector_key: "account-4", display_name: "Acme spare", ref: "account4",
  credential_present: false, last_read_failure: { reason: "signed_out", at_epoch_seconds: sec(NOW - HOUR) },
};
let b: Browser;
let d: Daemon;
beforeAll(async () => {
  b = await browser();
  d = daemon(world({
    "/api/accounts": { accounts: [...ACCOUNTS.accounts, spare] },
    "/api/auth/claude-splice/login": (req: Request) => req.method === "POST"
      ? { id: "login-1", state: "waiting", label: "account-4", user_code: null, verification_uri: null,
          browser_url: "https://claude.example.invalid/authorize?code=true", paste_code: true, failure_kind: null, failure_reason: null }
      : {},
    "/api/auth/claude-splice/login/login-1": { id: "login-1", state: "waiting", label: "account-4", user_code: null, verification_uri: null,
      browser_url: "https://claude.example.invalid/authorize?code=true", paste_code: true, failure_kind: null, failure_reason: null },
    "/api/auth/logins": { logins: [] },
    "/api/manager": { folder: "/work/manager", consented: true, state: "live", head: "claude-splice", session_id: "m-1", status: "idle",
      line: { model: "Opus 5.5", model_id: "claude-opus-5-5", effort: "high", at: NOW }, heads: [{ key: "claude-splice", label: "claude", ready: true }] },
    "/api/sessions/m-1/transcript": { messages: [], earlier: null, moves: [] },
    "/api/sessions/m-1/say": { ok: true },
  }));
}, 30_000);
afterAll(async () => { await b?.close(); d?.stop(); });

test("a signed-out account offers Sign in, which starts splice's sign-in for it and asks for the code", async () => {
  const o = await open(b, d, "accounts.html");
  try {
    const lane = o.page.locator(".lane", { has: o.page.locator(".mark .nm", { hasText: "Acme spare" }) });
    await expect(lane).toHaveCount(1, { timeout: 10_000 });
    await expect(lane).toContainText(/sign.in/i);
    await lane.locator(".mark .nm").click();
    const l = await lens(o.page);
    await l.locator('[data-act="signin"]').click();
    await expect.poll(() => d.seen.filter((s) => s.method === "POST" && s.path === "/api/auth/claude-splice/login")
      .map((s) => JSON.stringify(s.body)).join(" "), { timeout: 10_000 }).toContain("account-4");
    await expect(o.page.locator(".lens:not([hidden]) input:visible, .glass:not([hidden]) input:visible").first()).toBeVisible({ timeout: 10_000 });
    expect(o.errors).toEqual([]);
  } finally {
    await o.close();
  }
}, 60_000);

test("the composer on Accounts asks the manager", async () => {
  const o = await open(b, d, "accounts.html");
  try {
    await o.page.locator("#compWrap textarea").click();
    await o.page.keyboard.type("Which account comes back first?");
    await o.page.keyboard.press("Enter");
    await expect.poll(() => d.seen.filter((s) => s.method === "POST" && s.path.startsWith("/api/"))
      .map((s) => JSON.stringify(s.body)).join(" "), { timeout: 10_000 }).toContain("Which account comes back first?");
    expect(d.seen.some((s) => s.method === "POST" && s.path === "/api/sessions/m-1/say")).toBe(true);
  } finally {
    await o.close();
  }
}, 60_000);
