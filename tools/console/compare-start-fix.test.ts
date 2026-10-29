// A command that is not answering shows Start on Compare's card, and Start runs the fix Models offers for that same
// command: the fix doctor lists for it where one exists, the plain start where none is listed. Through the real page
// against a stub daemon that records which write each press made.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");

let checks: unknown[] = [];
const writes: string[] = [];

const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (req.method === "POST" && path.startsWith("/api/")) { writes.push(path); return Response.json({ ok: true }); }
    if (path === "/api/heads") return Response.json({ heads: [{ key: "local", label: "local", running: false, healthy: false, runtimeNotAnswering: true }] });
    if (path === "/api/models") return Response.json({ heads: [{ head: "local", provider: "local", models: [{ id: "tiny-m", label: "Tiny model" }] }] });
    if (path === "/api/auth") return Response.json({ local: { present: true, env_var: "LOCAL_KEY" } });
    if (path === "/api/doctor") return Response.json({ checks });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "compare.html" : path.slice(1));
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

async function pressStart() {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/compare.html`);
    await page.locator("[data-act=add]").click();
    const start = page.locator('button[data-act="startcmd"]');
    await expect(start).toHaveCount(1, { timeout: 10_000 });
    await start.click();
    await page.waitForTimeout(800);
  } finally {
    await page.close();
  }
}

test("Start on a command that is not answering runs the fix doctor lists for it, not the plain start", async () => {
  checks = [{ id: "daemon/head local", status: "fail", detail: "not started", fix_id: "start-local" }];
  writes.length = 0;
  await pressStart();
  expect(writes).toEqual(["/api/doctor/fix/start-local"]);
}, 30_000);

test("Start on a command that is not answering and has no doctor fix starts it", async () => {
  checks = [];
  writes.length = 0;
  await pressStart();
  expect(writes).toEqual(["/api/heads/local/start"]);
}, 30_000);
