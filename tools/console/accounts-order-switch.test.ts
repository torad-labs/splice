// Accounts' order switch for a command with two or more logins, through the real page against a stub daemon in the routes' own
// shapes. Goal (accounts-board-layout, live fix of Oct 29): the switch always shows the choice in force, and it moves when the
// choice moves. The lit part slides to My order the moment it is pressed and stays there while the order saves; the save shows
// "Saving order" with a spinner on the switch itself; and a refusal stands directly beside the switch, its word and splice's
// reason, never a gap away from it.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HOUR = 3_600_000;
const now = Date.now();
const sec = (ms: number) => Math.floor(ms / 1000);
const HEAD = "claude-splice";
const windows = {
  five_hour_used_percent: 20, five_hour_reset_epoch_seconds: sec(now + 2 * HOUR), five_hour_window_seconds: 18_000,
  seven_day_used_percent: 30, seven_day_reset_epoch_seconds: sec(now + 50 * HOUR), seven_day_window_seconds: 604_800,
  observed_at_epoch_seconds: sec(now - 60_000),
};
const login = (name: string, label: string) => ({
  label, selector_key: label, display_name: name, ref: label.padEnd(8, "0").slice(0, 8), heads: [HEAD], provider: "anthropic", kind: "client",
  credential_present: true, identity_verified: true, can_rename: true, ...windows,
});
const answers: Record<string, unknown> = {
  "/api/status": { registry: [{ key: HEAD, label: HEAD, family: "anthropic", authKind: "client" }] },
  "/api/accounts": { accounts: [login("work", "account-2"), login("home", "account-3")] },
};
const REASON = "the order file is locked by another splice";
// how the stub answers the order save: held until the test lets it go, or refused with a reason or without one
let held: string[] = []; // the order the daemon holds, as the last accepted save wrote it
let save: { hold: Promise<void>; refuse: "reason" | "bare" | null } = { hold: Promise.resolve(), refuse: null };
const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (req.method === "PUT" && path === `/api/auth/${HEAD}/order`) {
      await save.hold;
      if (save.refuse === "reason") return Response.json({ error: REASON }, { status: 409 });
      if (save.refuse === "bare") return Response.json({}, { status: 409 });
      held = ((await req.json()) as { order: string[] }).order;
      return Response.json({});
    }
    if (path === `/api/auth/${HEAD}/order`) return Response.json({ order: held, effective_order: ["account-2", "account-3"], next_target: "account-2", following_target: "account-3" });
    if (path in answers) return Response.json(answers[path]);
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "accounts.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : Response.json({}, { status: 404 });
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

async function onPage(check: (page: Page) => Promise<void>, width = 1440) {
  held = [];
  const context = await browser.newContext({ viewport: { width, height: 1000 }, colorScheme: "dark" });
  await context.addInitScript(() => localStorage.setItem("splice-console-theme", "night"));
  const page = await context.newPage();
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    await expect(page.locator(".abl-sw")).toBeVisible({ timeout: 15_000 });
    await check(page);
  } finally {
    await context.close();
  }
}

/** Where the lit part stands, as the distance from the switch's left edge to its own, in px, and the width of the choice it should cover. */
const lit = (page: Page) => page.evaluate(() => {
  const sw = document.querySelector(".abl-sw")!, box = sw.getBoundingClientRect();
  const style = getComputedStyle(sw, "::before"), matrix = new DOMMatrix(style.transform === "none" ? undefined : style.transform);
  const mine = [...sw.querySelectorAll("button")].find((b) => b.textContent?.trim() === "My order")!.getBoundingClientRect();
  const soonest = [...sw.querySelectorAll("button")].find((b) => b.textContent?.trim() === "Soonest reset")!.getBoundingClientRect();
  return { shift: matrix.e, toMine: mine.left - soonest.left, left: box.left };
});

test("pressing My order moves the lit part of the switch onto My order", () => onPage(async (page) => {
  save = { hold: Promise.resolve(), refuse: null };
  const before = await lit(page);
  bunExpect(before.shift).toBe(0);
  await page.getByRole("button", { name: "My order" }).click();
  // the slide takes 240 ms; settled, the lit part stands over the second choice
  await expect.poll(async () => Math.abs((await lit(page)).shift - (await lit(page)).toMine), { timeout: 5_000 }).toBeLessThan(2);
  bunExpect((await lit(page)).shift).toBeGreaterThan(20);
}), 30_000);

test("while the order saves the switch says Saving order with a spinner, and its lit part is already on My order", () => onPage(async (page) => {
  let release!: () => void;
  save = { hold: new Promise<void>((go) => { release = go; }), refuse: null };
  await page.getByRole("button", { name: "My order" }).click();
  const busy = page.locator(".abl-busy");
  await expect(busy).toContainText("Saving order");
  await expect(busy.locator(".abl-spin")).toBeVisible();
  await expect.poll(async () => Math.abs((await lit(page)).shift - (await lit(page)).toMine), { timeout: 5_000 }).toBeLessThan(2);
  await expect(page.locator(".abl-sw button").first()).toBeDisabled();
  // the word stands beside the switch, not at the far end of the header
  const gap = await page.evaluate(() => document.querySelector(".abl-busy")!.getBoundingClientRect().right - document.querySelector(".abl-sw")!.getBoundingClientRect().left);
  bunExpect(Math.abs(gap)).toBeLessThan(40);
  release();
  await expect(busy).toHaveCount(0);
}), 30_000);

for (const [kind, expected] of [["bare", ""], ["reason", REASON]] as const) {
  test(`an order splice refuses ${kind === "bare" ? "without a reason" : "with a reason"} stands as its word directly beside the switch, and the header keeps its height`, () => onPage(async (page) => {
    save = { hold: Promise.resolve(), refuse: kind };
    // read from the live document: the page redraws its board when a poll answers
    const headHeight = () => page.evaluate(() => document.querySelector(".abl-sw")?.closest("header")?.getBoundingClientRect().height ?? null);
    await expect.poll(headHeight).not.toBeNull();
    const height = (await headHeight())!;
    await page.getByRole("button", { name: "My order" }).click();
    const refusal = page.locator(".abl-refusal");
    await expect(refusal).toContainText("Order not saved", { timeout: 10_000 });
    if (expected) await expect(refusal.locator(".abl-why")).toHaveText(expected);
    else await expect(refusal.locator(".abl-why")).toHaveCount(0);
    const gap = await page.evaluate(() => {
      const r = document.querySelector(".abl-refusal")!, parts = [...r.children].map((e) => e.getBoundingClientRect().right);
      return document.querySelector(".abl-sw")!.getBoundingClientRect().left - Math.max(...parts);
    });
    bunExpect(gap).toBeGreaterThanOrEqual(0);
    bunExpect(gap).toBeLessThan(40);
    await expect.poll(headHeight).toBe(height);
  }), 30_000);
}
