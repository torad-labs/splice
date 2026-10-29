// Settings, Plan limits: a login's card reads the way Accounts reads it (shared-login-windows, finish pass). A login at its
// limit says when it is back once in its red line AND its window keeps its ring with "Resets <time>"; a login splice has
// never read stands as a card that says "Not read" beside an act that reads again, which asks the daemon for the accounts
// once more. Through the real page against a stub daemon that answers what Settings reads.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Math.floor(Date.now() / 1000);
const held = {
  display_name: "Held login", ref: "r1", heads: ["alpha"], provider: "anthropic", credential_present: true, token_renews: true,
  plan: "max", observed_at_epoch_seconds: now - 60,
  five_hour_used_percent: 30, five_hour_reset_epoch_seconds: now + 3600, five_hour_window_seconds: 18_000,
  seven_day_used_percent: 100, seven_day_reset_epoch_seconds: now + 86_400, seven_day_window_seconds: 604_800,
};
const unread = { display_name: "Fresh login", ref: "r2", heads: ["alpha"], provider: "anthropic", credential_present: true, token_renews: true };
let asked = 0;
const answers: Record<string, unknown> = {
  "/api/heads": { heads: [{ key: "alpha", label: "alpha" }] },
  "/api/models": { heads: [] },
  "/api/config": { layers: {}, effective: {} },
  "/api/upgrade": {},
  "/api/history": {},
};
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/accounts") { asked++; return Response.json({ accounts: [held, unread] }); }
    if (path in answers) return Response.json(answers[path]);
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "settings.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
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

test("a login at its limit keeps its reset on the window, and a login never read says so beside an act that reads again", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/settings.html#plan`);
    const card = page.locator(".plog", { hasText: "Held login" });
    await expect(card.locator(".lw-held")).toContainText("Limit reached until");
    // the held window still says when it refills, on its own row
    await expect(card.locator(".lw-wins .when").nth(1)).toContainText("Resets");
    const fresh = page.locator(".plog", { hasText: "Fresh login" });
    await expect(fresh).toContainText("Not read");
    const before = asked;
    await fresh.getByRole("button", { name: "Read again" }).click();
    await expect.poll(() => asked).toBeGreaterThan(before);
  } finally {
    await page.close();
  }
}, 30_000);

test("three login cards stand in a row where the pane is as wide as his 3822 screen", async () => {
  const page = await browser.newPage({ viewport: { width: 3822, height: 1860 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/settings.html#plan`);
    await page.locator(".plog:not(.ghosted)").first().waitFor();
    const tops = await page.locator(".plogs > .plog:not(.ghosted)").evaluateAll((els) => els.map((e) => Math.round(e.getBoundingClientRect().top)));
    // both logins on one row, and the row's cards are never wider than a third of the list
    expect(new Set(tops).size).toBe(1);
    const w = await page.locator(".plogs > .plog:not(.ghosted)").first().evaluate((e) => e.getBoundingClientRect().width / (e.parentElement as HTMLElement).getBoundingClientRect().width);
    expect(w).toBeLessThan(0.36);
  } finally {
    await page.close();
  }
}, 30_000);
