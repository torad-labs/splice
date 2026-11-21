// Teams (Night Deck): each live team is a band, its members a map with today's messages on the wires; a member's glass
// changes its role, model, login and instructions on the routes the console has always used, asks before it stops a
// session, and answers the question a member waits on, a folder trust question read off its screen included, with the
// client's own option number and words; a team ends only once asked, its sessions ended and the team archived; the
// sessions on no team become a team. Through the real page against a stub daemon with made-up rows. (Rewritten from
// teams-trust and account-reads' Teams case; teams-restored and teams-walk pinned the old page's form rows, bars and
// tags, which the approved page does not have.)
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { expect, type Browser, type Page } from "@playwright/test";
import { launch, stub, type Stub } from "./deck-stub.ts";

const MIN = 60_000, NOW = Date.now();
const proj = { id: "/work/harbor", name: "harbor", label: "harbor" };
const session = (id: string, name: string, over: Record<string, unknown> = {}) => ({
  session_id: id, name, head: "claude", route: "head", availability: "live", status: "idle", cwd: "/work/harbor",
  repo: { root: "/work/harbor" }, project: proj, account: "work", last: { role: "assistant", text: `${name} finished a step.`, ts: NOW - MIN },
  status_updated_at: NOW - 2 * MIN, ...over,
});
const SESSIONS = [
  session("s-lead", "chart-lead", { status: "working" }),
  session("s-build", "chart-builder"),
  session("s-loose", "tide-reader"),
];
const TEAM = {
  id: "t1", name: "harbor-crew", goal: "Chart the harbor", repo: "/work/harbor", archived: false,
  slots: [
    { id: "lead", role: "Lead", head: "claude", lead: true, session: "s-lead", instructions: "Plan first." },
    { id: "builder", role: "Builder", head: "claude", lead: false, session: "s-build" },
    { id: "reviewer", role: "Reviewer", head: "claude", lead: false, session: "s-trust" },
  ],
};
const ENDED = { id: "t9", name: "old-dock", goal: "", repo: "/work/dock", archived: true, slots: [], updated_epoch_millis: NOW - 3 * 86_400_000 };
// the trust panel as SessionDrive.offered serves it, for a start that stopped before its session registered
const TRUST = {
  asked: "Security guide",
  panel: [{ text: "Accessing workspace:" }, { text: "/work/harbor" }, { text: "Quick safety check: Is this a project you created or one you trust? (Like your own code)" }],
  choices: [{ choice: 1, label: "No, exit" }, { choice: 2, label: "Yes, I trust this folder" }],
};
const week = (turns: number, cost: number) => ({ turns, tokens: { input: 0, cache_read: 0, cache_write: 0, output: turns * 100 }, cost_usd: cost });

let s: Stub;
let browser: Browser;
beforeAll(async () => {
  s = stub({
    "/api/heads": { heads: [{ key: "claude", label: "claude-splice", gate: {} }, { key: "codex", label: "codex", gate: {} }] },
    "/api/status": { registry: [{ key: "claude", label: "claude-splice", family: "anthropic" }, { key: "codex", label: "codex", family: "openai" }] },
    "/api/models": { heads: [{ head: "claude", models: [{ id: "made-up-opus", label: "Made Opus" }, { id: "made-up-sonnet", label: "Made Sonnet" }] }, { head: "codex", models: [{ id: "made-up-gpt", label: "Made GPT" }] }] },
    "/api/sessions": { reading: true, sessions: SESSIONS },
    "/api/teams": { teams: [TEAM, ENDED] },
    "/api/accounts": { accounts: [
      { label: "work", display_name: "Work login", heads: ["claude"], provider: "anthropic", kind: "client", seven_day_used_percent: 94 },
      { label: "home", display_name: "Home login", heads: ["claude"], provider: "anthropic", kind: "client", seven_day_used_percent: 12 },
    ] },
    "/api/teams/t1/economics": { slots: [{ slot: "lead", week: week(1200, 40) }, { slot: "builder", week: week(300, 10) }, { slot: "reviewer", week: week(0, 0) }] },
    "/api/teams/t1/chat": { state: "on", messages: [
      { at: NOW - 30 * MIN, from_slot: "lead", to_slot: "builder", text: "Take the tide table." },
      { at: NOW - 20 * MIN, from_slot: "builder", to_slot: "lead", text: "Done with the table." },
    ] },
    "/api/teams/t1/slots/reviewer/screen": TRUST,
    "PUT /api/teams": { id: "t2" },
  });
  browser = await launch();
}, 30_000);
afterAll(async () => {
  await browser?.close();
  s?.stop();
});

async function at(): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  await page.goto(`${s.url}/teams.html`);
  await expect(page.locator('.band[data-team="t1"] .mb')).toHaveCount(3, { timeout: 10_000 });
  return page;
}
const glass = (page: Page) => page.locator(".lens:not([hidden])");
const member = (page: Page, slot: string) => page.locator(`.band[data-team="t1"] [data-member="${slot}"]`);
const sent = (method: string, path: string) => s.calls.filter((c) => c.method === method && c.path === path).map((c) => c.body);
const lr = (page: Page, name: string) => glass(page).locator(".lr").filter({ has: page.locator(".lr-nm", { hasText: name }) });

