// A page still reading draws its own shape, never the lone word "Reading" (Marcos, Oct 12: "we can do better than that
// reading indicator"). With every daemon read held open, Requests, Teams, Usage and Settings each draw striped bars where
// their figures will land, and Settings draws its own cards, each with a ghost where its figure will land.
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

test("settings draws its own cards while it reads, each with a ghost where its figure will land", () => reading("settings", async (p) => {
  expect(await p.locator(".card.job .jname").allInnerTexts()).toContain("Stalled answers");
  // a card's figure lands under its name, in the card's state slot: the ghost stands there, inside the card and below its
  // name, in the slot's own box, so nothing moves when the figure arrives
  const cards = await p.locator(".card.job").evaluateAll((els) => els.map((c) => {
    const slot = c.querySelector(".jstate")!.getBoundingClientRect(), name = c.querySelector(".jname")!.getBoundingClientRect();
    const ghost = c.querySelector(".jstate .ghost")?.getBoundingClientRect(), box = c.getBoundingClientRect();
    const inSlot = ghost ? ghost.left >= slot.left - 1 && ghost.right <= slot.right + 1 && ghost.top >= slot.top - 1 && ghost.bottom <= slot.bottom + 1 : false;
    return {
      drawn: Boolean(ghost),
      below: ghost ? ghost.top >= name.bottom - 1 : false,
      inside: ghost ? ghost.left >= box.left - 1 && ghost.right <= box.right + 1 && ghost.bottom <= box.bottom + 1 : false,
      inSlot,
    };
  }));
  expect(cards.length).toBeGreaterThan(5);
  expect(cards.filter((c) => !c.drawn).length).toBe(0);
  expect(cards.filter((c) => !c.below || !c.inside).length).toBe(0);
  expect(cards.filter((c) => !c.inSlot).length).toBe(0);
}), 30_000);

test("teams keeps Read again off while its read is out", () => reading("teams", async (p) => {
  expect(await p.locator('.head [data-act="read"]').isDisabled()).toBe(true);
}), 30_000);

// Breakdown is the "Where it went" band now and Results by command its own band (the Usage rebuild, Oct 26): each draws
// its own table in stripes, in the band its rows land in
test("usage draws Breakdown and Commands in the frames they land in, one card each", () => reading("usage", async (p) => {
  expect(await p.locator("#band-by .card .ghost").count()).toBeGreaterThan(3);
  expect(await p.locator("#band-by .card.ghosted").count()).toBe(1);
  expect(await p.locator("#band-perf .card.ghosted").count()).toBe(1);
}), 30_000);
