// The Models page asks splice only while it can be seen, one round at a time, and reads the lists that change only when
// someone saves or edits a file once a minute, not every 5 s (Oct 25: nine routes every 5 s, in a hidden tab too, with
// rounds piling up behind a slow splice). What he reads on a command's card stays as current as it was where it moves: a
// command that starts outside the page, his own Start, and a tab he comes back to. Through the real page in Chromium on a
// clock the test holds, against a daemon that answers as splice does. No test here counts requests: each judges the card.
import { afterAll, beforeAll, beforeEach, expect, test } from "bun:test";
import { chromium, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const CARD = 'article.card.h[data-open="alpha"]';
// What the daemon says about its one command, alpha. A test changes it the way a terminal, a hand edit or a restart would.
const world = { running: false, label: "Alpha One" };
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-alpha", running: world.running, healthy: world.running }] });
    if (path === "/api/topology") return Response.json({ topology: { heads: { alpha: { provider: "alpha-co" } }, providers: { "alpha-co": { base_url: "https://api.alpha.example" } } } });
    if (path === "/api/models") return Response.json({ heads: [{ head: "alpha", pinned_model: null, models: [{ id: "alpha-one", label: world.label, resolved: true, slot: "opus" }] }] });
    // a stopped command is a failed daemon/head check in doctor, which offers the fix that starts it
    if (path === "/api/doctor") return Response.json({ checks: world.running ? [] : [{ id: "daemon/head alpha", status: "fail", detail: "alpha is not running", fix_id: "start-alpha" }] });
    if (path === "/api/sessions") return Response.json({ sessions: [] });
    if (path === "/api/projects") return Response.json({ projects: [] });
    if (path === "/api/models/local") return Response.json({ providers: [] });
    if (path === "/api/claude-head") return Response.json({ mode: "wrapped", shim_path: "/home/u/.local/bin/claude", resolves_to: "/home/u/.local/bin/claude" });
    if (path === "/api/add/profiles") return Response.json({ profiles: [] });
    if (path === "/api/config") return Response.json({});
    if (path === "/api/doctor/fix/start-alpha" && req.method === "POST") {
      world.running = true;
      return Response.json({});
    }
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
beforeEach(() => {
  world.running = false;
  world.label = "Alpha One";
});

// The page has no call on its way. A round is a chain of reads with no gap between them, so a page that shows none in
// flight is between rounds, and what it drew is what the last round read.
async function settled(page: Page) {
  const give = Date.now() + 10_000;
  while ((await page.evaluate<number>("API.pending()")) > 0) {
    if (Date.now() > give) throw new Error("the page still has calls in flight after 10 s");
    await Bun.sleep(25);
  }
}
// The page's clock moves only here, so a 5 s tick comes when the test says and never on its own.
async function step(page: Page, ms: number) {
  await page.clock.runFor(ms);
  await settled(page);
}
// The tab goes out of view or comes back, as the browser does it: the page reads document.hidden, then hears the event.
const setVisible = (page: Page, visible: boolean) =>
  page.evaluate((v) => {
    Object.defineProperty(document, "hidden", { configurable: true, get: () => !v });
    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => (v ? "visible" : "hidden") });
    document.dispatchEvent(new Event("visibilitychange"));
  }, visible);
// What the alpha card says: its word, then its model.
const cardOf = (page: Page) =>
  page.evaluate((sel) => {
    const card = document.querySelector(sel);
    return card ? [...card.querySelectorAll(".meta > span")].map((s) => s.textContent ?? "") : null;
  }, CARD);
// The card once it reads as wanted, or after 5 s of real time, whichever comes first.
async function cardReading(page: Page, want: string[]) {
  const give = Date.now() + 5_000;
  let seen = await cardOf(page);
  while (JSON.stringify(seen) !== JSON.stringify(want) && Date.now() < give) {
    await Bun.sleep(50);
    seen = await cardOf(page);
  }
  return seen;
}
// The Models page on a screen too narrow to open a command beside its list. A pane holds a draft, and the page reads
// nothing under a draft, so with one open nothing below would be tested. Loaded, then held: its clock stands still.
async function open(): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: 1024, height: 900 } });
  try {
    await page.clock.install({ time: new Date("2026-10-25T12:00:00Z") });
    await page.goto(`http://127.0.0.1:${server.port}/models.html`);
    await page.locator(CARD).waitFor({ timeout: 15_000 });
    expect(await page.locator(".cmds.open").count()).toBe(0);
    await settled(page);
    await page.clock.pauseAt((await page.evaluate<number>("Date.now()")) + 1_000);
    await settled(page);
    return page;
  } catch (error) {
    await page.close();
    throw error;
  }
}

test("Back on the tab after two minutes hidden, what changed meanwhile shows at once", async () => {
  const page = await open();
  try {
    expect(await cardOf(page)).toEqual(["Not started", "Alpha One"]);
    await setVisible(page, false);
    await step(page, 120_000);
    await step(page, 1_000);
    // started from a terminal, its doctor check cleared, and the model renamed in splice.toml, all while nobody looked
    world.running = true;
    world.label = "Alpha Two";
    await setVisible(page, true);
    await settled(page);
    expect(await cardReading(page, ["Ready", "Alpha Two"])).toEqual(["Ready", "Alpha Two"]);
  } finally {
    await page.close();
  }
}, 30_000);

test("A command started outside the page reads Ready within one tick", async () => {
  const page = await open();
  try {
    expect(await cardOf(page)).toEqual(["Not started", "Alpha One"]);
    world.running = true;
    await step(page, 5_000);
    expect(await cardReading(page, ["Ready", "Alpha One"])).toEqual(["Ready", "Alpha One"]);
  } finally {
    await page.close();
  }
}, 30_000);

test("A change to the lists made outside the console shows within a minute", async () => {
  world.running = true;
  const page = await open();
  try {
    expect(await cardOf(page)).toEqual(["Ready", "Alpha One"]);
    world.label = "Alpha Two";
    for (let s = 5; s <= 65; s += 5) await step(page, 5_000);
    expect(await cardReading(page, ["Ready", "Alpha Two"])).toEqual(["Ready", "Alpha Two"]);
  } finally {
    await page.close();
  }
}, 60_000);

test("Start on a stopped command's card brings it to Ready at once", async () => {
  const page = await open();
  try {
    expect(await cardOf(page)).toEqual(["Not started", "Alpha One"]);
    await page.locator(`${CARD} [data-act="fix"]`).click();
    await settled(page);
    expect(await cardReading(page, ["Ready", "Alpha One"])).toEqual(["Ready", "Alpha One"]);
  } finally {
    await page.close();
  }
}, 30_000);
