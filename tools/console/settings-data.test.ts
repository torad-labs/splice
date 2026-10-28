// Settings' "Your data": every store splice keeps is one row of its group's table (Settings' goal, Oct 28), with its size
// as a bar on the column's scale and what it holds under its name, and its share of everything kept is its part of the
// one bar under the total; a store holding nothing is an empty track with no count and has no part; and a Delete stands
// on the row it deletes, under the column that says it cannot be undone. The rows must also keep their words inside
// themselves at his width and at 1440, where a long "When off" note used to run past the card's right edge.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
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

/** One store's row of the table. */
const rowOf = (page: Page, store: string) => page.locator(`.drow[data-store="${store}"]`);

/** The pane, open, with its stores drawn. */
async function open(width: number, height: number) {
  const page = await browser.newPage({ viewport: { width, height } });
  await page.goto(`http://127.0.0.1:${server.port}/settings.html`);
  await page.getByText("Your data").first().click();
  await expect(rowOf(page, "originals")).toContainText("GB", { timeout: 15_000 });
  return page;
}

// A store's share of everything kept is drawn as its part of the bar under the total, with its size as the part's figure,
// where each card said "N% of everything kept" (Settings' report, Oct 26)
test("a store that holds something shows its size, its count and its share; one that holds nothing is an empty track and claims none", async () => {
  const page = await open(1440, 900);
  try {
    const kept = rowOf(page, "originals");
    await expect(kept.locator(".dz .v")).toHaveText("12.8 GB");
    await expect(kept.locator(".dmeta")).toContainText("1,952 files");
    const whole = page.locator("section.psec", { has: page.locator('.sechead > .kicker:text-is("On this computer")') });
    const bar = whole.locator(".pd-pop");
    await expect(bar).toContainText("Transcript copies 12.8 GB");
    // every part is as long as its own share of the total the figure above it reads, once the bar has grown in
    const shares = () => whole.evaluate((el) => {
      const size = (t: string) => Number.parseFloat(t.replace(/[^\d.]/g, "")) * (/TB/.test(t) ? 1_048_576 : /GB/.test(t) ? 1024 : /KB/.test(t) ? 1 / 1024 : 1);
      const total = size(el.querySelector(".pd-top .pd-rate")?.textContent ?? "");
      const w = el.querySelector(".pd-bar")!.getBoundingClientRect().width;
      return [...el.querySelectorAll(".pd-bar .pd-seg")].map((s) => {
        const title = s.getAttribute("title") ?? "";
        return { title, drawn: s.getBoundingClientRect().width / w, read: size(title.replace(/^\D+/, "")) / total };
      });
    });
    await expect.poll(async () => (await shares()).find((s) => s.title.startsWith("Transcript copies"))?.drawn ?? 0).toBeGreaterThan(0.5);
    // the parts grow in together: measure once every one of them has finished, not while a small part is still partway
    await whole.locator(".pd-bar").first().evaluate((el) => Promise.all(el.getAnimations({ subtree: true }).map((a) => a.finished)).then(() => undefined));
    const parts = await shares();
    expect(parts.map((s) => s.title)).toContain("Transcript copies 12.8 GB");
    for (const part of parts) expect(Math.abs(part.drawn - part.read)).toBeLessThan(0.02);
    // a store holding nothing keeps its row: its size reads 0 B on an empty track, with no count line and no part of the bar
    const empty = rowOf(page, "recordings");
    await expect(empty.locator(".nm")).toHaveText("Compaction summaries");
    await expect(empty.locator(".dz .v")).toHaveText("0 B");
    await expect(empty.locator(".dz .vbar b")).toHaveAttribute("data-w", "0");
    await expect(empty.locator(".dmeta")).toHaveCount(0);
    await expect(bar).not.toContainText("Compaction summaries");
  } finally {
    await page.close();
  }
}, 30_000);

test("every Delete stands on the row of the store it deletes, under a column that says it cannot be undone, and a store holding nothing has none", async () => {
  const page = await open(1440, 900);
  try {
    // the column that holds the Delete is named once per table, over the rows
    const heads = await page.locator(".dhead .dcant").allInnerTexts();
    expect(heads.length).toBeGreaterThan(0);
    for (const head of heads) expect(head).toBe("Cannot be undone");
    // every store that holds something has its Delete on its own row, so the button is never anonymous
    const deletes = page.locator('.drow [data-act="arm"]');
    expect(await deletes.count()).toBeGreaterThan(2);
    await expect(rowOf(page, "originals").locator('[data-act="arm"]')).toHaveCount(1);
    await expect(rowOf(page, "recordings").locator('[data-act="arm"]')).toHaveCount(0);
    // pressing it arms the row in place with what it would delete, and a way out; nothing is deleted
    await rowOf(page, "originals").locator('[data-act="arm"]').click();
    await expect(rowOf(page, "originals").getByRole("button", { name: "Delete 1,952 files" })).toBeVisible();
    await expect(rowOf(page, "originals").getByRole("button", { name: "Cancel" })).toBeVisible();
  } finally {
    await page.close();
  }
}, 30_000);

for (const [width, height] of [[3828, 2130], [1440, 900]] as const) {
  test(`at ${width} every word of a store's row stays inside the row`, async () => {
    const page = await open(width, height);
    try {
      const past = await page.evaluate(() => {
        const out: string[] = [];
        const rows = document.querySelectorAll(".drow");
        for (const row of rows) {
          const box = row.getBoundingClientRect();
          for (const el of row.querySelectorAll("*")) {
            const r = el.getBoundingClientRect();
            if (r.width && r.right > box.right + 1) out.push(`${(row.querySelector(".nm")?.textContent ?? "?").trim()}: ${(el.textContent ?? "").slice(0, 40)}`);
          }
        }
        return { out, rows: rows.length };
      });
      expect(past.rows).toBeGreaterThan(5); // the stores are drawn as rows, so the check has rows to look at
      expect(past.out).toEqual([]);
    } finally {
      await page.close();
    }
  }, 30_000);
}
