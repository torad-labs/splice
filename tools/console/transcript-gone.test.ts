// A session whose transcript is gone is never a door to nothing (Marlin, Oct 12, from her Usage walk: "Open session"
// led to a Sessions page reading "No sessions", with no reason). Through the real pages against a stub daemon:
// Requests draws no Open session for a session Sessions no longer lists, Sessions reached for one it cannot find says
// "No transcript" where the log would be, and a log that cannot be read says "Not read" with the daemon's words kept
// off the page (fin's leak fix).
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const DAEMON_WORDS = "java.nio.file.AccessDeniedException: /home/someone/.claude/projects/x.jsonl";
const session = (id: string, name: string) => ({
  session_id: id, name, head: "alpha", route: "head", availability: "live", status: "idle", cwd: "/work/desk",
  last: { role: "assistant", tool: null, text: `${name} said its last line.`, ts: 1 },
  updated_at: new Date(now - 60_000).toISOString(),
});
const turn = (id: string, sid: string) => ({
  turn: id, turn_id: id, ts: now - 30_000, outcome: "ok", model: "m", session: sid, total: 2_000, attempts: 1, retries: 0,
});
const answers: Record<string, unknown> = {
  "/api/sessions": { reading: true, sessions: [session("live0001-0000", "builder"), session("unread01-0000", "reviewer")] },
  "/api/heads": { heads: [{ key: "alpha", label: "claude-alpha" }] },
  "/api/heads/retired": { retired: [] },
  "/api/models": { heads: [] },
};
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/perf/turns") {
      const rows = [turn("t-live", "live0001"), turn("t-gone", "0296ce46")];
      return Response.json({ heads: [{ key: "alpha", label: "alpha", count: 2, returned: 2, truncated: false, rows,
        usage: { models: [], accounts: [], sessions: [] } }] });
    }
    if (path === "/api/sessions/live0001-0000/transcript") return Response.json({ error: "gone" }, { status: 404 });
    if (path === "/api/sessions/unread01-0000/transcript") return Response.json({ error: DAEMON_WORDS }, { status: 500 });
    if (path in answers) return Response.json(answers[path]);
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

test("Requests opens a listed session, and draws no door for one Sessions no longer lists", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/requests.html`);
    await page.locator('article[data-open="alpha:t-live"]').click({ timeout: 15_000 });
    await expect(page.locator(".pane a", { hasText: "Open session" })).toBeVisible();
    await page.locator('article[data-open="alpha:t-gone"]').click();
    await expect(page.locator(".pane")).toBeVisible();
    await expect(page.locator(".pane a", { hasText: "Open session" })).toHaveCount(0);
  } finally {
    await page.close();
  }
}, 30_000);

test("Sessions reached for a session it cannot find says No transcript where the log would be", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/sessions.html#0296ce46-dead`);
    await expect(page.locator(".pane.missing .log .empty")).toHaveText("No transcript", { timeout: 15_000 });
    await expect(page.locator(".card.s", { hasText: "builder" })).toBeVisible(); // the list he has is still there
    await page.locator(".pane.missing [data-act=close]").click();
    await expect(page.locator(".pane.missing")).toHaveCount(0);
  } finally {
    await page.close();
  }
}, 30_000);

test("a log that is gone says No transcript, one that cannot be read says Not read, and the daemon's words stay off the page", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const warned: string[] = [];
  page.on("console", (m) => { if (m.type() === "warning") warned.push(m.text()); });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/sessions.html#live0001-0000`);
    await expect(page.locator(".pane .log .empty")).toHaveText("No transcript", { timeout: 15_000 });
    await page.goto(`http://127.0.0.1:${server.port}/sessions.html#unread01-0000`);
    await expect(page.locator(".pane .log .empty")).toHaveText("Not read", { timeout: 15_000 });
    await expect(page.locator("body")).not.toContainText("AccessDenied");
    await expect.poll(() => warned.some((w) => w.includes("AccessDeniedException"))).toBe(true);
  } finally {
    await page.close();
  }
}, 30_000);
