// A Settings tile that says 4,321 opens a list of exactly those requests. The page asks the daemon with the same test the
// tile was counted by (test=waited), so its total is the daemon's count past the 2,000 a read carries, and it lists every row the
// daemon sent, none dropped by a second, browser-side judgement. Through the real page against a stub daemon.
// Changed 2026-10-29 by the Requests list's pass (round 4): the total is read in the chip beside the list's title ("3 of
// 4,321"), the place the passed prototype draws it, and no longer in the meter's footer; the number must still be the
// tile's count.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const COUNT = 4321;
const now = Date.now();
const asked: URLSearchParams[] = [];
// the stub daemon honours test=waited: the rows it sends carry no silence field of their own, so any browser-side check
// of silence would drop them; without the test it would send a request that did not wait at all
const row = (turn: string, agoS: number) => ({ turn, turn_id: turn, ts: now - agoS * 1000, outcome: "ok", model: "gpt-6.1-sol", session: "aaaaaaaa", session_id: "aaaaaaaa-1", total: 9000, attempts: 1, retries: 0, account: "primary", cost_usd: null, cost_reason: "plan" });
const matching = [row("w1", 60), row("w2", 120), row("w3", 180)];
const decoy = [row("decoy", 30)];
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const url = new URL(req.url);
    const path = url.pathname;
    if (path === "/api/heads") return Response.json({ heads: [{ key: "codex", label: "codex", command: "codex" }] });
    if (path === "/api/config") return Response.json({ layers: {}, effective: { streamIdleMs: 60000 } });
    if (path === "/api/perf/turns") {
      asked.push(url.searchParams);
      const tested = url.searchParams.get("test") === "waited";
      const rows = tested ? matching : [...decoy, ...matching];
      const filtered = url.searchParams.get("n") !== "1";
      return Response.json({ heads: [{ key: "codex", label: "codex", count: tested ? COUNT : rows.length, returned: rows.length, truncated: tested && filtered, rows: filtered || !tested ? rows : [], usage: { models: [], accounts: [], sessions: [] } }] });
    }
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "requests.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : Response.json({}, { status: 404 });
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

test("a Waited out door lists what the daemon tested and counted, and its total is the tile's count", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/requests.html?win=7d&why=waited`);
    const card = (turn: string) => page.locator(`article[data-open="codex:${turn}"]`);
    await expect(card("w1")).toBeVisible({ timeout: 20_000 });
    for (const turn of ["w2", "w3"]) await expect(card(turn)).toBeVisible();
    await expect(card("decoy")).toHaveCount(0);
    await expect(page.locator(".rql-count")).toContainText("of 4,321");
    expect(asked.some((q) => q.get("test") === "waited" && q.get("silence_ms") === "60000")).toBe(true);
  } finally {
    await page.close();
  }
}, 40_000);
