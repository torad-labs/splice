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
const commands = ["torn-day", "clean-day", "plan-day"];

/** What the stub daemon says: both commands have a priced hour today; the first also holds an hour it could not read. */
function economics() {
  const bucket = (hour: number) => ({ hour, turns: 4, cost_usd: 1.5, unpriced_turns: 0 });
  return {
    heads: [
      { key: "torn-day", label: "torn-day", buckets: [bucket(noon)], not_read_hours: [noon + HOUR] },
      { key: "clean-day", label: "clean-day", buckets: [bucket(noon)], not_read_hours: [] },
      { key: "plan-day", label: "plan-day", buckets: [{ hour: noon, turns: 210, cost_usd: null, unpriced_turns: 210 }], not_read_hours: [noon + HOUR] },
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
  "/api/economics": economics(),
};

const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
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
});

test("a day whose hours were all read shows its figure", async () => {
  const day = await dayOf("clean-day");
  expect(day).toContain("$1.50");
  expect(day).not.toContain("Not read");
});

test("at 3828 the requests-on-your-plan line under a Day reading stays clear of the Set budget button", async () => {
  const page = await browser.newPage({ viewport: { width: 3828, height: 1889 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    const meter = page.locator(`[data-meter="plan-day"]`);
    await expect(meter).toBeVisible({ timeout: 15_000 });
    await expect(meter).not.toContainText("Reading", { timeout: 15_000 });
    const gap = await meter.evaluate((el) => {
      const sub = el.querySelector(".unpriced.own .part")?.getBoundingClientRect();
      const button = [...el.querySelectorAll("button")].find((b) => b.textContent?.trim() === "Set budget")?.getBoundingClientRect();
      return sub && button ? button.left - sub.right : null;
    });
    expect(gap).not.toBeNull();
    expect(gap!).toBeGreaterThanOrEqual(0);
  } finally {
    await page.close();
  }
});
