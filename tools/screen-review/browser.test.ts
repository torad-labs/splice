// NEW: durable full-page pin, below-fold reload, verdict, edit, and delete proofs through the real UI.
import { afterAll, afterEach, beforeAll, beforeEach, test } from "bun:test";
import { chromium, expect, type Browser, type BrowserContext, type Page } from "@playwright/test";
import { mkdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { fixture } from "./fixtures/fixture.ts";
import { startReview } from "./server.ts";

let server: Awaited<ReturnType<typeof startReview>>;
let data: ReturnType<typeof fixture>;
let browser: Browser;
let context: BrowserContext;
let page: Page;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined,
    args: ["--no-sandbox", "--disable-background-networking"] });
}, 15_000);
afterAll(async () => { await browser.close(); });
beforeEach(async () => {
  context = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: "dark" });
  page = await context.newPage();
  data = fixture();
  server = await startReview({ ...data, port: 0 });
  await page.goto(server.url.toString());
  await expect(page.locator("#screen-title")).toHaveText("Overview");
  await expect.poll(() => page.locator("#screen-page").evaluate((el) => el.getBoundingClientRect().height)).toBeGreaterThan(900);
});
afterEach(async () => { server.stop(true); await context.close(); });
async function saved(page: Page) { await expect(page.locator("#save-status")).toHaveText("Saved"); }
async function point(page: Page, x: number, y: number) {
  await page.locator("#screen-canvas").evaluate((el, fraction) => { el.scrollTop = el.scrollHeight * fraction - el.clientHeight / 2; }, y);
  const box = await page.locator("#screen-page").boundingBox();
  if (!box) throw new Error("The mock page is missing.");
  const clickX = Math.round(box.x + box.width * x);
  const clickY = Math.round(box.y + box.height * y);
  await page.mouse.click(clickX, clickY);
  await expect(page.locator("#editor")).toBeVisible();
  return { x: (clickX - box.x) / box.width, y: (clickY - box.y) / box.height };
}
async function fractions(page: Page) {
  return page.locator(".pin").evaluate((pin) => {
    const point = pin.getBoundingClientRect();
    const bounds = document.getElementById("screen-page")!.getBoundingClientRect();
    return { x: (point.x + point.width / 2 - bounds.x) / bounds.width, y: (point.y + point.height / 2 - bounds.y) / bounds.height };
  });
}
test("a below-fold pin reloads at its full-page position and a verdict persists", async () => {
  const placed = await point(page, .25, .72);
  await page.locator("#note").fill("Synthetic feedback about the lower page.");
  await page.getByRole("button", { name: "Approve", exact: true }).click();
  await saved(page);
  const persisted = JSON.parse(readFileSync(data.feedbackFile, "utf8")).screens["01-overview"];
  expect(persisted.verdict).toBe("approved");
  expect(persisted.pins[0].x).toBeCloseTo(placed.x, 5);
  expect(persisted.pins[0].y).toBeCloseTo(placed.y, 5);
  expect(persisted.pins[0].at).toBeTruthy();
  expect(persisted.pins[0].width).toBe(1440);
  await page.reload();
  await expect(page.locator("#note")).toHaveValue("Synthetic feedback about the lower page.");
  await expect.poll(() => page.locator("#screen-canvas").evaluate((el) => el.scrollTop)).toBeGreaterThan(100);
  let position = await fractions(page);
  expect(position.x).toBeCloseTo(placed.x, 4);
  expect(position.y).toBeCloseTo(placed.y, 4);
  await expect(page.locator("#approve")).toHaveAttribute("aria-pressed", "true");
  await page.setViewportSize({ width: 3394, height: 1889 });
  await expect.poll(async () => (await fractions(page)).y).toBeCloseTo(placed.y, 4);
  position = await fractions(page);
  expect(position.x).toBeCloseTo(placed.x, 4);
  expect(await page.locator("#mock").evaluate((el) => el.clientWidth)).toBe(1440);
  await page.getByRole("button", { name: "Close pin editor" }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.locator("#mock").evaluate((el) => el.clientWidth)).toBe(390);
  await expect.poll(() => page.locator("#mock").evaluate((el) => el.clientHeight)).toBe(9000);
  await page.locator(".note-item").click();
  await expect.poll(() => page.locator("#mock").evaluate((el) => el.clientWidth)).toBe(1440);
  await expect.poll(() => page.locator("#mock").evaluate((el) => el.clientHeight)).toBe(5600);
  const restored = await fractions(page);
  expect(restored.x).toBeCloseTo(placed.x, 4);
  expect(restored.y).toBeCloseTo(placed.y, 4);
}, 15_000);
test("the review chrome fits wide, light, and narrow viewports without horizontal clipping", async () => {
  for (const view of [
    { width: 3394, height: 1889, colorScheme: "dark" as const },
    { width: 1440, height: 900, colorScheme: "dark" as const },
    { width: 1440, height: 900, colorScheme: "light" as const },
    { width: 390, height: 844, colorScheme: "dark" as const },
  ]) {
    await page.setViewportSize(view);
    await page.emulateMedia({ colorScheme: view.colorScheme });
    await expect(page.locator("html")).toHaveAttribute("data-theme", view.colorScheme);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await expect(page.getByRole("button", { name: "Add a pin", exact: true })).toBeVisible();
    await expect.poll(() => page.locator("#mock").evaluate((el) => el.clientWidth)).toBe(view.width);
    for (const id of ["approve", "decline"]) {
      const bounds = await page.locator(`#${id}`).boundingBox();
      expect(bounds!.y).toBeGreaterThanOrEqual(0);
      expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(view.height);
    }
    await page.getByRole("button", { name: "Add a pin", exact: true }).click();
    await page.locator("#note").fill("Synthetic feedback: make the table labels easier to scan.");
    await saved(page);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.getByRole("button", { name: "Delete pin", exact: true }).click();
    await saved(page);
  }
});
test("an incomplete folder is disabled without interrupting navigation", async () => {
  mkdirSync(join(data.screensRoot, "01-pending"));
  await page.reload();
  await expect(page.locator('[data-screen="01-pending"]')).toBeDisabled();
  await expect(page.locator('[data-screen="01-pending"]')).toContainText("Not ready");
  await page.getByRole("button", { name: "Next screen" }).click();
  await expect(page.locator("#screen-title")).toHaveText("Activity");
  await page.getByRole("button", { name: "Previous screen" }).click();
  await expect(page.locator("#screen-title")).toHaveText("Overview");
});
test("pin editing and deletion persist without affecting the other screen", async () => {
  await point(page, .5, .35);
  await page.locator("#note").fill("First synthetic note.");
  await saved(page);
  await page.locator("#note").fill("Revised synthetic note.");
  await saved(page);
  await page.reload();
  await expect(page.locator("#note")).toHaveValue("Revised synthetic note.");
  await page.getByRole("button", { name: "Next screen" }).click();
  await expect(page.locator("#screen-title")).toHaveText("Activity");
  await expect(page.locator(".pin")).toHaveCount(0);
  await page.getByRole("button", { name: "Decline", exact: true }).click();
  await saved(page);
  await page.getByRole("button", { name: "Previous screen" }).click();
  await page.getByRole("button", { name: "Feedback pin 1", exact: true }).click();
  await page.getByRole("button", { name: "Delete pin", exact: true }).click();
  await saved(page);
  await page.reload();
  await expect(page.locator(".pin")).toHaveCount(0);
  await page.getByRole("button", { name: "Next screen" }).click();
  await expect(page.locator("#decline")).toHaveAttribute("aria-pressed", "true");
});
