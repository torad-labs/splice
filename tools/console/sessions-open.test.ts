// An open session on the Sessions board (Night Deck): its conversation drawn by the shared session reader (reply-view)
// from its transcript and its live feed, a compaction's summary as one quiet line that opens in place, each tool call as
// one closed line, a reply that is being written streaming in, earlier messages one press away, and a line above what
// is new since the person last looked, with the newest line resting above whatever floats over the foot of the screen
// (the composer, and on a phone the switch above it) while a reply grows; the question it waits on as a numbered
// answer pressed for it on splice, and the steering sent where the console sends it. A link to one session
// (sessions.html#<id>) opens it however slow the first read is; one splice cannot find, or whose conversation it cannot
// read, says so in the console's words with the daemon's own kept off the page. Through the real page against a stub
// daemon with made-up rows. (Rewritten from sessions-conversation, sessions-notecard, sessions-door-slow and
// transcript-gone's Sessions cases, which pinned the old page's pane. Nov 21, 2026: the conversation's own cases are
// rewritten for the reader, which replaced the page's own drawing of messages and folded tool runs.)
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
  row("s-live", "map-drawer", { status: "working" }),
  row("s-long", "log-keeper"),
  row("s-grow", "report-writer", { status: "working" }),
];
// a feed that says hello, starts a reply and writes one whole line of it, then stays open as a live one does
const FEED = [
  ["hello", { session: "s-live", live: ["t1"] }],
  ["turn", { turn: "t1", message: "msg_live1", agent: null, model: "acme-1", head: "acme", compact: false, at: Date.now() }],
  ["text", { turn: "t1", index: 0, delta: "The north pier label moved.\n\n" }],
] as const;
const feed = () => new Response(new ReadableStream({
  start(c) { for (const [event, data] of FEED) c.enqueue(new TextEncoder().encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)); },
}), { headers: { "content-type": "text/event-stream", "cache-control": "no-store" } });
// a reply longer than any screen here, written a paragraph at a time
const growing = () => new Response(new ReadableStream({
  async start(c) {
    const say = (event: string, data: unknown) => c.enqueue(new TextEncoder().encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
    say("hello", { session: "s-grow", live: ["t9"] });
    say("turn", { turn: "t9", message: "msg_grow", agent: null, model: "acme-1", head: "acme", compact: false, at: Date.now() });
    for (let i = 1; i <= 40; i++) {
      await Bun.sleep(30);
      say("text", { turn: "t9", index: 0, delta: `Paragraph ${i} of the long report about the harbor chart.\n\n` });
    }
  },
}), { headers: { "content-type": "text/event-stream", "cache-control": "no-store" } });
// a conversation longer than its newest page: two messages from long ago, then two since the person last looked
const NEWEST = { messages: [
  { index: 2, role: "user", text: "An old question.", ts: Date.now() - 50 * MIN },
  { index: 3, role: "assistant", text: "An old answer.", ts: Date.now() - 49 * MIN, message: "msg_o" },
  { index: 4, role: "user", text: "The newest question.", ts: Date.now() - 2 * MIN },
  { index: 5, role: "assistant", text: "The newest answer.", ts: Date.now() - MIN, message: "msg_n" },
], earlier: "c1" };
const OLDER = { messages: [
  { index: 0, role: "user", text: "The first thing asked.", ts: Date.now() - 90 * MIN },
  { index: 1, role: "assistant", text: "The first answer.", ts: Date.now() - 89 * MIN, message: "msg_f" },
], earlier: null };
const LOG = {
  messages: [
    { index: 0, role: "system", text: "The summary: zebra tail detail of what came before.", compact: true, ts: Date.now() - 9 * MIN },
    { index: 1, role: "user", text: "Read the tide table and fix the parser.", ts: Date.now() - 8 * MIN },
    { index: 2, role: "assistant", tool: "Bash", tool_use_id: "toolu_1", text: JSON.stringify({ description: "Run the parser tests", command: "bun test parser" }), ts: Date.now() - 7 * MIN },
    { index: 3, role: "tool", tool: "Bash", tool_use_id: "toolu_1", text: "4 pass", ts: Date.now() - 7 * MIN },
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
    "/api/sessions/s-live/transcript": (url: URL) => (url.searchParams.get("before") === "end"
      ? { messages: [{ index: 0, role: "user", text: "Fix the harbor map.", ts: Date.now() - 2 * MIN }], earlier: null } : { messages: [] }),
    "/api/sessions/s-live/live": () => feed(),
    "/api/sessions/s-grow/transcript": (url: URL) => (url.searchParams.get("before") === "end"
      ? { messages: [{ index: 0, role: "user", text: "Write the long report.", ts: Date.now() - MIN }], earlier: null } : { messages: [] }),
    "/api/sessions/s-grow/live": () => growing(),
    "/api/sessions/s-long/transcript": (url: URL) => (url.searchParams.get("before") === "end" ? NEWEST
      : url.searchParams.get("before") === "c1" ? OLDER : { messages: [] }),
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

test("the conversation is the reader's: a compaction's summary opens in place, and a tool call is one line that opens to its result", async () => {
  const page = await at("#s-talk");
  try {
    const thread = page.locator("#thread .rv");
    await expect(thread).toContainText("The parser reads the new column now.", { timeout: 10_000 });
    await expect(thread).not.toContainText("zebra tail detail", { useInnerText: true });
    await thread.locator("summary", { hasText: "Conversation compacted" }).click();
    await expect(thread).toContainText("zebra tail detail", { useInnerText: true });
    const call = thread.locator("details.rv-tool");
    await expect(call).toHaveCount(1);
    await expect(call.locator("summary")).toContainText("Bash");
    await expect(thread).not.toContainText("4 pass", { useInnerText: true });
    await call.locator("summary").first().click();
    await expect(thread).toContainText("4 pass", { useInnerText: true });
  } finally {
    await page.close();
  }
}, 30_000);

test("a reply that is being written streams into the open conversation", async () => {
  const page = await at("#s-live");
  try {
    const thread = page.locator("#thread .rv");
    await expect(thread).toContainText("Fix the harbor map.", { timeout: 10_000 });
    await expect(thread.locator(".rv-turn")).toContainText("The north pier label moved.", { timeout: 10_000 });
    bunExpect(s.calls.some((c) => c.method === "GET" && c.path === "/api/sessions/s-live/live")).toBe(true);
  } finally {
    await page.close();
  }
}, 30_000);

test("earlier messages are one press away, and a line stands above what is new since the person last looked", async () => {
  const page = await at("#s-long");
  try {
    const thread = page.locator("#thread");
    await expect(thread.locator(".rv")).toContainText("The newest answer.", { timeout: 10_000 });
    await expect(thread.locator(".cv-mark.is-new")).toHaveText("New since you last looked · 2");
    await expect(thread).not.toContainText("The first thing asked.");
    await thread.getByRole("button", { name: /Earlier messages/ }).click();
    await expect(thread.locator(".rv")).toContainText("The first thing asked.");
    await expect(thread.getByRole("button", { name: /Earlier messages/ })).toHaveCount(0);
  } finally {
    await page.close();
  }
}, 30_000);

test("while a reply grows past the screen its newest line rests just above the composer, and on a phone above the switch too, under nothing", async () => {
  for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
    const page = await browser.newPage({ viewport });
    try {
      await page.goto(`${s.url}/sessions.html#s-grow`);
      const newest = page.locator("#thread .rv-turn").getByText("Paragraph 40 of the long report");
      await expect(newest).toBeVisible({ timeout: 20_000 });
      // where the line stands against what floats over the foot, and what is drawn on top of its two ends
      const stands = () => newest.evaluate((el) => {
        const r = el.getBoundingClientRect();
        const foot = ["#compWrap", ".switch", ".need-float"].map((q) => document.querySelector(q)?.getBoundingClientRect())
          .filter((b): b is DOMRect => !!b && b.height > 0 && b.top > innerHeight / 2).map((b) => b.top);
        const over = (x: number) => { const top = document.elementFromPoint(x, r.top + r.height / 2); return !!top && (top === el || el.contains(top) || top.contains(el)) && !!top.closest(".rv"); };
        return { above: Math.round(Math.min(...foot) - r.bottom), clear: over(r.left + 2) && over(r.right - 2) };
      });
      await expect.poll(async () => (await stands()).clear, { timeout: 10_000 }).toBe(true);
      const { above } = await stands();
      bunExpect(above).toBeGreaterThanOrEqual(0);
      bunExpect(above).toBeLessThan(160);
    } finally {
      await page.close();
    }
  }
}, 60_000);

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
