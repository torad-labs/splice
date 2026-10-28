// Marlin's trust ruling of Oct 12, in fin's words and hitstop's drawing (da3d8b2), through the real Teams page against a
// stub daemon answering in the routes' own shapes (TeamStart, TeamScreen, SessionDrive.offered, Refusals.reply). A member
// stopped at Claude Code's folder trust question reads Needs you with the client's question, its folder (a chip naming the
// last two folders, the whole path under the pointer) and its two options verbatim, each leading with the number the client
// gives it (Teams' goal, Oct 28: numbered answer rows); nothing is answered until a click, and the click sends the choice
// with its label; a start that timed out with nothing asked reads Not started, its screen behind the Last screen door,
// never the daemon's sentence.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
type Row = Record<string, unknown>;
const proj = { id: "/work/website", name: "website", label: "website", source: "folder", folder: "/work" };
// the 2.1.296 trust panel as SessionDrive.offered serves it: blank lines dropped, the terminal's wrap kept
const TRUST = {
  session_id: "ffffffff-1", asked: "Security guide", draft: "",
  panel: [
    { text: "Accessing workspace:" }, { text: "/work/website" },
    { text: "Quick safety check: Is this a project you created or one you trust? (Like your own code, a well-known open source" },
    { text: "project, or work from your team). If not, take a moment to review what's in this folder first." },
    { text: "Claude Code'll be able to read, edit, and execute files here." }, { text: "Security guide" },
  ],
  choices: [{ choice: 1, label: "No, exit", here: true }, { choice: 2, label: "Yes, I trust this folder", here: false }],
};
const TAIL = "Claude Code v2.1.296\n~/work/website\n\nSessionStart:startup hook error\nFailed with non-blocking status code: /bin/sh: 1: bun: not found";
const DAEMON_SENTENCE = "claude-alpha did not register within 45 s";

let teams: Row[] = [], sessions: Row[] = [], start: { status: number; body: Row } = { status: 200, body: {} };
const answered: Row[] = [];
const team = (session: string | null) => [{ id: "t1", name: "Site crew", repo: "/work/website", project: proj,
  slots: [{ id: "lead", role: "Lead", head: "alpha", lead: true, session: null, project: proj },
    { id: "reviewer", role: "reviewer", head: "alpha", lead: false, session, project: proj }] }];
const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/sessions") return Response.json({ sessions, reading: true });
    if (path === "/api/teams") return Response.json({ teams });
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "alpha" }] });
    if (path === "/api/teams/t1/slots/reviewer/screen") return Response.json(TRUST);
    if (path === "/api/teams/t1/slots/reviewer/answer") {
      answered.push(await req.json() as Row);
      return Response.json({ session_id: "ffffffff-1" });
    }
    if (path === "/api/teams/t1/slots/reviewer/start") {
      // a terminal that closed gives its slot back by the time the daemon says so, as the page's next read finds it
      if (start.body.reason === "closed") teams = team(null);
      return Response.json(start.body, { status: start.status });
    }
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
// SHOTS=<dir> keeps a capture of each state at Marcos's width in dark and at 1440 in both themes, for the seat's own look
async function open(width = 1440, theme = "night"): Promise<Page> {
  const ctx = await browser.newContext({ viewport: { width, height: 1100 } });
  await ctx.addInitScript((t) => localStorage.setItem("splice-console-theme", t), theme);
  const p = await ctx.newPage();
  await p.goto(`http://127.0.0.1:${server.port}/teams.html`);
  return p;
}
const reviewer = (p: Page) => p.locator('article[data-key="m:reviewer"]');
async function shoot(name: string, check: (p: Page) => Promise<void>) {
  const dir = process.env.SHOTS;
  const setups: [number, string][] = dir ? [[3828, "night"], [1440, "night"], [1440, "day"]] : [[1440, "night"]];
  for (const [width, theme] of setups) {
    const p = await open(width, theme);
    try {
      await check(p);
      if (dir) {
        mkdirSync(dir, { recursive: true, mode: 0o700 });
        await reviewer(p).scrollIntoViewIfNeeded();
        await p.screenshot({ path: join(dir, `teams-${name}-${width}-${theme}.png`) });
      }
    } finally {
      await p.context().close();
    }
  }
}

