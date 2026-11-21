// A command's Sign in opens the provider's page by itself (Nov 19, 2026: after removing a stale login, the sign-in that
// followed opened no page, and a person waiting for one saw nothing happen). The press opens the tab, and the tab goes
// to the page splice names for the sign-in. A sign-in that ends before splice names a page closes the tab it opened.
// Through the real page against a stub daemon in the routes' own shapes (LoginRoutes.kt); the provider's page is one
// the stub serves, so the tab has somewhere real to land.
// Rewritten Nov 21, 2026 for the Night Deck from models-sign-in-opens, which pressed Sign in on the old Models page: a
// signed-out command's Sign in is in its glass on Providers.
import { afterAll, beforeAll, test } from "bun:test";
import { expect, type Browser } from "@playwright/test";
import { launch, stub, type Stub } from "./deck-stub.ts";

const HEAD = "alpha";
const COMMAND = `claude-${HEAD}`;
// what the next sign-in does: names its page, or fails before it has one
let failsAtOnce = false;

let s: Stub;
let browser: Browser;
const status = () => {
  const waiting = { id: "login-1", state: "waiting", label: null, user_code: null, verification_uri: null,
    browser_url: `${s.url}/api/provider-page?state=made-up`, paste_code: false, failure_kind: null, failure_reason: null };
  return failsAtOnce ? { ...waiting, state: "failed", browser_url: null, failure_kind: "provider_error", failure_reason: "the provider refused the sign-in" } : waiting;
};
beforeAll(async () => {
  s = stub({
    "/api/heads": { heads: [{ key: HEAD, label: COMMAND, state: "running", port: 41002, dialect: "openai-responses", authKind: "chatgpt-oauth", gate: { inflight: 0 } }] },
    "/api/status": { version: "9.8.7", registry: [{ key: HEAD, label: COMMAND, family: "openai", authKind: "chatgpt-oauth" }] },
    "/api/models": { heads: [{ head: HEAD, pinned_model: null, models: [{ id: "alpha-one", label: "Alpha One" }] }] },
    "/api/topology": {
      topology: { heads: { [HEAD]: { provider: "alpha-co" } }, providers: { "alpha-co": { base_url: "https://api.alpha.example", auth: { kind: "chatgpt-oauth" } } } },
      vocab: { auth_kinds: [{ kind: "chatgpt-oauth", label: "ChatGPT sign-in" }], share: ["settings", "mcps"], quirks: [] },
    },
    "/api/doctor": { checks: [{ id: `auth/${HEAD}`, status: "fail", cause: "signed_out" }] },
    [`POST /api/auth/${HEAD}/login`]: () => status(),
    [`/api/auth/${HEAD}/login/login-1`]: () => status(),
    // the provider's own page, served from the stub so the tab has somewhere real to land
    "/api/provider-page": () => new Response("<!doctype html><title>Provider sign-in</title><h1>Sign in to Alpha</h1>", { headers: { "content-type": "text/html" } }),
  });
  browser = await launch();
}, 30_000);
afterAll(async () => {
  await browser?.close();
  s?.stop();
});

async function pressSignIn() {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  await page.goto(`${s.url}/providers.html?cmd=${COMMAND}`);
  const glass = page.locator(".lens:not([hidden])");
  await expect(glass.locator("h3")).toContainText(COMMAND, { timeout: 15_000 });
  const opened = context.waitForEvent("page", { timeout: 10_000 });
  await glass.locator('[data-item="fix"]').getByRole("button", { name: "Sign in" }).click({ timeout: 15_000 });
  return { context, glass, tab: await opened };
}

test("Providers: Sign in opens the provider's page by itself, with no second press", async () => {
  failsAtOnce = false;
  const { context, tab } = await pressSignIn();
  try {
    await expect(tab).toHaveURL(/\/api\/provider-page\?state=made-up$/, { timeout: 10_000 });
    await expect(tab.getByRole("heading", { name: "Sign in to Alpha" })).toBeVisible();
  } finally {
    await context.close();
  }
}, 60_000);

test("Providers: a sign-in that fails before it has a page closes the tab it opened, and says why", async () => {
  failsAtOnce = true;
  const { context, glass, tab } = await pressSignIn();
  try {
    await expect.poll(() => tab.isClosed(), { timeout: 10_000 }).toBe(true);
    await expect(glass.locator(".sign-why")).toHaveText("the provider refused the sign-in", { timeout: 10_000 });
  } finally {
    await context.close();
  }
}, 60_000);
