// An open request says how many requests its command was already running when splice let it in, so a slow or queued
// request can be read against the load it entered: the daemon's in-flight count at admission, drawn beside the
// request's attempts and waits. A request the daemon sends no count for draws none. Through the real Requests page
// against a stub daemon.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const turn = (id: string, extra: Record<string, unknown>) => ({
  turn: id, turn_id: id, ts: now - 30_000, model: "m", session: "0c3b07d2", retries: 0, outcome: "ok", attempts: 1,
  in_tokens: 12_000, cached_tokens: 9_000, out_tokens: 400, total: 3_000, ...extra,
});
const rows = [
  turn("t-busy", { inflight: 21, admit_held_ms: 1_200 }),
  turn("t-unknown", { ts: now - 60_000 }),
];
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/perf/turns") {
      return Response.json({ heads: [{ key: "alpha", label: "alpha", count: rows.length, returned: rows.length, truncated: false, rows,
        usage: { models: [], accounts: [], sessions: [] } }] });
    }
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-alpha" }] });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({
    executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined,
    args: ["--no-sandbox", "--disable-background-networking"],
  });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

for (const width of [1440, 3822]) {
  test(`at ${width} px an open request shows the 21 requests its command was already running when it was let in`, async () => {
    const page = await browser.newPage({ viewport: { width, height: 1200 } });
    try {
      await page.goto(`http://127.0.0.1:${server.port}/requests.html`);
      await page.locator(`article[data-open="alpha:t-busy"] .name`).click({ timeout: 15_000 });
      const said = page.locator(".pane").getByText(/in flight/i);
      await expect(said).toBeVisible({ timeout: 5_000 });
      await expect(page.locator(".pane .pd-stat", { hasText: /in flight/i })).toContainText("21");
    } finally {
      await page.close();
    }
  }, 30_000);
}

test("a request the daemon sends no in-flight count for says nothing about it", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1200 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/requests.html`);
    await page.locator(`article[data-open="alpha:t-unknown"] .name`).click({ timeout: 15_000 });
    await expect(page.locator(".pane .figs")).toBeVisible({ timeout: 5_000 });
    await expect(page.locator(".pane")).not.toContainText(/in flight/i);
  } finally {
    await page.close();
  }
}, 30_000);
