// What a held Claude login offers on its card (Nov 14, 2026), through the real page against a stub daemon that answers in
// the routes' own shapes (LimitResetRoutes.kt, SessionFallbackRoute.kt): a login at its limit with a reset it can spend now
// says so, and its act opens the login's Limit resets; its sessions with no fallback model are offered the command's other
// models, and Use sets the one picked on each of them. A held login with no reset to spend still offers the fallback,
// and a login with room offers neither.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HOUR = 3_600_000;
const now = Date.now();
const sec = (ms: number) => Math.floor(ms / 1000);
const HEAD = "claude";
const login = (label: string, name: string, week: number) => ({
  label, display_name: name, ref: label.padEnd(8, "0").slice(0, 8), heads: [HEAD], provider: "anthropic",
  account_labels: { [HEAD]: label }, credential_present: true, identity_verified: true,
  can_rename: true, can_remove: true, edit_target: { kind: "pool", id: label },
  five_hour_used_percent: 40, five_hour_reset_epoch_seconds: sec(now + 2 * HOUR), five_hour_window_seconds: 18_000,
  seven_day_used_percent: week, seven_day_reset_epoch_seconds: sec(now + 50 * HOUR), seven_day_window_seconds: 604_800,
  observed_at_epoch_seconds: sec(now), pinned_heads: [],
});
// "first" can spend its weekly reset now; "third" has spent every reset it had
const resetsOf = (label: string) => ({
  label, offered: true,
  kinds: [
    { kind: "fable", name: "Fable reset", left: 0, total: 3, usable_now: false },
    { kind: "weekly", name: "weekly reset", left: label === "third" ? 0 : 1, total: 1, usable_now: label !== "third" },
  ],
});
const session = (id: string, name: string, account: string) =>
  ({ session_id: id, name, head: HEAD, account, availability: "live", status: "idle", model: "claude-fable-5-2" });
// what splice holds: the fallbacks set, and every read of a login's resets (each one asks the provider)
const fallbacks: Record<string, string> = {};
const resetReads: string[] = [];
const answers: Record<string, unknown> = {
  "/api/status": { registry: [{ key: HEAD, label: HEAD, family: "anthropic", authKind: "oauth" }] },
  "/api/accounts": { accounts: [login("first", "First", 100), login("second", "Second", 30), login("third", "Third", 100)], head_pools: {} },
  "/api/keys": { keys: [] },
  "/api/budgets": { budgets: [] },
  "/api/models": { heads: [{ head: HEAD, models: [{ id: "claude-fable-5-2", label: "Fable 5.2" }, { id: "claude-opus-5-5", label: "Opus 5.5" }] }] },
  "/api/heads": [],
  "/api/economics": { heads: [{ key: HEAD, label: HEAD, unpriced_reason: "plan", not_read_hours: [], buckets: [] }] },
  "/api/auth": {},
  "/api/sessions": { sessions: [session("s-build", "build", "first"), session("s-docs", "docs", "second"), session("s-ops", "ops", "third")] },
};
const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    if (path === `/api/auth/${HEAD}/resets`) {
      const label = url.searchParams.get("label") ?? "";
      resetReads.push(label);
      return Response.json({ head: HEAD, logins: [resetsOf(label)] });
    }
    if (path === "/api/sessions/fallbacks") return Response.json({ sessions: fallbacks });
    const fb = path.match(/^\/api\/sessions\/([^/]+)\/fallback$/);
    if (fb && req.method === "PUT") {
      fallbacks[decodeURIComponent(fb[1] ?? "")] = ((await req.json()) as { model: string }).model;
      return Response.json({ ok: true });
    }
    if (/^\/api\/auth\/[^/]+\/order$/.test(path)) return Response.json({ order: [], effective_order: ["first", "second", "third"], next_target: "second" });
    if (/^\/api\/heads\/[^/]+\/turns\/live$/.test(path)) return Response.json({ turns: [] });
    if (path in answers) return Response.json(answers[path]);
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "accounts.html" : path.slice(1));
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

const card = (page: Page, name: string) => page.locator("article.card").filter({ has: page.locator(".name", { hasText: new RegExp(`^${name}$`) }) });
async function onAccounts(check: (page: Page) => Promise<void>) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    await expect(card(page, "First")).toBeVisible({ timeout: 15_000 });
    await check(page);
  } finally {
    await page.close();
  }
}

test("a held login says a reset is available and its act opens the login's Limit resets; a login with room says nothing", () => onAccounts(async (page) => {
  const offer = card(page, "First").locator(".offer");
  await expect(offer).toContainText("A weekly reset is available", { timeout: 10_000 });
  await expect(card(page, "Second").locator(".offer")).toHaveCount(0);
  // the reads ask the provider, so only the held logins are read
  expect(resetReads).not.toContain("second");
  await offer.getByRole("button", { name: "Limit resets" }).click();
  await expect(card(page, "First").locator(".resets .rs-row")).toHaveCount(2, { timeout: 10_000 });
  // the open panel says it row by row, so the line steps aside
  await expect(card(page, "First").locator(".offer")).not.toContainText("reset is available");
}), 30_000);

test("a held login's sessions with no fallback are offered the command's other models, and Use sets the one picked", () => onAccounts(async (page) => {
  const offer = card(page, "First").locator(".offer");
  await expect(offer).toContainText("1 session on it has no fallback model", { timeout: 10_000 });
  await offer.getByRole("button", { name: /no fallback model/ }).click();
  // the model the session asks for is not offered as its own fallback
  await expect(offer.locator(".of-mrow")).toHaveCount(1);
  await offer.locator(".of-mrow").filter({ hasText: "Opus 5.5" }).getByRole("button", { name: "Use" }).click();
  await expect(offer).toContainText("1 session runs on Opus 5.5 while every login is held", { timeout: 10_000 });
  expect(fallbacks).toEqual({ "s-build": "claude-opus-5-5" });
  await expect(offer).not.toContainText("no fallback model");
}), 30_000);

test("a held login with no reset to spend draws its card and still offers the fallback", () => onAccounts(async (page) => {
  const offer = card(page, "Third").locator(".offer");
  await expect(offer).toContainText("1 session on it has no fallback model", { timeout: 10_000 });
  await expect(offer).not.toContainText("reset is available");
  await expect(offer.getByRole("button", { name: "Limit resets" })).toHaveCount(0);
}), 30_000);
