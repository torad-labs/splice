// A page still reading draws its own shape, never the lone word "Reading" (Oct 12: the plain reading indicator
// was to be improved). With every daemon read held open, the Night Deck pages stand in their own frame: Activity its
// figures and its list, Teams its board, Settings its parts, and none of them says Reading. (Rewritten for the Night
// Deck pages: the old Requests and Usage pages went into Activity, and the striped ghost bars, Settings' job cards and
// Teams' Read again went with the old pages' own drawing.)
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
let release = () => {};
const held = new Promise<void>((done) => { release = done; });
const server = Bun.serve({
  port: 0,
  idleTimeout: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (path.startsWith("/api/")) { await held; return Response.json({}); } // a daemon that has not answered yet
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox"] });
}, 20_000);
afterAll(async () => {
  release();
  await browser.close();
  server.stop(true);
});

async function reading(page: string, frame: string, least: number) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } }); // its own connections: held reads stay its own
  const p = await context.newPage();
  const errors: string[] = [];
  p.on("pageerror", (e) => errors.push(e.message));
  try {
    await p.goto(`http://127.0.0.1:${server.port}/${page}.html`, { waitUntil: "load" });
    await p.locator(frame).first().waitFor({ timeout: 15_000 });
    expect(await p.locator(frame).count()).toBeGreaterThanOrEqual(least);
    await p.waitForTimeout(1_000);
    expect(await p.locator("main").innerText()).not.toMatch(/\bReading\b/);
    expect(errors).toEqual([]);
  } finally {
    await context.close();
  }
}

test("activity stands in its figures and its list while it reads", () => reading("activity", "#figs, #rows", 1), 30_000);
test("teams stands in its board while it reads", () => reading("teams", "#tm", 1), 30_000);
test("settings stands in its parts while it reads", () => reading("settings", "[data-part]", 9), 30_000);
