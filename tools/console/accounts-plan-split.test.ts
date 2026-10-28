// Accounts' Day once splice splits plan from key (fin's words, Marlin's ruling, Oct 11), through the real page against a
// stub daemon that answers with the split fields of /api/economics: plan_turns, plan_value_usd, plan_unpriced_turns per
// hour and all_accounts_on_plan per command. A bare "≈$" is key money only; a plan's share is a count "on your plan",
// and a dollar on it says "at API prices".
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HOUR = 3_600_000;
const dayStart = Math.floor(Date.now() / (24 * HOUR)) * 24 * HOUR;
const noon = dayStart + 12 * HOUR;
const split = (turns: number, cost: number, unpriced: number, plan: number, planUsd: number) =>
  ({ hour: noon, turns, cost_usd: cost, unpriced_turns: unpriced, plan_turns: plan, plan_value_usd: planUsd, plan_unpriced_turns: 0 });
const before = (turns: number, cost: number) => ({ hour: noon + HOUR, turns, cost_usd: cost, unpriced_turns: 0, plan_turns: null, plan_value_usd: null, plan_unpriced_turns: null });
const heads = [
  { key: "all-plan", all_accounts_on_plan: true, buckets: [split(23_559, 0, 0, 23_559, 1512.4)] },
  { key: "plan-before", all_accounts_on_plan: true, buckets: [split(10, 0, 0, 10, 1), before(100, 5)] },
  { key: "mixed", all_accounts_on_plan: false, buckets: [split(23_559, 12.4, 0, 23_259, 1500)] },
  { key: "mixed-before", all_accounts_on_plan: false, buckets: [split(23_559, 12.4, 0, 23_259, 1500), before(50, 3)] },
  { key: "plan-budget", all_accounts_on_plan: true, buckets: [split(40, 0, 0, 40, 2)] },
  // figures long enough that the line wraps in a column, as claude-splice did live at 3828 (24,700 · ≈$1,579.77)
  { key: "long-plan", all_accounts_on_plan: true, buckets: [split(1_234_567, 0, 0, 1_234_567, 123_456.78)] },
];
const answers: Record<string, unknown> = {
  "/api/status": { registry: heads.map((h) => ({ key: h.key, label: h.key, family: "openai", authKind: "oauth" })) },
  "/api/accounts": { accounts: [] },
  "/api/keys": { keys: [] },
  "/api/budgets": {
    budgets: [{ head: "plan-budget", daily_usd: 20, action: "warn", used_usd: 0, unpriced_turns: 40, unpriced_reason: "plan" }],
    day_started_at_epoch_ms: dayStart, day_resets_at_epoch_ms: dayStart + 24 * HOUR,
  },
  "/api/models": { heads: [] },
  "/api/heads": [],
  "/api/economics": { heads: heads.map((h) => ({ ...h, label: h.key, unpriced_reason: "plan", not_read_hours: [] })) },
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
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox", "--disable-background-networking"] });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

async function dayOf(command: string, width = 1440) {
  const page = await browser.newPage({ viewport: { width, height: 1000 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    const meter = page.locator(`[data-meter="${command}"]`);
    await expect(meter).toBeVisible({ timeout: 15_000 });
    await expect(meter).not.toContainText("Reading", { timeout: 15_000 });
    const past = await page.evaluate((key) => {
      const el = document.querySelector(`[data-meter="${key}"]`)!;
      const box = el.getBoundingClientRect();
      return [...el.querySelectorAll("*")].filter((e) => e.getBoundingClientRect().right > box.right + 1).length;
    }, command);
    return { text: (await meter.innerText()).replace(/\s+/g, " "), past };
  } finally {
    await page.close();
  }
}

test("a command all on a plan counts its requests on the plan and names their value at API prices, with no Set budget", async () => {
  const { text } = await dayOf("all-plan");
  bunExpect(text).toContain("23,559 requests on your plan · ≈$1,512 at API prices");
  bunExpect(text).not.toContain("Set budget");
}, 30_000);

test("on a command all on a plan, an hour from before the split counts on the plan too, its value at API prices", async () => {
  const { text } = await dayOf("plan-before");
  bunExpect(text).toContain("110 requests on your plan · ≈$6.00 at API prices");
}, 30_000);

test("a mixed command shows the key's money for the key's requests and the plan's share as a count, never its value", async () => {
  const { text } = await dayOf("mixed");
  bunExpect(text).toContain("≈$12.40 for 300 requests · 23,259 on your plan");
  bunExpect(text).not.toContain("1,500");
  bunExpect(text).toContain("Set budget");
}, 30_000);

// the requests from before the split cannot be sorted into plan and key; the line counts them as "earlier, unsorted"
// (Accounts' goal, Oct 28), a count and never money
test("on a mixed command an hour from before the split is counted as earlier and unsorted, never as money", async () => {
  const { text } = await dayOf("mixed-before");
  bunExpect(text).toContain("≈$12.40 for 300 requests · 23,259 on your plan · 50 earlier, unsorted");
  bunExpect(text).not.toContain("15.40");
}, 30_000);

test("a budget set before on a command all on a plan keeps its button, so he can see it and remove it", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    const meter = page.locator(`[data-meter="plan-budget"]`);
    await expect(meter).toContainText("Budget $20", { timeout: 15_000 });
    await expect(meter).toContainText("40 requests on your plan");
    await expect(meter).not.toContainText("Set budget");
    await meter.getByRole("button", { name: /Budget \$20/ }).click();
    await expect(page.locator(`[data-meter="plan-budget"]`).getByRole("button", { name: "Remove budget" })).toBeVisible();
  } finally {
    await page.close();
  }
}, 30_000);

// these two drive a page per command per width, so they carry their own timeout: bun's 5 s default turned them red on a
// machine under load (load average 134 on 16 cores, Oct 11) while the same tests passed in 19 s on a quiet one
for (const width of [3828, 1440]) {
  test(`at ${width} every split Day line stays inside its card`, async () => {
    for (const command of heads.map((h) => h.key)) bunExpect((await dayOf(command, width)).past).toBe(0);
  }, 60_000);
}

// A split Day line that wraps opens its next line with the phrase, never with the dot that joined it to the one before:
// that dot is cut off at the card's edge, and every dot still drawn stands between two phrases on one line.
test("a wrapped split Day line never opens a line with a dot", async () => {
  let wrapped = 0;
  for (const width of [3828, 1440, 1280]) {
    const page = await browser.newPage({ viewport: { width, height: 1000 } });
    try {
      await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
      await expect(page.locator(`[data-meter="long-plan"]`)).toContainText("on your plan", { timeout: 15_000 });
      const seen = await page.evaluate(() => [...document.querySelectorAll(".money.line")].map((line) => {
        const edge = line.getBoundingClientRect().left;
        const parts = [...line.querySelectorAll(".part")].map((p) => p.getBoundingClientRect().top);
        const leading = [...line.querySelectorAll(".sep")].filter((s) => {
          const r = s.getBoundingClientRect();
          return r.right > edge + 0.5 && r.left < edge + 2; // drawn, and at the line's start
        }).length;
        return { lines: new Set(parts.map(Math.round)).size, leading };
      }));
      for (const s of seen) bunExpect(s.leading).toBe(0);
      wrapped += seen.filter((s) => s.lines > 1).length;
    } finally {
      await page.close();
    }
  }
  bunExpect(wrapped).toBeGreaterThan(0); // at least one line did wrap, so the rule was put to the test
}, 60_000);
