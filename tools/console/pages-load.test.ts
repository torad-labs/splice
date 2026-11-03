// EVERY CONSOLE PAGE LOADS ITS SCRIPTS AND RUNS THEM, so no page is dead on arrival. The pages share one global scope:
// each loads kit.js and seven more as classic scripts before its own, so a top-level name declared in two of them is a
// SyntaxError that stops the later script before its first line, and the page never draws (kit.js's textOf beside
// models.js's own, c5203799a, Nov 2 2026: the Models page died while every file passed `node --check`). The pages are
// listed from the folder, so a page added later is covered the day it lands. Through the real pages in Chromium against
// a stub daemon that answers every read empty, the way nav-current.test.ts reads them.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser } from "@playwright/test";
import { existsSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const pages = readdirSync(consoleDir).filter((name) => name.endsWith(".html")).sort();
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return path !== "/" && existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
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

test("the console folder holds pages to load", () => {
  expect(pages.length).toBeGreaterThan(0);
});

// AN ADDRESS NAMES ONLY THE PAGE'S OWN TOPICS. Settings' topic table inherits Object.prototype, so settings.html#constructor
// once passed a plain lookup and opened a pane that drew Object's native source; the gate reads the table's own keys
// (review of PR 450, round 3, and PR 453, round 1, which asked for the page to hold it in place).
test("settings.html opens no pane for an address that names none of its topics", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/settings.html#constructor`, { waitUntil: "load" });
    expect(await page.locator("body").innerText()).not.toContain("native code");
  } finally {
    await page.close();
  }
}, 30_000);

for (const name of pages) {
  test(`${name} loads every script and runs it without an error`, async () => {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    try {
      const errors: string[] = [];
      page.on("pageerror", (err) => errors.push(err.message));
      await page.goto(`http://127.0.0.1:${server.port}/${name}`, { waitUntil: "load" });
      expect(errors).toEqual([]);
    } finally {
      await page.close();
    }
  }, 30_000);
}
