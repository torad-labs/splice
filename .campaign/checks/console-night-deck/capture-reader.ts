// Pictures of an open session on the Sessions page now that its conversation is drawn by the shared session reader
// (Nov 21, 2026), for looking at before it is called done: a conversation with a compaction, thinking, tool calls and
// Markdown (at rest, and with a call and the summary opened), a reply being written, earlier messages with the line
// above what is new, a question waited on, and the two words for a conversation that cannot be read. Chromium on the
// Mac it is run on, the stub daemon with a made-up world, night and day, at 1440 and 3840 px and a phone's width.
// Everything shown is made up.
//
//   bun .campaign/checks/console-night-deck/capture-reader.ts --out <folder>
import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { browser, daemon, MIN, NARROW, NOW, open, ROOT, session, WIDE, world } from "./lib/deck.ts";

const out = process.argv[process.argv.indexOf("--out") + 1] || "build/night-deck-captures/reader-here";
const dir = join(ROOT, out);
rmSync(dir, { recursive: true, force: true });
mkdirSync(dir, { recursive: true });
const PHONE = { width: 390, height: 844 };

const call = (index: number, id: string, tool: string, input: Record<string, unknown>, at: number) =>
  ({ index, role: "assistant", tool, tool_use_id: id, text: JSON.stringify(input), ts: NOW - at * MIN, message: "msg_a1", model: "claude-opus-5-5" });
