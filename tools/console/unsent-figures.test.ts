// A request splice ended before any attempt asked no provider, so its pane has no token tiles, neither "Not reported"
// nor zeros, and Attempts 0 stands alone (fin, Oct 12). A request that was sent and got no counts back still reads
// "Not reported". Through the real Requests page against a stub daemon.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const turn = (id: string, extra: Record<string, unknown>) => ({
  turn: id, turn_id: id, ts: now - 30_000, model: "m", session: "0c3b07d2", retries: 0, ...extra,
});
const rows = [
  turn("t-unsent", { outcome: "error:budget", attempts: 0, in: 0, write: 0, out: 0, total: 0 }),
  turn("t-silent", { outcome: "ok", attempts: 1, total: 2_000 }),
];
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/perf/turns") {
      return Response.json({ heads: [{ key: "alpha", label: "alpha", count: 2, returned: 2, truncated: false, rows,
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
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox"] });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

async function tiles(id: string): Promise<string[]> {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/requests.html`);
    await page.locator(`article[data-open="alpha:${id}"]`).click({ timeout: 15_000 });
    await page.locator(".pane .figs").waitFor({ timeout: 5_000 });
    return await page.locator(".pane .figs .fig").allInnerTexts();
  } finally {
    await page.close();
  }
}

test("a request that never left splice shows no token tiles, and Attempts 0 stands", async () => {
  const shown = (await tiles("t-unsent")).map((t) => t.replace(/\s+/g, " ").trim());
  expect(shown.some((t) => /^Tokens|^Cache write/.test(t))).toBe(false);
  expect(shown).toContain("Attempts 0");
  expect(shown.join(" ")).not.toContain("Not reported");
}, 30_000);

test("a request that was sent and got no counts back still reads Not reported", async () => {
  expect((await tiles("t-silent")).join(" ")).toContain("Not reported");
}, 30_000);
