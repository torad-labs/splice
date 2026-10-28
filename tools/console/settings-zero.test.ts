// Settings' counts: an outcome that did not happen this week is never a row of the table with a 0 and an empty bar; the
// ones that did not happen are named together on one quiet line under it, and the outcome that happened keeps its row and
// its doors. Through the real page against a stub daemon that answers only what Settings reads: each figure's count is
// the daemon's answer to that figure's ask, as Settings asks it of /api/perf/turns.
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

/** The outcomes the table names, as the quiet line names those that did not happen. */
const OUTCOMES = ["splice restarted", "Given up", "Waited out", "Resumed", "Started over"];

test("Ended early at zero with Waited out above zero: the zero is one quiet line, the outcome that happened keeps its row and doors", async () => {
  const page = await stalledAnswers({ waited: 7 });
  try {
    const table = page.locator(".otab[role=table]");
    // the one outcome that happened is the table's one row: its count is a door, and the command it happened on is one
    await expect(table.locator(".orow:not(.oh):not(.og)")).toHaveCount(1, { timeout: 15_000 });
    const waited = table.locator(".orow", { hasText: "Waited out" });
    await expect(waited.locator(".on a")).toHaveText("7");
    await expect(waited.locator(".cdoor")).toHaveCount(1);
    // a group none of whose outcomes happened draws no header over nothing
    await expect(table.locator(".og")).toHaveCount(1);
    await expect(table.locator(".og")).toContainText("Kept going");
    // the outcomes that did not happen are named together on one line, and the one that did is not among them
    const none = page.locator(".ozero");
    await expect(none).toHaveCount(1);
    await expect(none).toContainText("None this week");
    await expect(none.locator(".ozw")).toHaveText(OUTCOMES.filter((w) => w !== "Waited out"));
  } finally {
    await page.close();
  }
}, 30_000);

test("every figure at zero: no table on the page, one quiet line naming every outcome", async () => {
  const page = await stalledAnswers({});
  try {
    const none = page.locator(".ozero");
    await expect(none).toHaveCount(1, { timeout: 15_000 });
    await expect(none.locator(".ozw")).toHaveText(OUTCOMES);
    await expect(page.locator(".otab")).toHaveCount(0);
  } finally {
    await page.close();
  }
}, 30_000);
