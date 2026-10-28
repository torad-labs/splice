// What Teams must show again (round 2's integrity check, Oct 27), through the real page against a stub daemon that
// answers in the routes' own shapes:
// - a member row of the team form whose Join by rule is on draws what the rule takes: the row's command and the team's
//   folder as chips, and under the pointer that such a session joins only while the member has none running (Teams' goal,
//   Oct 28: a sentence on the row became the rule's mark);
// - a member's own plan-window figures are a row headed by the span they were counted over, "7 days", with its count
//   under the Requests column, and the plan's own long window is named on the plan's meter row ("Month" on a 30-day plan,
//   "Week" on a 7-day one), with its share.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Locator, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HOUR = 3_600_000;
const now = Date.now();
const sec = (ms: number) => Math.floor(ms / 1000);
const proj = { id: "/work/website", name: "website", label: "website", source: "folder", folder: "/work" };
const sess = (id: string) => ({ session_id: id, name: id, head: "alpha", availability: "live", status: "idle", cwd: "/work/website", project: proj });
const slot = (id: string, role: string, session: string, account: string, lead = false) =>
  ({ id, role, head: "alpha", lead, session, account, project: proj });
const tokens = (n: number) => ({ input: n, cache_read: 0, cache_write: 0, output: 0 });
const windows = (account: string, five: number, seven: number) => [
  { window: "5h", head: "alpha", account, turns: five, tokens: 42_000_000 },
  { window: "7d", head: "alpha", account, turns: seven, tokens: 498_000_000 },
];
// a pooled login as /api/auth lists it under its head, with the ref /api/accounts keys the same login by
const pooled = (label: string, ref: string) => ({ label, plan: "max", auth: { ref }, five_hour_used_percent: 1, seven_day_used_percent: 1 });
const plan = (over: Record<string, unknown>) => ({
  heads: ["alpha"], kind: "oauth", provider: "anthropic",
  five_hour_reset_epoch_seconds: sec(now + 2 * HOUR), five_hour_window_seconds: 18_000, five_hour_current: true,
  seven_day_reset_epoch_seconds: sec(now + 90 * HOUR), seven_day_current: true, ...over,
});
const answers: Record<string, unknown> = {
  "/api/teams": { teams: [{ id: "t1", name: "Site crew", repo: "/work/website", project: proj,
    slots: [slot("lead", "Lead", "s-lead", "work", true), slot("builder", "Builder", "s-build", "home")] }] },
  "/api/sessions": { sessions: [sess("s-lead"), sess("s-build")], reading: true },
  "/api/heads": { heads: [{ key: "alpha", label: "claude-alpha" }] },
  "/api/models": { heads: [{ head: "alpha", provider: "anthropic", models: [{ id: "m1", label: "Model One" }] }] },
  "/api/auth": { alpha: { kind: "oauth", present: true, account_pool: { accounts: [pooled("work", "r-work"), pooled("home", "r-home")] } } },
  // the work login is keyed by its label on the head; the home login by the ref its pool entry carries. Work's plan
  // runs a 30-day long window, home's a 7-day one.
  "/api/accounts": { accounts: [
    plan({ label: "work", display_name: "Work login", account_labels: { alpha: "work" },
      five_hour_used_percent: 20, seven_day_used_percent: 40, seven_day_window_seconds: 30 * 86_400 }),
    plan({ ref: "r-home", label: "Home", display_name: "Home login", account_labels: {},
      five_hour_used_percent: 5, seven_day_used_percent: 15, seven_day_window_seconds: 7 * 86_400 }),
  ] },
  "/api/teams/t1/economics": { slots: [
    { slot: "lead", turns: 412, tokens: tokens(500_000_000), plan_turns: 406, moved_turns: 406, priced_moved_turns: 0, plan_windows: windows("work", 31, 402) },
    { slot: "builder", turns: 196, tokens: tokens(240_000_000), plan_turns: 193, moved_turns: 193, priced_moved_turns: 0, plan_windows: windows("home", 18, 193) },
  ] },
  "/api/economics": { heads: [{ key: "alpha", label: "claude-alpha", unpriced_reason: "plan" }] },
};
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path in answers) return Response.json(answers[path]);
    if (/^\/api\/teams\/t1\/(chat|activity)$/.test(path)) return Response.json({ team_id: "t1", state: "on", messages: [], entries: [] });
    if (/^\/api\/heads\/[^/]+\/turns\/live$/.test(path)) return Response.json({ turns: [] });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox", "--disable-background-networking"] });
}, 60_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

async function onTeams(check: (p: Page) => Promise<void>) {
  const p = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await p.goto(`http://127.0.0.1:${server.port}/teams.html`);
    await expect(p.locator('article[data-key="m:lead"]')).toBeVisible({ timeout: 20_000 });
    await check(p);
  } finally {
    await p.close();
  }
}
const member = (p: Page, id: string) => p.locator(`article[data-key="m:${id}"]`);
// the element whose own words hold [text], and the row it sits in
const rowOf = (scope: Locator, text: RegExp) => scope.getByText(text).first().locator("xpath=..");
// a member's own row of plan figures for one span, headed by it: "7 days", its requests under the Requests column
const spanRow = (scope: Locator, span: string) => scope.locator(".mtab .mtr.plan").filter({ hasText: span });

test("a form row with Join by rule on draws what the rule takes: its command, the team's folder, and the condition under the pointer", () => onTeams(async (p) => {
  await p.locator('[data-act="new"]').first().click();
  await p.locator("#t-name").fill("Crew");
  await p.locator("#t-repo").fill("/work/website");
  const row = p.locator(".slot-row").first();
  await row.locator('[data-what="head"]').click();
  await p.locator('.pick-menu button[data-v="alpha"]').click();
  await row.getByRole("button", { name: "Join by rule" }).click();
  const rule = row.locator(".rulemark");
  await expect(rule.locator(".kicker")).toHaveText("Joins by rule");
  await expect(rule.locator(".hcmd")).toHaveText("claude-alpha");
  await expect(rule.locator(".where")).toHaveText("website");
  await expect(rule.locator(".where")).toHaveAttribute("title", "/work/website");
  await expect(rule).toHaveAttribute("title", /only while the member has none running/i);
  // the folder it watches is the one the form names now: typed after the toggle, it follows
  await p.locator("#t-repo").fill("/work/shop");
  await expect(rule.locator(".where")).toHaveText("shop");
  await expect(rule.locator(".where")).toHaveAttribute("title", "/work/shop");
  // turned off, the row no longer carries the rule's mark
  await row.getByRole("button", { name: "Join by rule" }).click();
  await expect(row.locator(".rulemark")).toHaveCount(0);
}), 60_000);

test("a member's own plan figures are headed 7 days, and the plan's long window is named on its meter row with its share", () => onTeams(async (p) => {
  const lead = member(p, "lead");
  // the member's own week, under the span it was counted over, never under the plan's month
  await expect(spanRow(lead, "7 days").locator(".rf")).toHaveText("402");
  await expect(spanRow(lead, "7 days")).not.toContainText(/month/i);
  // the plan's month is the plan's meter row, with the share the provider reported
  await expect(rowOf(lead, /^month$/i)).toContainText(/40%\s*used/);
  await expect(lead).toContainText("Work login");
  // a login /api/accounts keys by the ref its pool entry carries is still found: named, and its week drawn
  const builder = member(p, "builder");
  await expect(spanRow(builder, "7 days").locator(".rf")).toHaveText("193");
  await expect(rowOf(builder, /^week$/i)).toContainText(/15%\s*used/);
  await expect(builder).toContainText("Home login");
}), 60_000);
