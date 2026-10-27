// What Accounts must show again (round 2's integrity check, Oct 27), through the real page against a stub daemon that
// answers in the routes' own shapes:
// - when two commands share a provider's logins, a move names the command that moved, one line per command;
// - a Remove splice refuses keeps "Not removed" and shows splice's own reason on the card, so a login busy with another
//   edit (wait) can be told from one whose folder could not be deleted (needs his hand);
// - a Use now splice refuses says so on the card it was pressed on.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HOUR = 3_600_000;
const now = Date.now();
const sec = (ms: number) => Math.floor(ms / 1000);
const HEADS = ["claude-splice", "claude-work"];
const REASON = "'spare' could not be removed; its folder is still on disk";
const login = (label: string, name: string, used: number) => ({
  label, display_name: name, ref: label.padEnd(8, "0").slice(0, 8), heads: HEADS, provider: "anthropic",
  account_labels: Object.fromEntries(HEADS.map((h) => [h, label])), credential_present: true, identity_verified: true,
  can_rename: true, can_remove: true, edit_target: { kind: "pool", id: label },
  five_hour_used_percent: used, five_hour_reset_epoch_seconds: sec(now + 2 * HOUR), five_hour_window_seconds: 18_000,
  seven_day_used_percent: 10, seven_day_reset_epoch_seconds: sec(now + 50 * HOUR), seven_day_window_seconds: 604_800,
  observed_at_epoch_seconds: sec(now), pinned_heads: [],
});
// both commands moved off "first" onto "second" for the same reason, a minute apart
const moved = (at: number) => ({ account_pool: { last_switch: { from: "first", to: "second", reason: "quota usage reading full", at_epoch_millis: at } } });
const answers: Record<string, unknown> = {
  "/api/status": { registry: HEADS.map((key) => ({ key, label: key, family: "anthropic", authKind: "oauth" })) },
  "/api/accounts": { accounts: [login("first", "First", 100), login("second", "Second", 20), login("spare", "Spare", 5)], head_pools: {} },
  "/api/keys": { keys: [] },
  "/api/budgets": { budgets: [] },
  "/api/models": { heads: [] },
  "/api/heads": [],
  "/api/economics": { heads: HEADS.map((key) => ({ key, label: key, unpriced_reason: "plan", not_read_hours: [], buckets: [] })) },
  "/api/auth": { "claude-splice": moved(now - 120_000), "claude-work": moved(now - 60_000) },
  "/api/sessions": { sessions: [] },
};
const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (req.method === "DELETE" && /^\/api\/auth\/[^/]+\/accounts\//.test(path)) return Response.json({ error: REASON }, { status: 409 });
    if (req.method === "POST" && /^\/api\/auth\/[^/]+\/switch$/.test(path)) return Response.json({ error: "this login is busy with a sign-in" }, { status: 409 });
    if (/^\/api\/auth\/[^/]+\/order$/.test(path)) {
      return Response.json({ order: [], effective_order: ["second", "spare", "first"], next_target: "second", following_target: "spare" });
    }
    if (/^\/api\/heads\/[^/]+\/turns\/live$/.test(path)) return Response.json({ turns: [] });
    if (path in answers) return Response.json(answers[path]);
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "accounts.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
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

async function onPage(check: (page: Page) => Promise<void>) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    await expect(card(page, "Second")).toBeVisible({ timeout: 15_000 });
    await check(page);
  } finally {
    await page.close();
  }
}
const card = (page: Page, name: string) => page.locator("article.card").filter({ has: page.locator(".name", { hasText: new RegExp(`^${name}$`) }) });

test("when two commands share the logins, each move names its command, on a line of its own", () => onPage(async (page) => {
  const lines = card(page, "Second").locator(".moveline");
  await expect(lines).toHaveCount(2);
  const said = (await lines.allInnerTexts()).map((t) => t.replace(/\s+/g, " "));
  bunExpect(said.filter((t) => t.includes("claude-splice"))).toHaveLength(1);
  bunExpect(said.filter((t) => t.includes("claude-work"))).toHaveLength(1);
  for (const t of said) bunExpect(t).toContain("First");
}), 30_000);

test("a Remove splice refuses says Not removed and shows splice's reason on the card", () => onPage(async (page) => {
  const spare = card(page, "Spare");
  await spare.getByRole("button", { name: "More" }).click();
  await spare.getByRole("menuitem", { name: "Remove" }).click();
  await spare.locator(".menu .armed").click();
  await expect(spare).toContainText("Not removed");
  // the reason may stand beside the word or open under it: either way he reaches it from the word
  const why = spare.getByRole("button", { name: /Not removed/ });
  if (await why.count()) await why.click();
  await expect(spare).toContainText("its folder is still on disk");
}), 30_000);

test("a Use now splice refuses says Not switched on the card it was pressed on", () => onPage(async (page) => {
  const spare = card(page, "Spare");
  await spare.getByRole("button", { name: "Use now" }).click();
  await expect(spare).toContainText("Not switched", { timeout: 10_000 });
}), 30_000);
