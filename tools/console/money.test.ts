// How a dollar figure reads, on the page that draws it (fin, Oct 12): a tiny reply keeps two significant digits instead
// of collapsing to "<$0.0001", a cent and up is cents, and Requests' rows say the same under a cent. Requests' row is
// read off the page, against a stub daemon serving one request that cost a hundredth of a cent.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, expect as see, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const tiny = {
  turn: "tiny", turn_id: "tiny", ts: Date.now() - 20_000, outcome: "ok", model: "m", session: "aaaaaaaa", total: 900, attempts: 1,
  retries: 0, account: "work-key", in_tokens: 600, out_tokens: 300, cost_usd: 0.000122, cost_reason: null,
};
const answers: Record<string, unknown> = {
  "/api/heads": { heads: [{ key: "alpha", label: "alpha" }] },
  "/api/perf/turns": { heads: [{ key: "alpha", label: "alpha", count: 1, returned: 1, truncated: false, rows: [tiny],
    usage: { models: [], accounts: [], sessions: [] } }] },
};
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path in answers) return Response.json(answers[path]);
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox"] });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

const CASES: [number, string][] = [
  [0.000122, "≈$0.00012"],
  [0.000149, "≈$0.00015"],
  [0.00004, "≈$0.000040"],
  [0.0033, "≈$0.0033"],
  [0.00949, "≈$0.0095"],
  [0.00999, "≈$0.01"],
  [0.0000004, "<$0.000001"],
  [0, "$0"],
  [1512.4, "≈$1,512.40"],
];

test("a dollar figure keeps two significant digits under a cent, and Requests says the same", async () => {
  const page = await browser.newPage();
  try {
    await page.goto(`http://127.0.0.1:${server.port}/requests.html`);
    const read = await page.evaluate((xs) => xs.map((x) => dollars(x)), CASES.map(([v]) => v));
    expect(read).toEqual(CASES.map(([, w]) => w));
    await see(page.locator('article[data-open="alpha:tiny"]')).toContainText("≈$0.00012", { timeout: 15_000 });
  } finally {
    await page.close();
  }
}, 30_000);

declare const dollars: (v: number) => string;