test("a team's band carries its week, its lead's login and today's messages on the wire; ended teams are not bands", async () => {
  const page = await at();
  try {
    const band = page.locator('.band[data-team="t1"]');
    await expect(band.locator(".tc")).toContainText("1.5k");
    await expect(band.locator(".tc")).toContainText("$50");
    await expect(band.locator(".tc-login")).toContainText("Work login");
    await expect(band.locator(".tc-login")).toContainText("94% of week");
    await expect(page.locator('.band[data-team="t9"]')).toHaveCount(0);
    await band.locator(".msg").first().click();
    await expect(glass(page).locator("h3")).toHaveText("Messages today");
    await expect(glass(page)).toContainText("Take the tide table.");
    await expect(glass(page)).toContainText("Done with the table.");
  } finally {
    await page.close();
  }
}, 30_000);

test("a member's glass changes its role, model, login and instructions through the team's routes", async () => {
  const page = await at();
  try {
    await member(page, "builder").click();
    await lr(page, "Role").getByRole("radio", { name: "Reviewer" }).click();
    await expect.poll(() => sent("PUT", "/api/teams/t1").map((b) => (b as { slots: { id: string; role: string }[] }).slots.find((x) => x.id === "builder")?.role)).toEqual(["Reviewer"]);
    await lr(page, "Model").getByRole("radio", { name: "Made Sonnet" }).click();
    await expect.poll(() => sent("PUT", "/api/teams/t1").map((b) => (b as { slots: { id: string; model?: string }[] }).slots.find((x) => x.id === "builder")?.model).pop()).toBe("made-up-sonnet");
    // the login pick offers this command's own logins, by the names Accounts gives them
    await lr(page, "Login").locator(".pickbtn").click();
    const offered = (await page.locator(".menu:not([hidden]) button[role=menuitem]").allInnerTexts()).join(" | ");
    bunExpect(offered).toContain("Work login");
    bunExpect(offered).toContain("Home login");
    await page.locator(".menu:not([hidden]) button", { hasText: "Home login" }).click();
    await expect.poll(() => sent("PUT", "/api/teams/t1").map((b) => (b as { slots: { id: string; account?: string }[] }).slots.find((x) => x.id === "builder")?.account).pop()).toBe("home");
    await glass(page).locator("textarea").fill("Run the checks first.");
    await lr(page, "Instructions").getByRole("button", { name: "Save" }).click();
    await expect.poll(() => sent("PUT", "/api/teams/t1/slots/builder/instructions")).toEqual([{ instructions: "Run the checks first." }]);
  } finally {
    await page.close();
  }
}, 40_000);

// Rewritten Nov 19, 2026: stopping and ending are one action (the owner's law of that day); the row says beforehand
// what a press stops, and no ask stands between the press and the act.
test("Stop says whose session it ends and stops it on its one press", async () => {
  const page = await at();
  try {
    await member(page, "builder").click();
    await expect(glass(page)).toContainText("Stop ends chart-builder's session");
    await glass(page).getByRole("button", { name: "Stop", exact: true }).click();
    await expect.poll(() => sent("POST", "/api/teams/t1/slots/builder/stop").length).toBe(1);
  } finally {
    await page.close();
  }
}, 30_000);

test("a member stopped at the folder trust question needs you, and its answer is the client's own number and words", async () => {
  const page = await at();
  try {
    await expect(member(page, "reviewer")).toHaveClass(/is-needs/);
    await expect(member(page, "reviewer")).toContainText("Quick safety check: Is this a project you created or one you trust?");
    await member(page, "reviewer").click();
    await expect(glass(page).locator(".ask .opt")).toHaveText([/No, exit/, /Yes, I trust this folder/]);
    bunExpect(sent("POST", "/api/teams/t1/slots/reviewer/answer")).toHaveLength(0);
    await glass(page).locator(".ask .opt", { hasText: "Yes, I trust this folder" }).click();
    await expect.poll(() => sent("POST", "/api/teams/t1/slots/reviewer/answer")).toEqual([{ choice: 2, label: "Yes, I trust this folder" }]);
  } finally {
    await page.close();
  }
}, 30_000);

test("End team says how many sessions it stops, and on its one press ends them and archives the team", async () => {
  const page = await at();
  try {
    await page.locator('.tc[data-team="t1"]').click();
    await expect(glass(page)).toContainText("Stops 3 sessions");
    await glass(page).getByRole("button", { name: "End team" }).click();
    await expect.poll(() => sent("POST", "/api/teams/t1/end")).toEqual([{ confirm: true }]);
    await expect.poll(() => sent("POST", "/api/teams/t1/archive").length).toBe(1);
  } finally {
    await page.close();
  }
}, 30_000);

test("the sessions on no team are counted and become a team; an ended team is restored from its list", async () => {
  const page = await at();
  try {
    await expect(page.locator('[data-open="loose"]')).toContainText("1");
    await page.locator('[data-open="loose"]').click();
    await glass(page).locator(".pick", { hasText: "tide-reader" }).click();
    await glass(page).locator("input").fill("tide-crew");
    await glass(page).getByRole("button", { name: "Make a team of 1" }).click();
    await expect.poll(() => sent("PUT", "/api/teams").map((b) => (b as { name: string }).name)).toEqual(["tide-crew"]);
    await expect.poll(() => sent("PUT", "/api/teams/t2/sessions")).toEqual([{ bindings: { lead: "s-loose" } }]);
    await page.locator('[data-open="ended"]').click();
    await expect(glass(page)).toContainText("old-dock");
    await glass(page).getByRole("button", { name: "Restore" }).click();
    await expect.poll(() => sent("POST", "/api/teams/t9/restore").length).toBe(1);
  } finally {
    await page.close();
  }
}, 30_000);
