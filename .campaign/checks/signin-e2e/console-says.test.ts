// signin-e2e console-says: the console says plainly what a sign-in came to. A sign-in that lands says "Signed in as
// <label>" where Sign in was pressed, without a reload (Nov 19, 2026: a new login landed and nothing on the page said
// so). Closing the provider's page before the sign-in finishes is said there too, and the page can be opened again.
// The real console pages against a stub daemon in the routes' own shapes (LoginRoutes.kt), as
// tools/console/models-sign-in-opens.test.ts drives them; the provider's page is one the stub serves.

import { afterAll, beforeAll, expect as bunExpect, setDefaultTimeout, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { PAGE_CLOSED, ROOT } from "./lib/report.ts";

setDefaultTimeout(120_000);

const consoleDir = join(ROOT, "app/src/main/resources/console");
const HEAD = "alpha";
const LABEL = "made-up-alpha";
// where the stub's sign-in stands: waiting on the provider's page, or landed as LABEL
let landed = false;

const server = Bun.serve({
  port: 0,
  fetch(req) {
    const url = new URL(req.url);
    const path = url.pathname;
    const page = `${url.origin}/provider/authorize?state=made-up`;
    const waiting = { id: "login-1", head: HEAD, state: "waiting", label: null, user_code: null, verification_uri: null, browser_url: page, paste_code: false, failure_kind: null, failure_reason: null };
    const status = landed ? { ...waiting, state: "signed_in", label: LABEL, browser_url: null } : waiting;
    if (path === "/provider/authorize") return new Response("<!doctype html><title>Provider sign-in</title><h1>Sign in to Alpha</h1>", { headers: { "content-type": "text/html" } });
    if (req.method === "POST" && path === `/api/auth/${HEAD}/login`) return Response.json(waiting);
    if (path === `/api/auth/${HEAD}/login/login-1`) return Response.json(status);
    if (path === "/api/heads") return Response.json({ heads: [{ key: HEAD, label: `claude-${HEAD}`, running: true, healthy: true, dialect: "openai-responses", authKind: "chatgpt-oauth" }] });
    if (path === "/api/topology") return Response.json({ topology: { heads: { [HEAD]: { provider: "alpha-co" } }, providers: { "alpha-co": { base_url: "https://api.alpha.example", auth: { kind: "chatgpt-oauth" } } } } });
    if (path === "/api/models") return Response.json({ heads: [{ head: HEAD, pinned_model: null, models: [{ id: "alpha-one", label: "Alpha One", resolved: true, slot: "opus" }] }] });
    if (path === "/api/doctor") return Response.json({ checks: [{ id: `auth/${HEAD}`, status: landed ? "ok" : "fail", detail: landed ? "signed in" : "signed out" }] });
    if (path === "/api/sessions") return Response.json({ sessions: [] });
    if (path === "/api/projects") return Response.json({ projects: [], folders: [] });
    if (path === "/api/models/local") return Response.json({ providers: [] });
    if (path === "/api/claude-head") return Response.json({ mode: "wrapped", shim_path: "/home/u/.local/bin/claude", resolves_to: "/home/u/.local/bin/claude" });
    if (path === "/api/add/profiles") return Response.json({ profiles: [] });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return path !== "/" && existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox", "--disable-background-networking"] });
}, 30_000);
afterAll(async () => {
  await browser?.close();
  server.stop(true);
});

/** Opens Models, presses Sign in on the command's card, and waits for the provider's page in the tab it opened. Counts
 *  the console page's own loads from then on. */
async function pressSignIn() {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  await page.goto(`http://127.0.0.1:${server.port}/models.html`);
  await page.locator(`article.card.h[data-open="${HEAD}"]`).click({ timeout: 15_000 });
  const opened = context.waitForEvent("page", { timeout: 15_000 });
  await page.locator(".pane header").getByRole("button", { name: "Sign in", exact: true }).click({ timeout: 15_000 });
  const tab = await opened;
  await expect(tab).toHaveURL(/\/provider\/authorize\?state=made-up$/, { timeout: 15_000 });
  const loads = { n: 0 };
  page.on("framenavigated", (f) => {
    if (f === page.mainFrame()) loads.n++;
  });
  return { context, page, tab, loads };
}

test("Models: a sign-in that lands says so by the login's label, without a reload", async () => {
  landed = false;
  const { context, page, loads } = await pressSignIn();
  try {
    landed = true;
    await expect(page.locator(".pane header")).toContainText(`Signed in as ${LABEL}`, { timeout: 20_000 });
    bunExpect(loads.n).toBe(0);
  } finally {
    landed = false;
    await context.close();
  }
});

test("Models: closing the provider's page before the sign-in finishes is said where Sign in was pressed", async () => {
  landed = false;
  const { context, page, tab } = await pressSignIn();
  try {
    await tab.close();
    await expect(page.locator(".pane header")).toContainText(PAGE_CLOSED, { timeout: 20_000 });
    await expect(page.locator(".pane header").getByRole("button", { name: /open/i }).first()).toBeVisible({ timeout: 10_000 });
  } finally {
    await context.close();
  }
});
