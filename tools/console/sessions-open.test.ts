// An open session on the Sessions board (Night Deck): its conversation read from its transcript, the client's own texts
// (a compaction's summary, a skill it loaded) as one quiet line each that opens in place, the question it waits on as a
// numbered answer pressed for it on splice, and the steering sent where the console sends it. A link to one session
// (sessions.html#<id>) opens it however slow the first read is; one splice cannot find, or whose conversation it cannot
// read, says so in the console's words with the daemon's own kept off the page. Through the real page against a stub
// daemon with made-up rows. (Rewritten from sessions-conversation, sessions-notecard, sessions-door-slow and
// transcript-gone's Sessions cases, which pinned the old page's pane.)
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { expect, type Browser, type Page } from "@playwright/test";
import { launch, stub, type Stub } from "./deck-stub.ts";

const MIN = 60_000;
const DAEMON_WORDS = "java.nio.file.AccessDeniedException: /made/up/projects/x.jsonl";
const row = (id: string, name: string, over: Record<string, unknown> = {}) => ({
  session_id: id, name, head: "acme", route: "head", availability: "live", status: "idle", cwd: `/work/harbor/${name}`,
  last: { role: "assistant", tool: null, text: `${name} finished a step.`, ts: Date.now() - MIN },
  updated_at: new Date(Date.now() - 2 * MIN).toISOString(), status_updated_at: Date.now() - 2 * MIN,
  project: { id: "/work/harbor", name: "harbor", label: "harbor" }, ...over,
});
const SESSIONS = [
  row("s-talk", "chart-writer", { status: "working" }),
  row("s-ask", "chart-checker", {
    waiting_for: "input needed",
    last: { role: "assistant", tool: null, text: "Which tide table?", ts: Date.now() - MIN, asks: [{ question: "Which tide table?", options: ["The new one", "The old one"] }] },
  }),
  row("s-unread", "chart-reader", { status: "working" }),
];
const LOG = {
  messages: [
    { index: 0, role: "user", text: "Conversation compacted. The summary: zebra tail detail of what came before.", ts: Date.now() - 9 * MIN },
    { index: 1, role: "user", text: "Read the tide table and fix the parser.", ts: Date.now() - 8 * MIN },
    { index: 2, role: "assistant", tool: "Bash", text: JSON.stringify({ description: "Run the parser tests", command: "bun test parser" }), ts: Date.now() - 7 * MIN },
    { index: 3, role: "tool", tool: "Bash", text: "4 pass", ts: Date.now() - 7 * MIN },
    { index: 4, role: "assistant", text: "The parser reads the new column now.", ts: Date.now() - 6 * MIN },
  ],
  earlier: null,
};
let slow = 0;

let s: Stub;
let browser: Browser;
beforeAll(async () => {
  s = stub({
    "/api/heads": { heads: [{ key: "acme", label: "claude-acme", gate: {} }, { key: "globex", label: "claude-globex", gate: {} }] },
    "/api/status": { registry: [{ key: "acme", label: "claude-acme", family: "anthropic" }, { key: "globex", label: "claude-globex", family: "openai" }] },
    "/api/models": { heads: [{ head: "acme", models: [{ id: "acme-1", label: "Acme 1" }] }, { head: "globex", models: [{ id: "globex-2", label: "Globex 2" }] }] },
    "/api/sessions": async () => { if (slow) await Bun.sleep(slow); return { reading: true, sessions: SESSIONS }; },
    "/api/teams": { teams: [] },
    "/api/sessions/s-talk/transcript": LOG,
    "/api/sessions/s-ask/transcript": { messages: [], earlier: null },
    "/api/sessions/s-unread/transcript": () => Response.json({ error: DAEMON_WORDS }, { status: 500 }),
    "/api/sessions/history": { sessions: [] },
    "/api/sessions/nowhere0-0000/transcript": () => Response.json({ error: "gone" }, { status: 404 }),
  });
  browser = await launch();
}, 30_000);
afterAll(async () => {
  await browser?.close();
  s?.stop();
});

