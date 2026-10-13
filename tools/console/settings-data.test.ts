// Settings' "Your data": every store splice keeps is a card with its size, what it holds and its share of everything
// kept; a store holding nothing says so; and every Delete stands together under "Free up space", never inside a card
// (Marcos's verdict on this pane, Oct 11). The cards must also keep their words inside themselves at his width and at
// 1440, where a long "When off" note used to run past the card's right edge.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const DAY = 864e5;
const since = Date.now() - 60 * DAY;
const MiB = 1_048_576;
const answers: Record<string, unknown> = {
  "/api/heads": { heads: [{ key: "alpha", label: "alpha" }, { key: "beta", label: "beta" }] },
  "/api/models": { heads: [] },
  "/api/config": { layers: {} },
  "/api/perf/turns": { heads: [] },
  "/api/upgrade": {},
  "/api/upgrade/run": {},
  "/api/history": {
    held: { turns: 942_444, bytes: 900 * MiB, oldest_epoch_ms: since },
    window: { days: null, forever: true, nothing: false },
    rate_bytes_per_month: 2_100 * MiB,
    days: Array.from({ length: 30 }, (_, i) => ({ bytes: (i + 1) * MiB })),
    cut: { turns: 0, bytes: 0, cutoff_epoch_ms: since },
  },
  "/api/kept/edges": { rows: 41_031, bytes: 7 * MiB, oldest: "2026-09-19" },
  "/api/kept/labels": { rows: 16_756, bytes: 2 * MiB, oldest: "2026-10-11" },
  "/api/kept/turns": {},
  // transcript copies hold files; compaction summaries hold nothing, so that card must say so and claim no share
  "/api/kept/stores": {
    stores: {
      transcript_copies: { files: 1_952, bytes: 13_100 * MiB, oldest_epoch_ms: since },
      compaction_summaries: { files: 0, bytes: 0, oldest_epoch_ms: null },
      code_mode: { files: 1_910, bytes: 685 * MiB, oldest_epoch_ms: since },
    },
  },
};
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (/^\/api\/heads\/[^/]+\/trace\/kept$/.test(path)) return Response.json({ records: 100_155, bytes: 2_550 * MiB });
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

/** The pane, open, with its stores drawn. */
async function open(width: number, height: number) {
  const page = await browser.newPage({ viewport: { width, height } });
  await page.goto(`http://127.0.0.1:${server.port}/settings.html`);
  await page.getByText("Your data").first().click();
  const card = page.locator(".dcard").filter({ hasText: "Transcript copies" });
  await expect(card).toContainText("GB", { timeout: 15_000 });
  return page;
}

test("a store that holds something shows its size, its count and its share; one that holds nothing says so and claims none", async () => {
  const page = await open(1440, 900);
  try {
    const kept = page.locator(".dcard").filter({ hasText: "Transcript copies" });
    await expect(kept).toContainText("12.8 GB");
    await expect(kept).toContainText("1,952 files");
    await expect(kept).toContainText("of everything kept");
    const empty = page.locator(".dcard").filter({ hasText: "Compaction summaries" });
    await expect(empty).toContainText("Nothing saved");
    await expect(empty).not.toContainText("of everything kept");
  } finally {
    await page.close();
  }
}, 30_000);

test("every Delete stands under Free up space, and no store card holds one", async () => {
  const page = await open(1440, 900);
  try {
    const free = page.locator(".dfree");
    await expect(free).toContainText("Deleting cannot be undone");
    const inFree = await free.locator('.dfrow [data-act="arm"], .dfrow a.act').count();
    expect(inFree).toBeGreaterThan(2);
    expect(await page.locator('.dcard [data-act="arm"], .dcard .act.danger').count()).toBe(0);
    // each row under Free up space names the store it deletes, so the button is never anonymous
    await expect(free.locator(".dfrow").filter({ hasText: "Transcript copies" })).toHaveCount(1);
  } finally {
    await page.close();
  }
}, 30_000);

for (const [width, height] of [[3828, 2130], [1440, 900]] as const) {
  test(`at ${width} every word of a store card stays inside the card`, async () => {
    const page = await open(width, height);
    try {
      const past = await page.evaluate(() => {
        const out: string[] = [];
        for (const card of document.querySelectorAll(".dcard")) {
          const box = card.getBoundingClientRect();
          for (const el of card.querySelectorAll("*")) {
            const r = el.getBoundingClientRect();
            if (r.width && r.right > box.right + 1) out.push(`${(card.querySelector(".nm")?.textContent ?? "?").trim()}: ${(el.textContent ?? "").slice(0, 40)}`);
          }
        }
        return out;
      });
      expect(past).toEqual([]);
    } finally {
      await page.close();
    }
  }, 30_000);
}
