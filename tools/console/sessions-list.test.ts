// The list on Sessions as he reads it (round 2's bug list, Oct 27): clearing a search closes the ended sessions the
// search opened; every lamp has its state as its name, so a working row and a stalled one read apart without sight;
// a working row's time in its state counts from when that state began, whether or not splice holds a request for it
// at that moment; and a session is listed once, its ended namesake within reach from it. Through the real Sessions
// page against a stub daemon.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const MIN = 60_000;
type Row = Record<string, unknown>;
const row = (id: string, name: string, p: string, over: Row = {}): Row => ({
  session_id: id, name, head: "alpha", route: "head", availability: "live", status: "idle", cwd: `/work/${p}/${id}`, title: null,
  last: { role: "assistant", tool: null, text: `${name} said its last line.`, ts: Date.now() - 2 * MIN },
  updated_at: new Date(Date.now() - 2 * MIN).toISOString(),
  project: { id: `/work/${p}`, name: p, label: p, source: "folder", folder: "/work" }, ...over,
});

let sessions: () => Row[] = () => [];
let turns: () => Row[] = () => [];
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/sessions") return Response.json({ reading: true, sessions: sessions() });
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-alpha" }] });
    if (path === "/api/models") return Response.json({ heads: [] });
    if (path === "/api/heads/alpha/turns/live") return Response.json({ turns: turns() });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox", "--disable-background-networking"] });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

async function open(): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/sessions.html`);
  await expect(page.locator(".list .card.s").first()).toBeVisible({ timeout: 15_000 });
  return page;
}
const card = (page: Page, name: string) => page.locator(".card.s", { has: page.locator(".name", { hasText: new RegExp(`^${name}$`) }) });

test("clearing a search closes the ended sessions it opened", async () => {
  sessions = () => [row("a-1", "api", "website"), row("b-1", "builder", "temple"), row("o-1", "oldie", "attic", { availability: "gone" })];
  turns = () => [];
  const page = await open();
  try {
    await expect(card(page, "oldie")).toHaveCount(0);
    await page.locator("#q").fill("oldie");
    await expect(card(page, "oldie")).toBeVisible({ timeout: 10_000 });
    await page.locator("#q").fill("");
    await expect(card(page, "builder")).toBeVisible();
    await expect(card(page, "oldie")).toHaveCount(0, { timeout: 10_000 });
  } finally {
    await page.close();
  }
}, 40_000);

test("every lamp names its state, so a stalled row and a working row read apart", async () => {
  sessions = () => [row("s-st", "stuck", "temple", { status: "working" }), row("s-wk", "busy", "temple", { status: "working" }), row("a-1", "api", "website")];
  turns = () => [
    { session: "s-st", id: "t-st", phase: "stream", idle_ms: 120_000, seen_output: true, age_ms: 200_000, retries: 0 },
    { session: "s-wk", id: "t-wk", phase: "stream", idle_ms: 1_000, seen_output: true, age_ms: 5_000, retries: 0 },
  ];
  const page = await open();
  try {
    await expect(card(page, "stuck").getByRole("img", { name: "Stalled" })).toBeVisible({ timeout: 10_000 });
    await expect(card(page, "busy").getByRole("img", { name: "Working" })).toBeVisible();
    await expect(card(page, "api").getByRole("img", { name: "Idle" })).toBeVisible();
  } finally {
    await page.close();
  }
}, 40_000);

test("a working row counts its time from when it started working, with or without a request in flight", async () => {
  let reads = 0;
  const started = new Date(Date.now() - 10 * MIN - 5_000).toISOString();
  sessions = () => { reads += 1; return [row("s-w", "worker", "temple", { status: "working", status_updated_at: started, updated_at: new Date().toISOString() }), row("a-1", "api", "website")]; };
  // splice holds a request for it on every other read, three seconds into that request
  turns = () => (reads % 2 ? [{ session: "s-w", id: `t-${reads}`, phase: "stream", idle_ms: 500, seen_output: true, age_ms: 3_000, retries: 0 }] : []);
  const page = await open();
  try {
    const figure = card(page, "worker").locator(".barcell.age .v");
    await expect(figure).toHaveText("10 min", { timeout: 10_000 });
    const seen = reads;
    await expect.poll(() => reads, { timeout: 15_000 }).toBeGreaterThan(seen + 1);
    await expect(figure).toHaveText("10 min");
  } finally {
    await page.close();
  }
}, 45_000);

test("a session is listed once, and its ended namesake is reached from it", async () => {
  sessions = () => [
    row("n-live", "lenovo", "temple", { availability: "stale", updated_at: new Date(Date.now() - 40 * MIN).toISOString() }),
    row("n-gone", "lenovo", "temple", { availability: "gone", updated_at: new Date(Date.now() - 3 * 60 * MIN).toISOString() }),
    row("a-1", "api", "website"),
  ];
  turns = () => [];
  const page = await open();
  try {
    await page.locator("#q").fill("lenovo");
    await expect(card(page, "lenovo")).toHaveCount(1, { timeout: 10_000 });
    await card(page, "lenovo").locator("button.name").click();
    await expect(page.locator(`.pane a[href="#n-gone"]`)).toBeVisible({ timeout: 10_000 });
  } finally {
    await page.close();
  }
}, 40_000);
