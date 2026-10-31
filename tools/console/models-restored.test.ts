// Two things the Models page lost in round 3 and has again. (1) The wire a command speaks to its provider (OpenAI Responses,
// OpenAI Chat, Anthropic Messages, Anthropic pass-through) is named once on the command's pane, and the spelling splice.toml
// writes it in rides that name as its title, for the one who has to write it there (GET /api/heads' dialect). (2) Every raw
// log line splice read can be reached again: the log is bounded on screen, but a way back moves the window to the lines
// before it, a way forward to the newest, so a log of three hundred lines is never cut off at what fits.
// Through the real page in Chromium against a stub daemon, judged by what he sees.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
// four commands, one for each wire splice.toml can name
const WIRES = [
  { key: "alpha", dialect: "openai-responses", words: "OpenAI Responses" },
  { key: "beta", dialect: "openai-chat", words: "OpenAI Chat" },
  { key: "gamma", dialect: "anthropic-messages", words: "Anthropic Messages" },
  { key: "delta", dialect: "anthropic-passthrough", words: "Anthropic pass-through" },
];
// alpha's log holds three hundred of its own lines, none of them an event or a turn, so the raw log is all there is to see
const LINES = Array.from({ length: 300 }, (_, i) => `[2026-10-29 09:${String(Math.floor(i / 60)).padStart(2, "0")}:${String(i % 60).padStart(2, "0")}.000] [alpha] note line ${String(i + 1).padStart(3, "0")}`);
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/heads") return Response.json({ heads: WIRES.map((w) => ({ key: w.key, label: `claude-${w.key}`, running: true, healthy: true, dialect: w.dialect })) });
    if (path === "/api/topology") {
      return Response.json({ topology: {
        heads: Object.fromEntries(WIRES.map((w) => [w.key, { provider: `${w.key}-co` }])),
        providers: Object.fromEntries(WIRES.map((w) => [`${w.key}-co`, { base_url: `https://api.${w.key}.example` }])),
      } });
    }
    if (path === "/api/models") {
      return Response.json({ heads: WIRES.map((w) => ({ head: w.key, pinned_model: null, models: [{ id: `${w.key}-one`, label: `${w.key} One`, resolved: true, slot: "opus" }] })) });
    }
    if (path === "/api/logs/alpha") return Response.json({ path: "/home/u/.local/state/splice/daemon.log", lines: LINES });
    if (path === "/api/doctor") return Response.json({ checks: [] });
    if (path === "/api/sessions") return Response.json({ sessions: [] });
    if (path === "/api/projects") return Response.json({ projects: [], folders: [] });
    if (path === "/api/models/local") return Response.json({ providers: [] });
    if (path === "/api/claude-head") return Response.json({ mode: "wrapped", shim_path: "/home/u/.local/bin/claude", resolves_to: "/home/u/.local/bin/claude" });
    if (path === "/api/add/profiles") return Response.json({ profiles: [] });
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

test("each command's pane names the wire it speaks once, with splice.toml's spelling on the name", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/models.html`);
  for (const w of WIRES) {
    await page.locator(`article.card.h[data-open="${w.key}"]`).click({ timeout: 15_000 });
    await expect(page.locator(".pane header .name")).toHaveText(`claude-${w.key}`, { timeout: 15_000 });
    const named = page.locator(".pane").getByText(w.words, { exact: true });
    await expect(named).toHaveCount(1);
    await expect(named).toHaveAttribute("title", w.dialect);
    // no other wire is named on it
    for (const other of WIRES.filter((o) => o !== w)) await expect(page.locator(".pane").getByText(other.words, { exact: true })).toHaveCount(0);
  }
  await page.close();
}, 60_000);

test("every raw log line read can be reached, a bounded window at a time, and the way back to the newest is one press", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/models.html`);
  await page.locator('article.card.h[data-open="alpha"]').click({ timeout: 15_000 });
  await page.getByRole("button", { name: /Raw log/ }).click({ timeout: 15_000 });
  const shown = page.locator(".rawlines li[title]");
  await expect(page.getByText("note line 300")).toBeVisible();
  await expect(page.getByText("note line 001")).toHaveCount(0);
  // press the way back until the oldest line is in the window; the window never holds more than 120 lines
  for (let i = 0; i < 12 && (await page.getByText("note line 001").count()) === 0; i++) {
    await page.locator(".rawwrap").getByRole("button", { name: /older/i }).click({ timeout: 5_000 });
    expect(await shown.count() <= 120).toBe(true);
  }
  await expect(page.getByText("note line 001")).toBeVisible();
  await expect(page.getByText("note line 300")).toHaveCount(0);
  // one press takes him back to the newest
  await page.locator(".rawwrap").getByRole("button", { name: /newest/i }).click({ timeout: 5_000 });
  await expect(page.getByText("note line 300")).toBeVisible();
  await expect(page.getByText("note line 001")).toHaveCount(0);
  await page.close();
}, 60_000);
