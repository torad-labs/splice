// A session's conversation as he reads it on Sessions (round 2's bug list, Oct 27): a link the agent wrote is a link,
// and a link to a file names the file and nothing else; a text the client puts into the conversation (the summary of
// a compaction, a skill it loaded, a delivery notice) is one line in plain words that opens in place, with no id or
// socket path on that line; and Show newest, after a door opened the conversation at a moment, leaves no moment in the
// address. Through the real Sessions page against a stub daemon.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const MIN = 60_000;
const session = {
  session_id: "s-conv", name: "reader", head: "alpha", route: "head", availability: "live", status: "idle", cwd: "/work/desk/app",
  project: { id: "/work/desk", name: "desk", label: "desk", source: "folder", folder: "/work" },
  last: { role: "assistant", tool: null, text: "See the guide.", ts: now - MIN }, updated_at: new Date(now - MIN).toISOString(),
};
const messages = [
  { index: 0, role: "user", text: "Read the guide and tell me what it says.", ts: now - 9 * MIN },
  { index: 1, role: "system", ts: now - 8 * MIN,
    text: "This session is being continued from a previous conversation that ran out of context. The summary below covers the earlier portion of the conversation.\n\nSummary:\n1. The zebra tail detail was settled." },
  { index: 2, role: "system", ts: now - 7 * MIN, text: "Base directory for this skill: /home/me/.claude/skills/review-pr\n\n# Review a pull request\nRead the diff first." },
  { index: 3, role: "system", ts: now - 6 * MIN,
    text: "[Cross-session delivery notice] Your message to \"keeper\" was delivered to uds:/run/user/1000/cc-socks/1f2e3d4c.sock and is waiting." },
  { index: 4, role: "assistant", ts: now - 5 * MIN,
    text: "The guide is at [the guide](https://example.com/guide), and the code is in [sessions.js](/work/desk/app/src/sessions.js)." },
];
const transcript = { messages, path: "/home/me/.claude/projects/desk/s-conv.jsonl", earlier: null, moves: [], unmoved: [], unparseable_lines: 0 };
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/sessions") return Response.json({ reading: true, sessions: [session] });
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-alpha" }] });
    if (path === "/api/models") return Response.json({ heads: [] });
    if (path === "/api/sessions/s-conv/transcript") return Response.json(transcript);
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
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

test("a link the agent wrote is a link, and a link to a file names only the file", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/sessions.html#s-conv`);
    const log = page.locator(".pane .log");
    await expect(log.locator('a[href="https://example.com/guide"]')).toHaveText("the guide", { timeout: 15_000 });
    await expect(log).toContainText("sessions.js");
    await expect(log).not.toContainText("](");
    await expect(log).not.toContainText("/work/desk/app/src");
  } finally {
    await page.close();
  }
}, 40_000);

test("what the client put into the conversation is one plain line each, opening in place", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/sessions.html#s-conv`);
    const log = page.locator(".pane .log");
    await expect(log).toContainText("review-pr", { timeout: 15_000 });
    await expect(log).not.toContainText("Base directory");
    await expect(log).not.toContainText("uds:");
    await expect(log).not.toContainText("zebra tail detail");
    await log.getByRole("button", { name: /Summary of the earlier conversation/ }).click();
    await expect(log).toContainText("zebra tail detail");
  } finally {
    await page.close();
  }
}, 40_000);

test("Show newest after a door's moment leaves no moment in the address", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/sessions.html?at=${now - 6 * MIN}#s-conv`);
    const newest = page.locator(".pane .log").getByRole("button", { name: "Show newest" });
    await newest.click({ timeout: 15_000 });
    await expect(newest).toHaveCount(0, { timeout: 10_000 });
    const url = new URL(page.url());
    bunExpect(url.searchParams.get("at")).toBeNull();
    bunExpect(url.hash).toBe("#s-conv");
  } finally {
    await page.close();
  }
}, 40_000);
