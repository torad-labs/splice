// A request row says its outcome with its lamp, never again in words on every line: a failed row carries no outcome word, the
// lamp's tooltip names it, and the chip beside the list's title is a door (Requests list, approved prototype, finish pass
// 2026-10-29). Through the real page against a stub daemon.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const rows = [
  { turn: "bad", turn_id: "bad", ts: now - 30_000, outcome: "error:rate-limited", model: "m", session: "0c3b07d2", total: 2_000, attempts: 1, retries: 0 },
  { turn: "good", turn_id: "good", ts: now - 90_000, outcome: "ok", model: "m", session: "0c3b07d2", total: 2_000, attempts: 1, retries: 0 },
];
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-alpha" }] });
    if (path === "/api/perf/turns") return Response.json({ heads: [{ key: "alpha", label: "alpha", count: rows.length, returned: rows.length, truncated: false, rows, usage: { models: [], accounts: [], sessions: [] } }] });
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

test("a failed row has no outcome word, its lamp says it, and the count chip is a door", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/requests.html`);
    const bad = page.locator('article[data-open="alpha:bad"]').last();
    await bad.waitFor({ timeout: 15_000 });
    expect(await bad.locator(".who .word").count()).toBe(0);
    expect(await bad.locator(".lamp").getAttribute("title")).toContain("Rate limited");
    const chip = page.locator(".rql-count");
    expect(await chip.evaluate((e) => e.tagName)).toBe("BUTTON");
    expect(await chip.getAttribute("title")).toBe("Show these requests");
  } finally {
    await page.close();
  }
}, 30_000);
