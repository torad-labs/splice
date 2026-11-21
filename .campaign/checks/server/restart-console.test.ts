// restart-at-once, console-restart-press: the console's Restart splice says what a restart does now. On a daemon that
// hands over (GET /api/daemon/restart's `hands_over`), a session whose answer is arriving on a command that hands over
// (GET /api/heads' `hands_over`) is not cut, so the press restarts at once, and while it restarts the console says that
// answers in progress finish. A session on a command that stops whole is still cut, so the press arms with it first.
// A daemon that does not hand over arms with every session mid-request, as before. Through the real Settings page
// against a stub daemon.

import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser, type Page } from "@playwright/test";
import { expect as see } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { ROOT } from "./lib/harness.ts";

const consoleDir = join(ROOT, "app/src/main/resources/console");
const UPGRADE = {
  installed: "0.4.0",
  latest: null,
  latest_unavailable_reason: "not checked",
  rollback_target: null,
  rollback_unavailable_reason: null,
  checked_at_epoch_millis: null,
};
const heads = [
  { key: "alpha", label: "alpha", hands_over: true },
  { key: "beta", label: "beta", hands_over: false },
];
const sessions = {
  sessions: [
    { session_id: "114e6cb0-9c3a-4d26-b259-f2b7f24ab56c", name: "changelog-pass", status: "busy" },
    { session_id: "80e8a500-0a41-4f21-b707-3d7009e22904", name: "release-notes", status: "busy" },
  ],
};
const turn = (session: string) => ({ id: `t-${session}`, session, model: "claude-opus-5-5", compact: false, age_ms: 42_000, idle_ms: 900,
  seen_output: true, resumes: 0, retries: 0, will_resume: false, stopped: false });
let live: Record<string, Record<string, unknown>[]> = {};
let handsOver = true;
let down = false;
const restarts: string[] = [];

const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/daemon/restart") {
      if (req.method === "POST") {
        restarts.push("POST");
        down = true;
        return Response.json({ status: "draining", hands_over: handsOver }, { status: 202 });
      }
      if (down) return new Response("down", { status: 503 });
      return Response.json({ status: "idle", drain_ms: 45_000, hands_over: handsOver });
    }
    const liveFor = path.match(/^\/api\/heads\/([^/]+)\/turns\/live$/);
    if (liveFor) return Response.json({ head: liveFor[1], turns: live[liveFor[1] ?? ""] ?? [] });
    if (path === "/api/heads") return Response.json({ heads });
    if (path === "/api/sessions") return Response.json(sessions);
    if (path === "/api/upgrade") return Response.json(UPGRADE);
    if (path === "/api/upgrade/run") return Response.json({ run: null });
    if (path === "/api/models") return Response.json({ heads: [] });
    if (path === "/api/config") return Response.json({ layers: {} });
    if (path === "/api/perf/turns") return Response.json({ heads: [] });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "settings.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({
    executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined,
    args: ["--no-sandbox", "--disable-background-networking"],
  });
}, 60_000);
afterAll(async () => {
  await browser?.close();
  server.stop(true);
});

/** Settings' Version pane, open, on a daemon that [hands] over, with [running] answers arriving on each command. */
async function versionPane(hands: boolean, running: Record<string, Record<string, unknown>[]>): Promise<Page> {
  handsOver = hands;
  live = running;
  down = false;
  restarts.length = 0;
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/settings.html`);
  await page.getByText("Version").first().click();
  await see(page.locator(".vcard").first()).toBeVisible({ timeout: 15_000 });
  return page;
}

test("an answer arriving on a command that hands over does not arm the press: it restarts at once and says answers finish", async () => {
  const page = await versionPane(true, { alpha: [turn("114e6cb0-9c3a-4d26-b259-f2b7f24ab56c")] });
  try {
    const pane = page.locator(".pane");
    await pane.getByRole("button", { name: "Restart splice" }).click();
    await see.poll(() => restarts, { timeout: 10_000 }).toEqual(["POST"]);
    await see(pane.locator(".armed")).toHaveCount(0);
    await see(pane).toContainText("Answers in progress finish", { timeout: 10_000 });
  } finally {
    await page.close();
  }
}, 60_000);

test("a session on a command that stops whole still arms the press with that session alone", async () => {
  const page = await versionPane(true, {
    alpha: [turn("114e6cb0-9c3a-4d26-b259-f2b7f24ab56c")],
    beta: [turn("80e8a500-0a41-4f21-b707-3d7009e22904")],
  });
  try {
    const pane = page.locator(".pane");
    await pane.getByRole("button", { name: "Restart splice" }).click();
    const armed = pane.locator(".vcard .armed");
    await see(armed.locator(".said").first()).toHaveText("Mid-request", { timeout: 10_000 });
    await see(armed.locator("a.chip")).toHaveText(["release-notes"]);
    expect(restarts).toEqual([]);
  } finally {
    await page.close();
  }
}, 60_000);

test("a daemon that does not hand over arms with every session mid-request, as before", async () => {
  const page = await versionPane(false, { alpha: [turn("114e6cb0-9c3a-4d26-b259-f2b7f24ab56c")] });
  try {
    const pane = page.locator(".pane");
    await pane.getByRole("button", { name: "Restart splice" }).click();
    const armed = pane.locator(".vcard .armed");
    await see(armed.locator(".said").first()).toHaveText("Mid-request", { timeout: 10_000 });
    await see(armed.locator("a.chip")).toHaveText(["changelog-pass"]);
    expect(restarts).toEqual([]);
  } finally {
    await page.close();
  }
}, 60_000);
