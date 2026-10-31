// "Waited in line" is a request the gate HELD because its command's limit was full (Marlin, Oct 12). A pass through an
// open gate records a wait of a millisecond or so, and on his day 1,151 such passes read as waiting under a peak of 33
// and a limit of 100. Through the real Requests page: the door Settings opens asks the daemon for held requests only,
// and a row the daemon sends that went straight in is not kept, however its wait reads.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const row = (turn: string, agoS: number, over: Record<string, unknown>) => ({
  turn, turn_id: turn, ts: now - agoS * 1000, outcome: "ok", model: "m", session: turn, total: 2_000, attempts: 1, retries: 0, ...over,
});
// one the gate held for 900 ms behind a full limit, and one that passed an open gate in 1 ms
const ROWS = [row("t-held", 20, { admit_wait_ms: 901, admit_held_ms: 900 }), row("t-pass", 40, { admit_wait_ms: 1 })];
const asked: URLSearchParams[] = [];
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "alpha" }] });
    if (path === "/api/perf/turns") {
      asked.push(url.searchParams);
      return Response.json({ heads: [{ key: "alpha", label: "alpha", count: ROWS.length, returned: ROWS.length, truncated: false, rows: ROWS, usage: { models: [], accounts: [], sessions: [] } }] });
    }
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

test("Waited in line asks for the requests the gate held, and keeps only those", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/requests.html?why=queued`);
    await page.locator('article[data-open="alpha:t-held"]').waitFor({ timeout: 15_000 });
    expect(asked.some((q) => q.get("queued_ms") === "0")).toBe(true);
    expect(await page.locator('article[data-open="alpha:t-pass"]').count()).toBe(0);
  } finally {
    await page.close();
  }
}, 30_000);
