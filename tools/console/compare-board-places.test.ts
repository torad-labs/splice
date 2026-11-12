// What the Choose models board keeps in place and in view, as he sees it on a wide screen. A model he has chosen stays on
// its card in the order he reads by (Cheapest never folds a chosen model away behind "+N"), and a card that opens or folds
// moves only the groups under it in their own column, never a group to another column, so the page keeps its place. Through
// the real page against a stub daemon with eight providers of different lengths.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const providers = ["anthropic", "openai", "xai", "moonshot", "deepseek", "google", "mistral", "meta"];
const lengths = [9, 9, 8, 7, 3, 2, 2, 2];
// the first model of the second command is the dearest of its card, so Cheapest puts it last
const heads = providers.map((p, i) => ({ key: `cmd-${p}`, label: `cmd-${p}`, provider: p, i }));
const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/heads") return Response.json({ heads: heads.map((h) => ({ key: h.key, label: h.label })) });
    if (path === "/api/models") {
      return Response.json({ heads: heads.map((h) => ({
        head: h.key, provider: h.provider,
        models: Array.from({ length: lengths[h.i] ?? 0 }, (_, k) => ({ id: `${h.provider}-m${k}`, label: `${h.provider} model ${k}`, context_window: 1_000_000 - k * 1000, rates: { input: 20 - k * 2, output: 40 - k * 2 } })),
      })) });
    }
    if (path === "/api/auth") return Response.json(Object.fromEntries(heads.map((h) => [h.key, { present: true, env_var: `${h.provider.toUpperCase()}_KEY` }])));
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "compare.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : Response.json({}, { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox", "--disable-background-networking"] });
}, 20_000);
afterAll(async () => { await browser.close(); server.stop(true); });

const open = async (width = 3822): Promise<Page> => {
  const page = await browser.newPage({ viewport: { width, height: 1860 } });
  await page.goto(`http://127.0.0.1:${server.port}/compare.html`);
  await page.locator(".pg-board .pg-pb").first().waitFor();
  return page;
};
// where each provider's group stands: its left edge, which says its column
const columns = (page: Page) => page.evaluate(() => Object.fromEntries([...document.querySelectorAll<HTMLElement>(".pg-board .pg-pb[data-pv]")].map((g) => [g.dataset.pv, Math.round(g.getBoundingClientRect().left)])));

test("a model he has chosen stays on its card when Cheapest puts it past the first six", async () => {
  const page = await open();
  try {
    const dear = page.locator('button.pg-mrow[data-model="openai-m0"]');
    await dear.click(); // the largest window and the dearest price: first by window, last by price
    await page.getByRole("button", { name: "Cheapest" }).click();
    await page.waitForTimeout(500); // the board is drawn again in the new order
    await expect(page.locator('button.pg-mrow[data-model="openai-m0"]')).toHaveCount(1);
    await expect(page.locator('button.pg-mrow[data-model="openai-m0"]')).toBeVisible();
    await expect(page.locator('button.pg-mrow[data-model="openai-m0"]')).toHaveAttribute("aria-pressed", "true");
  } finally {
    await page.close();
  }
}, 60_000);

test("a card that opens leaves every other group in its own column", async () => {
  const page = await open();
  try {
    const before = await columns(page);
    await page.locator('[data-act="more"]').first().click();
    await page.waitForTimeout(400);
    const after = await columns(page);
    for (const [name, left] of Object.entries(before)) expect(after[name]).toBe(left);
    await page.locator('[data-act="fewer"]').first().click();
    await page.waitForTimeout(400);
    const folded = await columns(page);
    for (const [name, left] of Object.entries(before)) expect(folded[name]).toBe(left);
  } finally {
    await page.close();
  }
}, 60_000);

test("a find keeps each group that stays in the column it stood in", async () => {
  const page = await open();
  try {
    const before = await columns(page);
    await page.locator("#find").fill("model 1");
    await page.waitForTimeout(600);
    const after = await columns(page);
    for (const name of Object.keys(after)) expect(after[name]).toBe(before[name]);
  } finally {
    await page.close();
  }
}, 60_000);
