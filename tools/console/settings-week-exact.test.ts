// Settings' week counts are the daemon's own counts: a busy command's week holds more than the 2,000 rows one read can carry,
// and the figure must be the real count, never the size of the read. Through the real page against a stub daemon that
// answers every ask of one request with a count above 2,000 and sends back no more than the newest row.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const COUNT = 4321;
const asked: string[] = [];
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const url = new URL(req.url);
    const path = url.pathname;
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "alpha", command: "alpha" }] });
    if (path === "/api/config") return Response.json({ layers: {}, effective: { streamIdleMs: 60000 } });
    if (path === "/api/perf/turns") {
      const asks = url.searchParams.getAll("ask");
      asked.push(...asks);
      const answer = (query: string) => {
        const waited = new URLSearchParams(query).get("test") === "waited";
        return {
          key: "alpha", count: COUNT, returned: 1, truncated: true,
          rows: [{ ts: Date.now() - 1000, outcome: "ok", turn: "newest" }],
          ...(waited ? { longest_silence_ms: 90000, longest: { ts: Date.now() - 2000, outcome: "ok", turn: "longest" } } : {}),
        };
      };
      return Response.json({ heads: [{ key: "alpha", ...(asks.length ? { asks: asks.map(answer) } : { count: 0, rows: [] }) }] });
    }
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
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

test("a busy command's week shows the daemon's real count above 2,000, and its longest silence", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/settings.html`);
    await page.getByText("Stalled answers").first().click();
    const waited = page.locator(".fig3", { hasText: "Waited out" });
    await expect(waited.locator(".lead .big")).toHaveText("4,321", { timeout: 15_000 });
    await expect(waited).toContainText("1 min 30 s");
    expect(asked.some((q) => q.includes("test=waited") && q.includes("silence_ms=60000"))).toBe(true);
    expect(asked.every((q) => new URLSearchParams(q).get("n") === "1")).toBe(true);
  } finally {
    await page.close();
  }
}, 40_000);
