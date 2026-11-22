// signin-e2e console-says on Night Deck: verifies that when a sign-in lands, the console glass
// displays "Signed in as <label>" without reloading the page, and when the provider's tab is closed
// early, the glass displays PAGE_CLOSED and offers a button to reopen the sign-in page.
import { afterAll, beforeAll, expect as bunExpect, setDefaultTimeout, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";

setDefaultTimeout(60_000);

const ROOT = join(import.meta.dir, "../..");
const CONSOLE_DIR = join(ROOT, "app/src/main/resources/console");
const PAGE_CLOSED = "The sign-in page was closed before the sign-in finished.";
const HEAD = "alpha";
const LABEL = "made-up-alpha";
let landed = false;

const server = Bun.serve({
  port: 0,
  fetch(req) {
    const url = new URL(req.url);
    const path = url.pathname;
    const authPage = `${url.origin}/provider/authorize?state=made-up`;
    const waiting = {
      id: "login-1",
      head: HEAD,
      state: "waiting",
      label: null,
      user_code: null,
      verification_uri: null,
      browser_url: authPage,
      paste_code: false,
      failure_kind: null,
      failure_reason: null,
    };
    const status = landed ? { ...waiting, state: "signed_in", label: LABEL, browser_url: null } : waiting;
    if (path === "/provider/authorize") {
      return new Response("<!doctype html><title>Provider</title><h1>Sign in to Alpha</h1>", {
        headers: { "content-type": "text/html" },
      });
    }
    if (req.method === "POST" && path === `/api/auth/${HEAD}/login`) return Response.json(waiting);
    if (path === `/api/auth/${HEAD}/login/login-1`) return Response.json(status);
    if (path === "/api/heads") {
      return Response.json({
        heads: [{ key: HEAD, label: `claude-${HEAD}`, running: true, healthy: true, dialect: "openai-responses", authKind: "chatgpt-oauth" }],
      });
    }
    if (path === "/api/topology") {
      return Response.json({
        topology: {
          heads: { [HEAD]: { provider: "alpha-co", port: 3101 } },
          providers: { "alpha-co": { dialect: "openai-responses", base_url: "https://api.alpha.example", auth: { kind: "chatgpt-oauth" } } },
        },
      });
    }
    if (path === "/api/models") {
      return Response.json({
        heads: [{ head: HEAD, pinned_model: "alpha-one", models: [{ id: "alpha-one", label: "Alpha One", resolved: true, slot: "opus" }] }],
      });
    }
    if (path === "/api/doctor") {
      return Response.json({
        checks: [{ id: `auth/${HEAD}`, status: landed ? "ok" : "fail", detail: landed ? "signed in" : "signed out" }],
      });
    }
    if (path === "/api/accounts") return Response.json({ accounts: [] });
    if (path === "/api/sessions") return Response.json({ sessions: [] });
    if (path === "/api/projects") return Response.json({ projects: [], folders: [] });
    if (path === "/api/economics") return Response.json({ heads: [] });
    if (path === "/api/keys") return Response.json({ keys: [] });
    if (path === "/api/modes") return Response.json({ modes: [] });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(CONSOLE_DIR, path.slice(1));
    return path !== "/" && existsSync(file) && file.startsWith(CONSOLE_DIR)
      ? new Response(Bun.file(file))
      : new Response("no", { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({
    executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined,
    args: ["--no-sandbox", "--disable-background-networking"],
  });
}, 30_000);
afterAll(async () => {
  await browser?.close();
  server.stop(true);
});

async function pressSignIn() {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await context.addInitScript(() => localStorage.setItem("splice-console-access-key", "test-key"));
  const page = await context.newPage();
  await page.goto(`http://127.0.0.1:${server.port}/providers.html`, { waitUntil: "networkidle" });
  const card = page.locator('[data-key]').first();
  await card.waitFor({ state: "visible", timeout: 15_000 });
  await card.click();
  const opened = context.waitForEvent("page", { timeout: 15_000 });
  const glass = page.locator("#lens, .lens, [data-lens]").first();
  await glass.getByRole("button", { name: "Sign in", exact: true }).click({ timeout: 15_000 });
  const tab = await opened;
  await expect(tab).toHaveURL(/\/provider\/authorize\?state=made-up$/, { timeout: 15_000 });
  const loads = { n: 0 };
  page.on("framenavigated", (f) => {
    if (f === page.mainFrame()) loads.n++;
  });
  return { context, page, glass, tab, loads };
}

test("Providers glass: a sign-in that lands says Signed in as <label> without a reload", async () => {
  landed = false;
  const { context, glass, loads } = await pressSignIn();
  try {
    landed = true;
    await expect(glass).toContainText(`Signed in as ${LABEL}`, { timeout: 15_000 });
    bunExpect(loads.n).toBe(0);
  } finally {
    landed = false;
    await context.close();
  }
});

test("Providers glass: closing the provider tab early says PAGE_CLOSED and offers reopen", async () => {
  landed = false;
  const { context, glass, tab } = await pressSignIn();
  try {
    await tab.close();
    await expect(glass).toContainText(PAGE_CLOSED, { timeout: 15_000 });
    await expect(glass.getByRole("button", { name: /open/i }).first()).toBeVisible({ timeout: 10_000 });
  } finally {
    await context.close();
  }
});
