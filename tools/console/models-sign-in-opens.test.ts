// A command's Sign in opens the provider's page by itself (Nov 19, 2026: after removing a stale login, the sign-in that
// followed opened no page, and a person waiting for one saw nothing happen). The press opens the tab, and the tab goes
// to the page splice names for the sign-in. A sign-in that ends before splice names a page closes the tab it opened.
// Through the real page against a stub daemon in the routes' own shapes (LoginRoutes.kt); the provider's page is one
// the stub serves, so the tab has somewhere real to land.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HEAD = "alpha";
// what the next sign-in does: names its page, or fails before it has one
let failsAtOnce = false;

const server = Bun.serve({
  port: 0,
  fetch(req) {
    const url = new URL(req.url);
    const path = url.pathname;
    const page = `${url.origin}/provider/authorize?state=made-up`;
    const waiting = { id: "login-1", state: "waiting", label: null, user_code: null, verification_uri: null, browser_url: page, paste_code: false, failure_kind: null, failure_reason: null };
    const failed = { ...waiting, state: "failed", browser_url: null, failure_kind: "provider_error", failure_reason: "the provider refused the sign-in" };
    if (path === "/provider/authorize") return new Response("<!doctype html><title>Provider sign-in</title><h1>Sign in to Alpha</h1>", { headers: { "content-type": "text/html" } });
    if (req.method === "POST" && path === `/api/auth/${HEAD}/login`) return Response.json(failsAtOnce ? failed : waiting);
    if (path === `/api/auth/${HEAD}/login/login-1`) return Response.json(failsAtOnce ? failed : waiting);
    if (path === "/api/heads") return Response.json({ heads: [{ key: HEAD, label: `claude-${HEAD}`, running: true, healthy: true, dialect: "openai-responses", authKind: "chatgpt-oauth" }] });
    if (path === "/api/topology") return Response.json({ topology: { heads: { [HEAD]: { provider: "alpha-co" } }, providers: { "alpha-co": { base_url: "https://api.alpha.example", auth: { kind: "chatgpt-oauth" } } } } });
    if (path === "/api/models") return Response.json({ heads: [{ head: HEAD, pinned_model: null, models: [{ id: "alpha-one", label: "Alpha One", resolved: true, slot: "opus" }] }] });
    if (path === "/api/doctor") return Response.json({ checks: [{ id: `auth/${HEAD}`, status: "fail", detail: "signed out" }] });
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
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

async function pressSignIn() {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  await page.goto(`http://127.0.0.1:${server.port}/models.html`);
  await page.locator(`article.card.h[data-open="${HEAD}"]`).click({ timeout: 15_000 });
  const opened = context.waitForEvent("page", { timeout: 10_000 });
  await page.locator(".pane header").getByRole("button", { name: "Sign in", exact: true }).click({ timeout: 15_000 });
  return { context, page, tab: await opened };
}

test("Models: Sign in opens the provider's page by itself, with no second press", async () => {
  failsAtOnce = false;
  const { context, tab } = await pressSignIn();
  try {
    await expect(tab).toHaveURL(/\/provider\/authorize\?state=made-up$/, { timeout: 10_000 });
    await expect(tab.getByRole("heading", { name: "Sign in to Alpha" })).toBeVisible();
  } finally {
    await context.close();
  }
}, 60_000);

test("Models: a sign-in that fails before it has a page closes the tab it opened, and says why", async () => {
  failsAtOnce = true;
  const { context, page, tab } = await pressSignIn();
  try {
    await expect.poll(() => tab.isClosed(), { timeout: 10_000 }).toBe(true);
    await expect(page.locator(".pane header .signwhy")).toHaveText("the provider refused the sign-in", { timeout: 10_000 });
  } finally {
    await context.close();
  }
}, 60_000);
