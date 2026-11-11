// Usage's own sign-in box ends a sign-in on splice when it is cancelled (Marcos, Nov 11, 2026: "it said it canceled, then
// when I tried sign in again, it said it was already in progress, so it didnt really cancelled"). A signed-out login's
// card on Usage signs it in again in place, with its own Cancel, and until that day that Cancel only forgot the sign-in
// in the page, as Accounts' did: splice held it for up to ten minutes, and a sign-in page finished later still filed the
// login. Through the real page against a stub daemon in the routes' own shapes (LoginRoutes.kt).
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HEAD = "plan";
// The sign-ins the stub daemon holds, as splice holds them: open until ended, and ended only by a DELETE.
const held = new Map<string, { id: string; open: boolean }>();
const view = (h: { id: string; open: boolean }) => ({
  id: h.id, state: h.open ? "waiting" : "failed", label: null, user_code: null, verification_uri: null,
  browser_url: h.open ? "https://claude.example.invalid/authorize" : null,
  failure_kind: h.open ? null : "cancelled", failure_reason: h.open ? null : "the sign-in was cancelled",
});
const openOnSplice = () => [...held.values()].filter((h) => h.open).length;

const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    if (req.method === "POST" && path === `/api/auth/${HEAD}/login`) {
      const h = { id: crypto.randomUUID(), open: true };
      held.set(h.id, h);
      return Response.json(view(h));
    }
    const one = held.get(path.match(new RegExp(`^/api/auth/${HEAD}/login/([^/]+)$`))?.[1] ?? "");
    if (one && req.method === "DELETE") { one.open = false; return Response.json(view(one)); }
    if (one) return Response.json(view(one));
    if (path === "/api/heads") return Response.json({ heads: [{ key: HEAD, label: HEAD }] });
    if (path === "/api/models") return Response.json({ heads: [{ head: HEAD, provider: "openai", models: [{ id: "gpt-6" }] }] });
    if (path === "/api/status") return Response.json({ registry: [{ key: HEAD, family: "openai", authKind: "chatgpt-oauth" }] });
    // one login, signed out: its card is where it signs in again
    if (path === "/api/accounts") return Response.json({ accounts: [{ provider: "openai", display_name: "Work", label: "work", account_labels: { [HEAD]: "work" }, kind: "chatgpt-oauth", heads: [HEAD], credential_present: false, refusal: "signed out" }] });
    if (path === "/api/keys") return Response.json({ keys: [] });
    if (path === "/api/sessions") return Response.json({ sessions: [] });
    if (path === "/api/topology") return Response.json({ topology: { providers: {} } });
    if (path.endsWith("/turns/live")) return Response.json({ turns: [] });
    if (path === "/api/perf/turns") return Response.json({ heads: [{ key: HEAD, label: HEAD, count: 0, returned: 0, truncated: false, rows: [], asks: [] }] });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox", "--disable-background-networking"] });
}, 60_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

test("Usage: Cancel on a login's sign-in ends it on splice, and Sign in again starts a new one", async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1200 } });
  const page = await context.newPage();
  try {
    await page.goto(`http://127.0.0.1:${server.port}/usage.html?win=7d`);
    await page.getByRole("button", { name: "Sign in", exact: true }).click({ timeout: 20_000 });
    await expect(page.getByRole("button", { name: "Open sign-in" })).toBeVisible({ timeout: 10_000 });
    bunExpect(openOnSplice()).toBe(1);
    await page.getByRole("button", { name: "Cancel" }).click();
    // the box is the card's Sign in again, and splice holds no sign-in
    await expect(page.getByRole("button", { name: "Sign in", exact: true })).toBeVisible({ timeout: 10_000 });
    await expect.poll(openOnSplice, { timeout: 10_000 }).toBe(0);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await expect(page.getByRole("button", { name: "Open sign-in" })).toBeVisible({ timeout: 10_000 });
    bunExpect(openOnSplice()).toBe(1);
  } finally {
    await context.close();
  }
}, 60_000);
