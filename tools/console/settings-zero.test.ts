// Settings' counts: a zero is a quiet line under its group's tiles and never a tile; the tile that is not zero keeps its
// doors. Through the real page against a stub daemon that answers only what Settings reads: each figure's count is the
// daemon's answer to that figure's ask, as Settings asks it of /api/perf/turns.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const answers: Record<string, unknown> = {
  "/api/heads": { heads: [{ key: "alpha", label: "alpha" }, { key: "beta", label: "beta" }] },
  "/api/models": { heads: [] },
  // a stall setting, so Waited out is asked at all: with none answered it reads Not read
  "/api/config": { layers: {}, effective: { streamIdleMs: 60_000 } },
  "/api/upgrade": {},
  "/api/history": {},
};
/** The week as the daemon counts it on the first command, word by word; the second command counted none. */
let week: Record<string, number> = {};
/** Which figure an ask is for, by the filter Settings sends for it. */
function wordOf(query: string): string {
  const q = new URLSearchParams(query), test = q.get("test");
  if (test === "silent") return q.get("started_over") === "1" ? "silentover" : "silentresume";
  if (test) return test;
  return q.get("outcome") === "error:restarted" ? "restarted" : q.has("queued_ms") ? "queued" : "unknown";
}
const server = Bun.serve({
  port: Number(process.env.SETTINGS_ZERO_PORT ?? 0),
  fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    if (path === "/api/perf/turns") {
      const head = url.searchParams.get("head") ?? "alpha", asks = url.searchParams.getAll("ask");
      const count = (query: string) => ({ count: head === "alpha" ? week[wordOf(query)] ?? 0 : 0, rows: [] });
      return Response.json({ heads: [{ key: head, ...(asks.length ? { asks: asks.map(count) } : { count: 0, rows: [] }) }] });
    }
    if (path in answers) return Response.json(answers[path]);
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "settings.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
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

/** Stalled answers over a week the daemon counted as given. */
async function stalledAnswers(counts: Record<string, number>): Promise<Page> {
  week = counts;
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/settings.html`);
  await page.getByText("Stalled answers").first().click();
  await expect(page.getByRole("heading", { name: "This week" })).toBeVisible({ timeout: 15_000 });
  return page;
}

test("Ended early at zero with Waited out above zero: the zero is a line, the figure keeps its tile and doors", async () => {
  const page = await stalledAnswers({ waited: 7 });
  try {
    await expect(page.locator(".fig3.none")).toHaveCount(0);
    await expect(page.locator(".fgroup", { hasText: "Ended early" }).locator(".fig3")).toHaveCount(0);
    await expect(page.locator(".fgroup", { hasText: "Ended early" }).locator(".zeros")).toContainText("0");
    const kept = page.locator(".fgroup", { hasText: "Kept going" });
    await expect(kept.locator(".fig3")).toHaveCount(1);
    await expect(kept.locator(".fig3 .lead")).toContainText("7");
    await expect(kept.locator(".fig3 .cdoor")).toHaveCount(1);
    await expect(kept.locator(".zeros")).toContainText("0");
  } finally {
    await page.close();
  }
}, 30_000);

test("every figure at zero: no tile on the page, only quiet lines", async () => {
  const page = await stalledAnswers({});
  try {
    await expect(page.locator(".fig3")).toHaveCount(0);
    await expect(page.locator(".fgroup .zeros")).toHaveCount(2);
  } finally {
    await page.close();
  }
}, 30_000);
