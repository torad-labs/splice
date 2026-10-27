// Teams and Compare read accounts from what the daemon publishes (splice-lead, Oct 12, from splice-builder's /api/auth audit).
// /api/auth answers one entry per head, keyed by its key, with a pooled head's logins under account_pool (AuthStatusRoutes,
// AccountPoolJson); it never names which login is next, which GET /api/auth/{head}/order publishes as next_target. Teams'
// account picker offers each head's own pool, and Compare sends as the next login, or leaves a pool with none off its picker.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const login = (label: string) => ({ label, primary: false, selected: false, available: true, credential_present: true, plan: "max" });
const auth = {
  claude: { kind: "oauth", login: "automated", present: true, account_pool: { selected_label: "work", pinned_label: "work", accounts: [login("work"), login("home")] } },
  muse: { kind: "oauth", login: "automated", present: true, account_pool: { selected_label: "a", pinned_label: "a", accounts: [login("a"), login("b")] } },
  codex: { kind: "api-key", login: "manual", present: true },
};
const order: Record<string, string | null> = { claude: "home", muse: null };
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/auth") return Response.json(auth);
    const ordered = path.match(/^\/api\/auth\/([^/]+)\/order$/);
    if (ordered) return Response.json({ head: ordered[1], order: [], effective_order: [], next_target: order[ordered[1] ?? ""] ?? null, following_target: null });
    if (path === "/api/heads") return Response.json({ heads: ["claude", "muse", "codex"].map((key) => ({ key, label: key })) });
    if (path === "/api/models") return Response.json({ heads: ["claude", "muse", "codex"].map((head) => ({ head, provider: head === "codex" ? "codex" : "anthropic", models: [{ id: `${head}-m`, label: `${head} model` }] })) });
    if (path === "/api/accounts") return Response.json({ accounts: [{ label: "home", display_name: "Home login", heads: ["claude"] }] });
    if (path === "/api/teams") return Response.json({ teams: [] });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox"] });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

test("Teams offers a pooled command's own logins, and no account pick on a command with one login", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/teams.html`);
    await page.locator('[data-act="new"]').first().click({ timeout: 15_000 });
    const slot = page.locator(".slot-row").first();
    await slot.locator('[data-what="head"]').click();
    await page.locator('.pick-menu button[data-v="claude"]').click();
    await slot.locator('button.pickbtn[data-what="account"]').click();
    expect(await page.locator('.pick-menu[data-what="account"] button').allInnerTexts()).toEqual(["work", "home"]);
    await slot.locator('button.pickbtn[data-what="head"]').click();
    await page.locator('.pick-menu button[data-v="codex"]').click();
    expect(await slot.locator('button.pickbtn[data-what="account"]').count()).toBe(0);
  } finally {
    await page.close();
  }
}, 30_000);

test("Compare names the login the order route says is next, and leaves off a pool with no next login", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/compare.html`);
    await page.locator("[data-act=add]").click({ timeout: 15_000 });
    // the picker is a board of command cards now, a model a row on its command's card, the login on the card's head
    await page.locator("button.pg-mrow").first().waitFor({ timeout: 15_000 });
    const keys = await page.locator("button.pg-mrow").evaluateAll((rows) => rows.map((r) => r.getAttribute("data-key")));
    expect([...new Set(keys)].sort()).toEqual(["claude", "codex"]);
    expect(await page.locator(".pg-cmd .pg-ch .pg-an").allInnerTexts()).toContainEqual(expect.stringContaining("Home login"));
  } finally {
    await page.close();
  }
}, 30_000);
