// Accounts' Day for a command, through the real page: a day that holds an hour the daemon could not read (not_read_hours on
// /api/economics) reads "Not read", never the sum of the hours it could; a clean day shows its figure.
//
// The page is the real console served from the repo, against a stub daemon that answers only what Accounts reads.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HOUR = 3_600_000;
const dayStart = Math.floor(Date.now() / (24 * HOUR)) * 24 * HOUR; // a UTC midnight: whole hours, so no opening half-hour
const noon = dayStart + 12 * HOUR;
const commands = ["torn-day", "clean-day", "plan-day", "plan-torn", "warming", "big-day"];
// the daemon says a command is still being read for this many answers, then answers with its day (wire shape of EconomicsPayloads)
const READING_ANSWERS = 2;
let asked = 0;

/** What the stub daemon says: both commands have a priced hour today; the first also holds an hour it could not read. */
function economics(reading: boolean) {
  const bucket = (hour: number) => ({ hour, turns: 4, cost_usd: 1.5, unpriced_turns: 0 });
  return {
    heads: [
      { key: "torn-day", label: "torn-day", buckets: [bucket(noon)], not_read_hours: [noon + HOUR] },
      { key: "clean-day", label: "clean-day", buckets: [bucket(noon)], not_read_hours: [] },
      { key: "plan-day", label: "plan-day", buckets: [{ hour: noon, turns: 210, cost_usd: null, unpriced_turns: 210 }], not_read_hours: [] },
      { key: "plan-torn", label: "plan-torn", buckets: [{ hour: noon, turns: 210, cost_usd: null, unpriced_turns: 210 }, { hour: noon + 2 * HOUR, turns: 4, cost_usd: 1.5, unpriced_turns: 0 }], not_read_hours: [noon + HOUR] },
      { key: "big-day", label: "big-day", buckets: [{ hour: noon, turns: 23_559, cost_usd: 1512.4, unpriced_turns: 94 }], not_read_hours: [] },
      reading
        ? { key: "warming", label: "warming", buckets: [], read_pending: true, unavailable: "Reading saved request history before showing this command's hourly totals." }
        : { key: "warming", label: "warming", buckets: [bucket(noon)], not_read_hours: [] },
    ],
  };
}

const answers: Record<string, unknown> = {
  "/api/status": { registry: commands.map((key) => ({ key, label: key, family: "openai", authKind: "oauth" })) },
  "/api/accounts": { accounts: [] },
  "/api/keys": { keys: [] },
  "/api/budgets": { budgets: [], day_started_at_epoch_ms: dayStart, day_resets_at_epoch_ms: dayStart + 24 * HOUR },
  "/api/models": { heads: [] },
  "/api/heads": [],
};

const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/economics") return Response.json(economics(++asked <= READING_ANSWERS));
    if (path in answers) return Response.json(answers[path]);
    const file = join(consoleDir, path === "/" ? "accounts.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : Response.json({}, { status: 404 });
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

async function dayOf(command: string) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    const meter = page.locator(`[data-meter="${command}"]`);
    await expect(meter).toBeVisible({ timeout: 15_000 });
    await expect(meter).not.toContainText("Reading", { timeout: 15_000 });
    return (await meter.innerText()).replace(/\s+/g, " ");
  } finally {
    await page.close();
  }
}

test("a day that holds an hour splice could not read says Not read, not the sum of the hours it could", async () => {
  const day = await dayOf("torn-day");
  expect(day).toContain("Not read");
  expect(day).not.toContain("$1.50");
}, 30_000);

test("a day with an unread hour is the one word Not read: no money and no requests-on-your-plan count from its readable hours", async () => {
  const day = await dayOf("plan-torn");
  expect(day).toContain("Not read");
  expect(day).not.toContain("on your plan");
  expect(day).not.toContain("$");
  expect(day).not.toMatch(/\d+ requests/);
}, 30_000);

