// A request on a plan account costs the person nothing: the list and the opened request say "On your plan", and the
// opened request adds what it would have cost at API prices (plan_value_usd), never as its cost (Marlin, Oct 11 5:52 PM).
// A request billed to a key keeps its dollars and has no such line. Through the real page against a stub daemon.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const row = (turn: string, agoS: number, extra: Record<string, unknown>) => ({
  turn, turn_id: turn, ts: now - agoS * 1000, outcome: "ok", model: "gpt-6.1-sol", session: "aaaaaaaa", session_id: "aaaaaaaa-1", total: 9000,
  attempts: 1, retries: 0, account: "primary", cost_usd: null, cost_reason: "plan", ...extra,
});
const tokens = { in_tokens: 169600, out_tokens: 140, cached_tokens: 169216 };
const rows = [
  row("onplan", 20, { ...tokens, plan_value_usd: 0.053 }),
  row("onkey", 40, { ...tokens, account: "work-key", cost_usd: 0.12, cost_reason: null }),
];

const answers: Record<string, unknown> = {
  "/api/heads": { heads: [{ key: "codex", label: "codex" }] },
  "/api/models": { heads: [] },
  "/api/sessions": { sessions: [] },
  "/api/keys": { keys: [] },
  "/api/topology": { topology: { providers: {} } },
  "/api/heads/codex/capture": { enabled: false, retention_days: 7 },
  "/api/config": {},
  "/api/perf/turns": { heads: [{ key: "codex", label: "codex", count: rows.length, returned: rows.length, truncated: false, rows, usage: { models: [], accounts: [], sessions: [] } }] },
};

const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path in answers) return Response.json(answers[path]);
    const file = join(consoleDir, path === "/" ? "requests.html" : path.slice(1));
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

// The list draws a plan request's worth at API prices in its cost column as a hollow bar with its figure muted, and its
// key says once what hollow means (Requests' goal, Oct 26: "A plan row draws its worth at API prices as a hollow bar with
// its figure muted. The list's key says once what hollow means."), where each plan row read "On your plan" and no "$".
test("a plan request draws its API-price worth hollow, the list's key says On your plan once; a key request keeps its cost alone", async () => {
  // This walk closes its overlay; side-by-side panes intentionally hide that control.
  const page = await browser.newPage({ viewport: { width: 1100, height: 900 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/requests.html`);
    const card = (turn: string) => page.locator(`article[data-open="codex:${turn}"]`);
    await expect(card("onplan")).toBeVisible({ timeout: 20_000 });
    const fill = (turn: string) => card(turn).locator(".co .cbar > b").evaluate((b) => getComputedStyle(b).backgroundColor);
    await expect(card("onplan").locator(".co .v")).toHaveText("≈$0.053");
    expect(await card("onplan").locator(".co.plan").count()).toBe(1);
    expect(await fill("onplan")).toBe("rgba(0, 0, 0, 0)");
    await expect(page.locator(".lkey")).toContainText("On your plan, at API prices");
    await expect(card("onkey").locator(".co .v")).toHaveText("≈$0.120");
    expect(await card("onkey").locator(".co.plan").count()).toBe(0);
    expect(await fill("onkey")).not.toBe("rgba(0, 0, 0, 0)");

    const cost = page.locator(".rqpane .fig").filter({ hasText: "Cost" });
    await card("onplan").click();
    await expect(cost.locator(".v")).toHaveText("On your plan");
    await expect(cost.locator(".fsub")).toHaveText("≈$0.053 at API prices");

    await page.locator('.rqpane [data-act="close"]').click();
    await card("onkey").click();
    await expect(cost.locator(".v")).toHaveText("≈$0.120");
    expect(await cost.locator(".fsub").count()).toBe(0);
  } finally {
    await page.close();
  }
}, 60_000);  // drives the page through several rows; bun's 5 s default turned it red on a loaded machine (Oct 11)
