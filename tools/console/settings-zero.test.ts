// Settings' counts: the table of how this week's stalls ended has the same five rows every week (Settings' goal, Oct 28);
// an outcome that did not happen is its row with an empty track and a quiet 0, and the outcome that happened keeps its
// bar, its count as a door and its command's door. Through the real page against a stub daemon that answers only what
// Settings reads: each figure's count is the daemon's answer to that figure's ask, as Settings asks it of /api/perf/turns.
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

/** The outcomes the table names, in its order: two that ended early, then three that kept going. */
const OUTCOMES = ["splice restarted", "Given up", "Waited out", "Resumed", "Started over"];

test("Ended early at zero with Waited out above zero: each zero is its row with an empty track, the outcome that happened keeps its bar and doors", async () => {
  const page = await stalledAnswers({ waited: 7 });
  try {
    const table = page.locator(".otab[role=table]");
    // every outcome has its row, under its two groups, in the same order every week
    await expect(table.locator(".orow:not(.oh):not(.og) .ow")).toHaveText(OUTCOMES, { timeout: 15_000 });
    await expect(table.locator(".og")).toHaveText(["Ended early", "Kept going"]);
    // the one outcome that happened has a bar, its count is a door, and the command it happened on is one
    const waited = table.locator(".orow", { hasText: "Waited out" });
    await expect(waited.locator(".ofill")).toHaveCount(1);
    await expect(waited.locator(".on a")).toHaveText("7");
    await expect(waited.locator(".cdoor")).toHaveCount(1);
    // the four that did not happen are a quiet 0 on an empty track, with no door
    const none = table.locator(".orow.none");
    await expect(none.locator(".ow")).toHaveText(OUTCOMES.filter((w) => w !== "Waited out"));
    await expect(none.locator(".on .nz")).toHaveText(["0", "0", "0", "0"]);
    await expect(none.locator(".ofill, a")).toHaveCount(0);
  } finally {
    await page.close();
  }
}, 30_000);

test("every figure at zero: the table stands with its five rows, each an empty track and a quiet 0", async () => {
  const page = await stalledAnswers({});
  try {
    const table = page.locator(".otab[role=table]");
    await expect(table.locator(".orow.none .ow")).toHaveText(OUTCOMES, { timeout: 15_000 });
    await expect(table.locator(".orow.none .on .nz")).toHaveText(["0", "0", "0", "0", "0"]);
    await expect(table.locator(".orow:not(.oh):not(.og):not(.none)")).toHaveCount(0);
    await expect(table.locator(".ofill, .orow a")).toHaveCount(0);
  } finally {
    await page.close();
  }
}, 30_000);