const result = (index: number, id: string, tool: string, text: string, at: number) => ({ index, role: "tool", tool, tool_use_id: id, text, ts: NOW - at * MIN });
const TALK = {
  messages: [
    { index: 0, role: "system", compact: true, ts: NOW - 40 * MIN,
      text: "The summary: the tide table's parser was reading the height column as the time column. The fix is in parse.ts; the tests for the new column are still to write." },
    { index: 1, role: "user", text: "Read the tide table and fix the parser. The new file has a column for the moon's phase and the old tests don't know it.", ts: NOW - 30 * MIN },
    { index: 2, role: "assistant", message: "msg_a1", model: "claude-opus-5-5", ts: NOW - 29 * MIN,
      thinking: "The table has five columns now. The parser splits on two spaces, so a phase of 'new moon' becomes two fields.",
      text: "I'll read the table first, then the parser." },
    call(3, "toolu_1", "Read", { file_path: "/work/harbor/data/tides-2026.txt" }, 29),
    result(4, "toolu_1", "Read", "time   height  phase\n00:14  1.2     new moon\n06:31  4.8     new moon\n12:40  1.0     new moon", 29),
    call(5, "toolu_2", "Bash", { description: "Run the parser tests", command: "bun test parser" }, 28),
    result(6, "toolu_2", "Bash", "3 pass\n1 fail\n  expected 5 columns, read 6", 28),
    call(7, "toolu_3", "Edit", { file_path: "/work/harbor/src/parse.ts", old_string: "line.split('  ')", new_string: "line.split(/\\s{2,}/)" }, 27),
    result(8, "toolu_3", "Edit", "The file /work/harbor/src/parse.ts has been updated.", 27),
    { index: 9, role: "assistant", message: "msg_a2", model: "claude-opus-5-5", ts: NOW - 26 * MIN,
      text: "The parser reads the new column now. What changed:\n\n- **Columns** are split on two or more spaces, so `new moon` stays one field.\n- The **phase** is kept as text, never parsed as a number.\n- One test was wrong and is rewritten.\n\n```ts\nexport function row(line: string): Tide {\n  const [time, height, phase] = line.trim().split(/\\s{2,}/);\n  return { time, height: Number(height), phase };\n}\n```\n\nAll four tests pass. The [tide notes](https://example.invalid/tides) say the phase column may be empty before 2019, and that case reads as `null`." },
    { index: 10, role: "user", text: "Good. Does it still read last year's file?", ts: NOW - 6 * MIN },
    { index: 11, role: "assistant", message: "msg_a3", model: "acme-large-2", ts: NOW - 5 * MIN,
      text: "Yes. Last year's file has four columns, and a row with no phase reads with `phase: null`. I ran both files through it:\n\n| File | Rows | Read |\n| --- | --- | --- |\n| tides-2025.txt | 1,460 | all |\n| tides-2026.txt | 1,412 | all |" },
  ],
  earlier: null,
};
const NEWEST = { messages: [
  { index: 2, role: "user", text: "An old question about the lighthouse's lamp hours.", ts: NOW - 50 * MIN },
  { index: 3, role: "assistant", text: "The lamp ran 4,112 hours last year. The log is in `lamp/2025.csv`.", ts: NOW - 49 * MIN, message: "msg_o", model: "claude-opus-5-5" },
  { index: 4, role: "user", text: "And this year so far?", ts: NOW - 2 * MIN },
  { index: 5, role: "assistant", text: "3,208 hours to the end of October, which is on course for about 3,900.", ts: NOW - MIN, message: "msg_n", model: "claude-opus-5-5" },
], earlier: "c1" };
const FEED = [
  ["hello", { session: "live1111", live: ["t1"] }],
  ["turn", { turn: "t1", message: "msg_live1", agent: null, model: "claude-opus-5-5", head: "claude-splice", compact: false, at: NOW }],
  ["thinking", { turn: "t1", index: 0, delta: "The north pier label overlaps the depth mark." }],
  ["text", { turn: "t1", index: 1, delta: "The north pier label moved clear of the depth mark.\n\n" }],
  ["tool", { turn: "t1", index: 2, id: "toolu_l1", name: "Bash" }],
  ["text", { turn: "t1", index: 3, delta: "Now the south pier. Its label is drawn twice at the widest zoom:\n\n- once by the pier layer\n- once by the label layer\n\n" }],
] as const;
const feed = () => new Response(new ReadableStream({
  start(c) { for (const [event, data] of FEED) c.enqueue(new TextEncoder().encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)); },
}), { headers: { "content-type": "text/event-stream", "cache-control": "no-store" } });
// a reply longer than any screen here, written a paragraph at a time, then left open as a live one is
const growing = () => new Response(new ReadableStream({
  async start(c) {
    const say = (event: string, data: unknown) => c.enqueue(new TextEncoder().encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
    say("hello", { session: "grow1111", live: ["t9"] });
    say("turn", { turn: "t9", message: "msg_grow", agent: null, model: "claude-opus-5-5", head: "claude-splice", compact: false, at: NOW });
    for (let i = 1; i <= 24; i++) {
      await Bun.sleep(25);
      say("text", { turn: "t9", index: 0, delta: `Depth mark ${i}: the sounding at the harbor mouth reads ${(3 + i * 0.4).toFixed(1)} m at low water, and the chart shows it beside the pier it belongs to.\n\n` });
    }
  },
}), { headers: { "content-type": "text/event-stream", "cache-control": "no-store" } });
const before = (url: URL) => url.searchParams.get("before");

const WORLD = world({
  "/api/sessions": { reading: true, sessions: [
    session("talk1111", "tide-fixer", "harbor", { status: "working" }),
    session("live1111", "map-drawer", "harbor", { status: "working" }),
    session("long1111", "log-keeper", "lighthouse"),
    session("ask11111", "chart-checker", "harbor", { waiting_for: "input needed",
      last: { role: "assistant", tool: null, text: "Which tide table?", ts: NOW - MIN, asks: [{ question: "Which tide table should the chart use?", options: ["The 2026 table", "The 2025 table", "Both, side by side"] }] } }),
    session("lock1111", "chart-reader", "harbor", { status: "working" }),
    session("grow1111", "depth-marker", "harbor", { status: "working" }),
  ] },
  "/api/sessions/talk1111/transcript": (_r: Request, url: URL) => (before(url) === "end" ? TALK : { messages: [] }),
  "/api/sessions/live1111/transcript": (_r: Request, url: URL) => (before(url) === "end"
    ? { messages: [{ index: 0, role: "user", text: "Fix the harbor map: the pier labels sit on top of the depth marks.", ts: NOW - 2 * MIN }], earlier: null } : { messages: [] }),
  "/api/sessions/live1111/live": () => feed(),
  "/api/sessions/grow1111/transcript": (_r: Request, url: URL) => (before(url) === "end"
    ? { messages: [{ index: 0, role: "user", text: "List every depth mark at the harbor mouth.", ts: NOW - MIN }], earlier: null } : { messages: [] }),
  "/api/sessions/grow1111/live": () => growing(),
  "/api/sessions/long1111/transcript": (_r: Request, url: URL) => (before(url) === "end" ? NEWEST : { messages: [] }),
  "/api/sessions/ask11111/transcript": (_r: Request, url: URL) => (before(url) === "end"
    ? { messages: [{ index: 0, role: "user", text: "Draw the chart for the harbor mouth.", ts: NOW - 3 * MIN },
      { index: 1, role: "assistant", text: "There are two tide tables in the folder, and they disagree for March.", ts: NOW - MIN, message: "msg_q", model: "claude-opus-5-5" }], earlier: null } : { messages: [] }),
  "/api/sessions/lock1111/transcript": () => Response.json({ error: "made-up refusal" }, { status: 500 }),
  "/api/sessions/gone0000-0000/transcript": () => Response.json({ error: "gone" }, { status: 404 }),
  "/api/sessions/history": { sessions: [] },
});

type Step = (page: import("@playwright/test").Page) => Promise<void>;
const all = [NARROW, WIDE, PHONE];
const SCENES: { name: string; hash: string; sizes: { width: number; height: number }[]; full?: boolean; steps?: Step }[] = [
  { name: "talk", hash: "talk1111", sizes: all },
  { name: "talk-whole", hash: "talk1111", sizes: [NARROW, PHONE], full: true },
  { name: "talk-opened", hash: "talk1111", sizes: [NARROW, WIDE], full: true, steps: async (p) => {
    await p.locator("#thread .rv summary", { hasText: "Conversation compacted" }).click();
    for (const s of await p.locator("#thread .rv details.rv-tool > summary").all()) await s.click();
    await p.locator("#thread .rv summary", { hasText: "Thinking" }).first().click();
    await p.waitForTimeout(400);
  } },
  { name: "live", hash: "live1111", sizes: all },
  { name: "grow", hash: "grow1111", sizes: all, steps: async (p) => { await p.locator("#thread .rv-turn").getByText("Depth mark 24:").waitFor({ timeout: 15_000 }); await p.waitForTimeout(500); } },
  { name: "earlier", hash: "long1111", sizes: [NARROW, WIDE, PHONE] },
  { name: "ask", hash: "ask11111", sizes: [NARROW, PHONE] },
  { name: "top", hash: "talk1111", sizes: [PHONE], steps: async (p) => { await p.evaluate(() => window.scrollTo(0, 0)); await p.waitForTimeout(300); } },
  { name: "failed", hash: "lock1111", sizes: [NARROW] },
  { name: "gone", hash: "gone0000-0000", sizes: [NARROW] },
];

const report: string[] = [];
const b = await browser();
const d = daemon(WORLD);
try {
  for (const s of SCENES) for (const theme of ["night", "day"] as const) for (const size of s.sizes) {
    const name = `${s.name}-${theme}-${size.width}`;
    let o: Awaited<ReturnType<typeof open>> | null = null;
    try {
      o = await open(b, d, `sessions.html#${s.hash}`, size, theme);
      await o.page.waitForTimeout(1200);
      if (s.steps) await s.steps(o.page);
      await o.page.screenshot({ path: join(dir, `${name}.png`), fullPage: !!s.full });
      const spill = await o.page.evaluate(() => document.documentElement.scrollWidth - innerWidth);
      const drawn = await o.page.evaluate(() => {
        const t = document.querySelector("#thread");
        return { messages: t ? t.querySelectorAll(".rv [data-index]").length : -1, note: (document.querySelector("#cvNote")?.textContent || "").trim() };
      });
      report.push(`${name}: ${o.errors.length ? "threw: " + o.errors.join(" | ") : "no error"}${spill > 1 ? `; ${spill}px past the right edge` : ", nothing past the right edge"}; ${drawn.messages} messages drawn${drawn.note ? `; note "${drawn.note}"` : ""}`);
    } catch (e) {
      report.push(`${name}: failed: ${(e as Error).message.split("\n")[0]}`);
    } finally {
      await o?.close();
    }
  }
} finally {
  d.stop();
  await b.close();
}
writeFileSync(join(dir, "report.txt"), `${report.join("\n")}\n`);
console.log(report.join("\n"));
