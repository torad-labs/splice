// A Compare pane whose command is signed out signs it in where it failed, and follows the sign-in splice holds (Marcos,
// Nov 11, 2026: "TRhe whol state machine system for login is broken"). The pane ran its own loop over a sign-in: it read
// "Not completed" however the sign-in had failed, and went on waiting for five minutes on one a restarted splice no
// longer held. It runs the steps every page shares now (SIGNIN, api.js), and a sign-in whose pane is closed ends on
// splice too. Through the real page against a stub daemon in the routes' own shapes (LoginRoutes.kt).
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HEAD = "codex";
// The sign-ins the stub daemon holds, as splice holds them: open until ended, and ended only by a DELETE or by splice.
type Held = { id: string; open: boolean; kind: string | null; reason: string | null };
const held = new Map<string, Held>();
// the daemon restarted, and knows no sign-in it held before
let restarted = false;
const view = (h: Held) => ({
  id: h.id, state: h.open ? "waiting" : "failed", label: null,
  user_code: h.open ? "WXYZ-9999" : null, verification_uri: h.open ? "https://signin.example.invalid/device" : null, browser_url: null,
  failure_kind: h.open ? null : h.kind, failure_reason: h.open ? null : h.reason,
});
const openOnSplice = () => [...held.values()].filter((h) => h.open).length;

const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (req.method === "POST" && path === `/api/auth/${HEAD}/login`) {
      const h: Held = { id: crypto.randomUUID(), open: true, kind: null, reason: null };
      held.set(h.id, h);
      return Response.json(view(h));
    }
    const id = path.match(new RegExp(`^/api/auth/${HEAD}/login/([^/]+)$`))?.[1];
    if (id) {
      const one = held.get(id);
      if (!one || restarted) return Response.json({ error: "no such login" }, { status: 404 });
      if (req.method === "DELETE" && one.open) Object.assign(one, { open: false, kind: "cancelled", reason: "the sign-in was cancelled" });
      return Response.json(view(one));
    }
    if (path === "/api/heads") return Response.json({ heads: [{ key: HEAD, label: HEAD, command: HEAD }] });
    if (path === "/api/models") return Response.json({ heads: [{ head: HEAD, provider: HEAD, models: [{ id: "gpt-6.1-sol", label: "GPT 6.1 Sol" }] }] });
    if (path === "/api/auth") return Response.json({ [HEAD]: { present: true, display_name: "primary" } });
    // the command holds no sign-in: the playground route refuses the send in splice's own words (a 401 is the console's
    // own key being refused, which locks the page, so splice never answers a command's missing sign-in with one)
    if (path === "/api/playground" && req.method === "POST") {
      return Response.json({ error: "no credential for this command", outcome: "error:auth-missing", head: HEAD }, { status: 502 });
    }
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "compare.html" : path.slice(1));
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

// A pane that failed as signed out, with its sign-in started and its code beside the act.
async function signingIn(check: (page: Page) => Promise<void>) {
  held.clear(); restarted = false;
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/compare.html`);
    await page.locator("[data-act=add]").click();
    await page.locator("button.pg-mrow").first().click();
    await page.locator("#prompt").fill("say hi");
    await page.locator("button.pg-send").click();
    await page.getByRole("button", { name: "Sign in", exact: true }).click({ timeout: 20_000 });
    await expect(page.locator(".pg-code")).toHaveText("WXYZ-9999", { timeout: 10_000 });
    await check(page);
  } finally {
    await page.close();
  }
}

test("Compare: a sign-in splice no longer holds, because it restarted, says Stopped and gives Sign in back", () => signingIn(async (page) => {
  restarted = true;
  await expect(page.locator(".result", { hasText: "Stopped" })).toBeVisible({ timeout: 10_000 });
  await expect(page.locator(".pg-code")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Sign in", exact: true })).toBeVisible();
}), 60_000);

test("Compare: a sign-in whose pane is closed ends on splice too", () => signingIn(async (page) => {
  // the pane's own X, then the Remove it asks for where the pane holds something
  await page.locator("article.pgp").getByRole("button", { name: /^Remove / }).click();
  const sure = page.locator('article.pgp [data-act="drop"]');
  if (await sure.count()) await sure.click();
  await expect(page.locator("article.pgp")).toHaveCount(0);
  await expect.poll(openOnSplice, { timeout: 10_000 }).toBe(0);
}), 60_000);
