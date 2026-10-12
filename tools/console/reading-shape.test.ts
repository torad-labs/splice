// A page still reading draws its own shape, never the lone word "Reading" (Marcos, Oct 12: "we can do better than that
// reading indicator"). With every daemon read held open, Requests, Teams, Usage and Settings each draw striped bars where
// their figures will land, and Settings draws its own cards, each line's bar starting where its figure will.
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

async function reading(page: string, check?: (p: import("@playwright/test").Page) => Promise<void>) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } }); // its own connections: held reads stay its own
  const p = await context.newPage();
  try {
    await p.goto(`http://127.0.0.1:${server.port}/${page}.html`, { waitUntil: "domcontentloaded" });
    await p.locator("main .ghost").first().waitFor({ timeout: 15_000 });
    expect(await p.locator("main .ghost").count()).toBeGreaterThan(3);
    expect(await p.locator("main").innerText()).not.toMatch(/\bReading\b/);
    await check?.(p);
  } finally {
    await context.close();
  }
}

for (const page of ["requests", "teams", "usage"] as const) {
  test(`${page} draws its shape while it reads`, () => reading(page), 30_000);
}

test("settings draws its own cards while it reads, each line's bar where its figure will land", () => reading("settings", async (p) => {
  expect(await p.locator(".card.job .jname").allInnerTexts()).toContain("Silent models");
  const lines = await p.locator(".card.job").evaluateAll((cards) => cards.flatMap((c) => {
    const bar = c.querySelector(".meta .ghost"), name = c.querySelector(".jname");
    return bar && name ? [Math.round(bar.getBoundingClientRect().x - name.getBoundingClientRect().x)] : [];
  }));
  expect(lines.length).toBeGreaterThan(5);
  expect(lines.every((d) => d === 0)).toBe(true);
}), 30_000);

test("teams keeps Read again off while its read is out", () => reading("teams", async (p) => {
  expect(await p.locator('.head [data-act="read"]').isDisabled()).toBe(true);
}), 30_000);

test("usage draws Breakdown and Commands in the frames they land in, one card each", () => reading("usage", async (p) => {
  expect(await p.locator(".breakdown > .card .ghost").count()).toBeGreaterThan(3);
  expect(await p.locator(".lower .card.gcard").count()).toBe(2);
}), 30_000);
