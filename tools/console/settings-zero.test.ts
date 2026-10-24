// Settings' counts: a zero is a quiet line under its group's tiles and never a tile; the tile that is not zero keeps its
// doors. Through the real page against a stub daemon that answers only what Settings reads.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

// the page's own top-level bindings, which the evaluate callbacks below reach by name in the page
declare const ui: { headRows: unknown[]; week: Record<string, unknown> };
declare function render(): void;

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const answers: Record<string, unknown> = {
  "/api/heads": { heads: [{ key: "alpha", label: "alpha" }, { key: "beta", label: "beta" }] },
  "/api/models": { heads: [] },
  "/api/config": { layers: {} },
  "/api/perf/turns": { heads: [] },
  "/api/upgrade": {},
  "/api/history": {},
};
const server = Bun.serve({
  port: Number(process.env.SETTINGS_ZERO_PORT ?? 0),
  fetch(req) {
    const path = new URL(req.url).pathname;
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

/** Silent models with the week's counts as given: each word's total split across the two commands. */
async function silentModels(counts: Record<string, number>): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/settings.html`);
  await page.getByText("Silent models").first().click();
  await expect(page.getByText("This week")).toBeVisible({ timeout: 15_000 });
  await page.evaluate((week) => {
    const per = (n: number) => ui.headRows.map((head, i) => ({ head, n: i === 0 ? n : 0, newest: null, longest: null, silence: 0 }));
    for (const why of ["restarted", "gaveup", "waited", "silentresume", "silentover"]) ui.week[why] = per(week[why] ?? 0);
    render();
  }, counts);
  return page;
}

test("Ended early at zero with Waited out above zero: the zero is a line, the figure keeps its tile and doors", async () => {
  const page = await silentModels({ waited: 7 });
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
  const page = await silentModels({});
  try {
    await expect(page.locator(".fig3")).toHaveCount(0);
    await expect(page.locator(".fgroup .zeros")).toHaveCount(2);
  } finally {
    await page.close();
  }
}, 30_000);
