// nd-usage: what the old Usage page showed lives on Activity: the window every figure covers is a choice of 5 hours,
// 7 days and 30 days, kept in the address, and the page's reads follow it. The Usage page and its own files are gone.
import { afterAll, beforeAll, test } from "bun:test";
import { expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { browser, CONSOLE, daemon, HOUR, open, world, type Daemon } from "./lib/deck.ts";

let b: Browser;
let d: Daemon;
beforeAll(async () => { b = await browser(); d = daemon(world()); }, 30_000);
afterAll(async () => { await b?.close(); d?.stop(); });

const oldestSince = () => Math.min(...d.seen.filter((s) => s.path === "/api/perf/turns")
  .map((s) => Number(new URLSearchParams(s.query).get("since"))).filter((n) => Number.isFinite(n) && n > 0));

test("the window is a choice of 5 hours, 7 days and 30 days, and the reads follow it", async () => {
  const o = await open(b, d, "activity.html");
  try {
    await expect(o.page.locator("body")).toContainText("Last 7 days", { timeout: 10_000 });
    for (const w of ["5 hours", "7 days", "30 days"]) await expect(o.page.locator(".seg-o", { hasText: w }).first()).toBeVisible();
    d.seen.length = 0;
    await o.page.locator(".seg-o", { hasText: "30 days" }).first().click();
    await expect(o.page.locator("body")).toContainText("Last 30 days", { timeout: 10_000 });
    await expect.poll(() => Date.now() - oldestSince(), { timeout: 10_000 }).toBeGreaterThan(29 * 24 * HOUR);
    expect(new URL(o.page.url()).searchParams.get("win")).toBe("30d");
    expect(o.errors).toEqual([]);
  } finally {
    await o.close();
  }
  const five = await open(b, d, "activity.html?win=5h");
  try {
    await expect(five.page.locator("body")).toContainText("Last 5 hours", { timeout: 10_000 });
  } finally {
    await five.close();
  }
}, 60_000);

test("the Usage page and its own files are gone", () => {
  for (const f of ["usage.html", "usage.js", "usage.css", "perf-results.js"]) expect(existsSync(join(CONSOLE, f)), f).toBe(false);
});