test("a day whose hours were all read shows its figure", async () => {
  const day = await dayOf("clean-day");
  expect(day).toContain("$1.50");
  expect(day).not.toContain("Not read");
}, 30_000);

test("at 3828 the requests-on-your-plan line under a Day reading stays clear of the Set budget button", async () => {
  const page = await browser.newPage({ viewport: { width: 3828, height: 1889 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    const meter = page.locator(`[data-meter="plan-day"]`);
    await expect(meter).toBeVisible({ timeout: 15_000 });
    await expect(meter).not.toContainText("Reading", { timeout: 15_000 });
    // clear means the two boxes share no area: since 18cb0a9eb the button sits on the row under the line, so a
    // left-of check alone failed whenever a longer reset label ("Resets Mon 7:00 PM", after 7 PM CT) moved it left
    const overlap = await page.evaluate(() => {
      const el = document.querySelector('[data-meter="plan-day"]')!;
      const sub = el.querySelector(".unpriced.own .part")?.getBoundingClientRect();
      const button = [...el.querySelectorAll("button")].find((b) => b.textContent?.trim() === "Set budget")?.getBoundingClientRect();
      if (!sub || !button) return null;
      return Math.max(0, Math.min(sub.right, button.right) - Math.max(sub.left, button.left))
        * Math.max(0, Math.min(sub.bottom, button.bottom) - Math.max(sub.top, button.top));
    });
    expect(overlap).not.toBeNull();
    expect(overlap!).toBe(0);
  } finally {
    await page.close();
  }
}, 30_000);

test("a command the daemon is still reading says Reading, never Not read, and then shows its day", async () => {
  asked = 0;
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const seen: string[] = [];
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    const meter = page.locator(`[data-meter="warming"]`);
    await expect(meter).toBeVisible({ timeout: 15_000 });
    // first draw: the page has not been answered yet; then the daemon answers "still reading", twice
    const watch = setInterval(async () => seen.push((await meter.innerText().catch(() => "")).replace(/\s+/g, " ")), 100);
    await expect(meter).toContainText("$1.50", { timeout: 30_000 });
    clearInterval(watch);
    expect(asked).toBeGreaterThan(READING_ANSWERS);
    expect(seen.some((day) => day.includes("Reading"))).toBe(true);
    expect(seen.some((day) => day.includes("Not read"))).toBe(false);
  } finally {
    await page.close();
  }
}, 30_000);

test("a figure past a thousand dollars groups its thousands and keeps its dollar sign", async () => {
  const day = await dayOf("big-day");
  expect(day).toContain("≈$1,512.40");
  expect(day).toContain("for 23,465 requests");
}, 30_000);

for (const width of [3828, 1440]) {
  test(`at ${width} a Day with a figure and a count keeps each phrase on one line, inside the card`, async () => {
    const page = await browser.newPage({ viewport: { width, height: 1000 } });
    try {
      await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
      const meter = page.locator(`[data-meter="big-day"]`);
      await expect(meter).toContainText("≈$1,512.40", { timeout: 15_000 });
      // read in one step from the live document: the page redraws its rows when a poll answers, and a row it replaced
      // between finding it and measuring it is detached and measures as nothing
      const shape = await page.evaluate(() => {
        const el = document.querySelector('[data-meter="big-day"]')!;
        const box = el.getBoundingClientRect();
        const lines = (e: Element | null) => (e ? e.getClientRects().length : 0);
        const phrases = [el.querySelector(".money > em"), el.querySelector(".unpriced.own .part")];
        return {
          phraseLines: phrases.map(lines),
          past: [...el.querySelectorAll("*")].filter((e) => e.getBoundingClientRect().right > box.right + 1).length,
        };
      });
      expect(shape.phraseLines).toEqual([1, 1]);
      expect(shape.past).toBe(0);
    } finally {
      await page.close();
    }
  }, 30_000);
}
