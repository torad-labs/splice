// Every page marks its own nav item whatever address it was opened at (splice-lead, Oct 12, from builder2's "No project"
// retake: Requests opened with ?project=- marked Accounts). Through the real pages against a stub daemon that answers
// every read empty: a query, and a page served at a bare address, still mark the page's own item and only that one.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path.startsWith("/api/")) return Response.json({});
    if (path === "/bare/") return new Response(Bun.file(join(consoleDir, "requests.html"))); // a page at no file name
    const name = path.replace(/^\/bare\//, "/").slice(1);
    const file = join(consoleDir, name);
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

for (const [address, item] of [
  ["requests.html?project=-", "Requests"],
  ["requests.html?project=splice&why=gaveup", "Requests"],
  ["sessions.html?project=-#0c3b07d2", "Sessions"],
  ["usage.html?range=7d", "Usage"],
  ["bare/?project=-", "Requests"],
] as const) {
  test(`${address} marks ${item} and nothing else`, async () => {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    try {
      await page.goto(`http://127.0.0.1:${server.port}/${address}`);
      await page.locator(".nav a").first().waitFor({ timeout: 15_000 });
      expect(await page.locator('.nav a[aria-current="page"]').allTextContents()).toEqual([item]);
    } finally {
      await page.close();
    }
  }, 30_000);
}