async function at(hash: string, width = 1440): Promise<Page> {
  const page = await browser.newPage({ viewport: { width, height: 900 } });
  await page.goto(`${s.url}/sessions.html${hash}`);
  return page;
}
const posts = (path: string) => s.calls.filter((c) => c.method === "POST" && c.path === path).map((c) => c.body);

test("the conversation reads from its transcript, a compaction's summary one line that opens in place, the tool calls folded", async () => {
  const page = await at("#s-talk");
  try {
    const thread = page.locator("#thread");
    await expect(thread).toContainText("The parser reads the new column now.", { timeout: 10_000 });
    await expect(thread).not.toContainText("zebra tail detail", { useInnerText: true });
    await thread.getByRole("button", { name: /Summary of the earlier conversation/ }).click();
    await expect(thread).toContainText("zebra tail detail", { useInnerText: true });
    await expect(thread.locator(".cv-run-sum", { hasText: "1 tool call · Ran" })).toHaveCount(1);
  } finally {
    await page.close();
  }
}, 30_000);

test("the question a session waits on is a numbered answer, and pressing 2 sends that choice to splice", async () => {
  const page = await at("#s-ask");
  try {
    const ask = page.locator("#dock .ask");
    await expect(ask).toContainText("Which tide table?", { timeout: 10_000 });
    await ask.focus();
    await page.keyboard.press("2");
    await expect.poll(() => posts("/api/sessions/s-ask/answer")).toEqual([{ choice: 2 }]);
  } finally {
    await page.close();
  }
}, 30_000);

test("Stop, Continue on and Back each do their one move", async () => {
  const page = await at("#s-talk");
  try {
    await expect(page.locator("#conv")).toBeVisible({ timeout: 10_000 });
    await page.locator("#stStop").click();
    await expect.poll(() => posts("/api/sessions/s-talk/stop").length).toBe(1);
    await page.locator("#stModel").click();
    await page.locator(".pk-row", { hasText: "claude-globex" }).click();
    await page.locator(".pk-row", { hasText: "Globex 2" }).click();
    await expect.poll(() => posts("/api/sessions/s-talk/continue")).toEqual([{ head: "globex", model: "globex-2" }]);
    await page.locator("#back").click();
    await expect(page.locator("#board")).toBeVisible();
    bunExpect(new URL(page.url()).hash).toBe("");
  } finally {
    await page.close();
  }
}, 30_000);

test("a link to one session opens it, however slow the first read of the list is", async () => {
  slow = 2_500;
  const page = await at("#s-talk", 3840);
  try {
    await page.waitForTimeout(1_000);
    bunExpect(new URL(page.url()).hash).toBe("#s-talk");
    await expect(page.locator("#title")).toHaveText("chart-writer", { timeout: 10_000 });
  } finally {
    slow = 0;
    await page.close();
  }
}, 30_000);

// Nov 21, 2026: the page's words for a transcript splice could not read are "Transcript did not load"; this test still
// looked for "Not read", which the page stopped saying before the lane was merged.
test("a session splice cannot find opens on No transcript, an unreadable one on Transcript did not load, and the daemon's words stay off the page", async () => {
  const page = await at("#nowhere0-0000");
  const warned: string[] = [];
  page.on("console", (m) => { if (m.type() === "warning") warned.push(m.text()); });
  try {
    await expect(page.locator("#thread")).toContainText("No transcript", { timeout: 10_000 });
    await page.locator("#back").click();
    await expect(page.locator("#board .srow", { hasText: "chart-writer" })).toBeVisible();
    await page.goto("about:blank");
    await page.goto(`${s.url}/sessions.html#s-unread`);
    await expect(page.locator("#thread")).toContainText("Transcript did not load", { timeout: 10_000 });
    await expect(page.locator("body")).not.toContainText("AccessDenied");
    await expect.poll(() => warned.some((w) => w.includes("AccessDeniedException"))).toBe(true);
  } finally {
    await page.close();
  }
}, 40_000);