test("a member stopped at the trust question reads Needs you, with the client's question, folder and options verbatim", async () => {
  teams = team("ffffffff-1"); sessions = [];
  await shoot("trust", async (p) => {
    const card = reviewer(p);
    await expect(card.locator(".word")).toHaveText("Needs you", { timeout: 20_000 });
    await expect(card).toHaveClass(/\bneeds\b/);
    await expect(card.locator(".ask .q")).toHaveText("Quick safety check: Is this a project you created or one you trust?");
    await expect(card.locator(".ask .folder .where")).toHaveText("work/website");
    await expect(card.locator(".ask .folder .where")).toHaveAttribute("title", "/work/website");
    await expect(card.locator(".answers .act .n")).toHaveText(["1", "2"]);
    await expect(card.locator(".answers .act span")).toHaveText(["No, exit", "Yes, I trust this folder"]);
    await expect(card).not.toContainText("Accessing workspace");
    await expect(card).not.toContainText("Security guide");
    bunExpect(answered).toHaveLength(0);
  });
}, 120_000);

test("Yes sends its own choice and label, then the start goes on and the member comes up", async () => {
  teams = team("ffffffff-1"); sessions = []; answered.length = 0;
  start = { status: 200, body: { session_id: "ffffffff-1" } };
  const p = await open();
  try {
    await reviewer(p).locator(".answers .act", { hasText: "Yes, I trust this folder" }).click({ timeout: 20_000 });
    sessions = [{ session_id: "ffffffff-1", name: "reviewer", head: "alpha", availability: "live", status: "idle", cwd: "/work/website", updated_at: new Date().toISOString(), project: proj }];
    await expect(reviewer(p).locator(".word")).not.toHaveText(/Needs you|Not started/, { timeout: 20_000 });
    await expect(reviewer(p).locator(".ask")).toHaveCount(0);
    bunExpect(answered).toEqual([{ choice: 2, label: "Yes, I trust this folder" }]);
  } finally {
    await p.context().close();
  }
}, 60_000);

test("No closes the terminal: the member reads Not started and Terminal closed, with its folder kept on the card", async () => {
  teams = team("ffffffff-1"); sessions = []; answered.length = 0;
  start = { status: 409, body: { error: "that slot's terminal has closed", reason: "closed" } };
  await shoot("no", async (p) => {
    await reviewer(p).locator(".answers .act", { hasText: "No, exit" }).click({ timeout: 20_000 });
    await expect(reviewer(p).locator(".word")).toHaveText("Not started", { timeout: 20_000 });
    await expect(reviewer(p).locator(".folder .where")).toHaveText("work/website");
    await expect(reviewer(p).locator(".folder .where")).toHaveAttribute("title", "/work/website");
    // why it did not come up, in the console's words, never the daemon's sentence (marlin p223, fin, Oct 13)
    await expect(reviewer(p)).toContainText("Terminal closed");
    await expect(reviewer(p)).not.toContainText("has closed");
    teams = team("ffffffff-1");
  });
}, 120_000);

test("a start that timed out with nothing asked reads Not started, its screen behind Last screen, never the daemon's sentence", async () => {
  start = { status: 504, body: { error: DAEMON_SENTENCE, screen: TAIL } };
  teams = team(null); sessions = [];
  await shoot("notstarted", async (p) => {
    await reviewer(p).locator(".slot-acts .act", { hasText: "Start new" }).click({ timeout: 20_000 });
    const card = reviewer(p);
    await expect(card.locator(".word")).toHaveText("Not started", { timeout: 20_000 });
    await expect(card).not.toContainText(DAEMON_SENTENCE);
    await expect(card.locator("pre.screen")).toHaveCount(0);
    const door = card.locator(".slot-acts .act", { hasText: "Last screen" });
    await expect(door).toHaveAttribute("aria-expanded", "false");
    await door.click();
    await expect(card.locator("pre.screen")).toHaveText(TAIL);
    await expect(door).toHaveAttribute("aria-expanded", "true");
  });
}, 120_000);
