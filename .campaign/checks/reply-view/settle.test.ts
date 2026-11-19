// rv-settle: a live reply settles into the transcript's form. Once its turn has ended and the transcript's message with
// the feed's `message` id arrives, the live turn gives way to the reply as history draws it, in its place, drawn once; a
// message of that id that arrives while the turn is still live is held until it ends. A tool's result that arrives
// before its call has settled is drawn inside the live tool line, and moves with it into the settled call. A compaction
// gives way to the transcript's summary; a subagent's ended turn, which the main transcript never holds, stays as its
// line. Live turns sit below every transcript message. On the reader's own page, a reply streams in and settles with
// the connector reading splice's API.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { expect as pw, type Browser, type Page } from "@playwright/test";
import {
  addHistory, addLive, browser, CHAT, ev, host, mount, open, PHONE, readable, SESSION, SESSIONS, stub, turnEv, WORLD, type Msg, type Stub,
} from "./lib/rv.ts";

let b: Browser;
let st: Stub;
beforeAll(async () => {
  b = await browser();
  st = stub({ transcripts: { [SESSION]: [...WORLD] }, answers: { "/api/sessions": SESSIONS } });
}, 30_000);
afterAll(async () => {
  await b?.close();
  st?.stop();
});

const SOON = 1_500;
const hello = (live: string[] = []) => ev("hello", { session: SESSION, live });
const text = (turn: string, index: number, delta: string) => ev("text", { turn, index, delta });
const end = (turn: string, stop: string | null = "end_turn") => ev("end", { turn, stop_reason: stop, error: null });
const count = (hay: string, needle: string) => hay.split(needle).length - 1;
const sel = (id: string) => `.rv .rv-turn[data-turn="${id}"]`;
const turnEl = (page: Page, id: string) => page.locator(sel(id));
const before = (page: Page, a: string, z: string) =>
  page.evaluate(([x, y]) => {
    const ax = document.querySelector(x!);
    const zy = document.querySelector(y!);
    return !!ax && !!zy && (ax.compareDocumentPosition(zy) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
  }, [a, z] as const);

const REPLY = "The parser now splits on **tabs** and commas.\n\n";
const SETTLED: Msg[] = [
  { index: 50, role: "assistant", text: REPLY.trim(), message: "msg_live1", model: "claude-opus-5-5", thinking: "Checking the header row first." },
  { index: 51, role: "assistant", text: JSON.stringify({ command: "bun test parser" }), tool: "Bash", tool_use_id: "toolu_l1", message: "msg_live1", model: "claude-opus-5-5" },
];
const RESULT: Msg = { index: 52, role: "tool", text: "3 pass\n0 fail", tool: "Bash", tool_use_id: "toolu_l1" };

test("an ended reply settles into the transcript's form, drawn once, with its tool's result in place", async () => {
  const o = await host(b, st);
  try {
    await mount(o.page, CHAT.slice(0, 1));
    await addLive(o.page, [
      hello(),
      turnEv("T1", "msg_live1"),
      ev("thinking", { turn: "T1", index: 0, delta: "Checking the header row first." }),
      text("T1", 1, REPLY),
      ev("tool", { turn: "T1", index: 2, id: "toolu_l1", name: "Bash" }),
      end("T1", "tool_use"),
    ]);
    const t1 = turnEl(o.page, "T1");
    await pw.poll(() => t1.getAttribute("data-state"), { timeout: SOON }).toBe("ended");
    // the result reaches the transcript before the reply's own messages: it is drawn inside the live tool line
    await addHistory(o.page, [RESULT]);
    await pw(t1.locator('details.rv-tool [data-index="52"]')).toHaveCount(1, { timeout: SOON });
    await t1.locator("details.rv-tool > summary").first().click();
    expect(await readable(o.page, sel("T1"))).toContain("3 pass");

    // the reply's messages arrive: the live turn gives way to them, in its place, drawn once
    await addHistory(o.page, SETTLED);
    await pw(turnEl(o.page, "T1")).toHaveCount(0, { timeout: SOON });
    await pw(o.page.locator('.rv [data-index="50"]')).toHaveCount(1);
    await pw(o.page.locator('.rv [data-index="51"] [data-index="52"]')).toHaveCount(1);
    await pw(o.page.locator('.rv [data-index="52"]')).toHaveCount(1);
    await pw(o.page.locator(".rv details.rv-tool > summary").first()).toContainText("bun test parser");
    await pw(o.page.locator(".rv details.rv-thinking")).toHaveCount(1);
    await pw(o.page.locator('.rv [data-index="50"] .rv-md strong')).toHaveText("tabs");
    const now = await readable(o.page);
    expect(count(now, "The parser now splits on tabs and commas.")).toBe(1);
    expect(now.indexOf("Fix the tide table parser")).toBeLessThan(now.indexOf("The parser now splits"));
    for (const d of await o.page.locator(".rv details.rv-tool").all()) await d.locator("summary").first().click();
    expect(count(await readable(o.page), "3 pass")).toBe(1);
    expect(o.errors).toEqual([]);
    expect(await o.csp()).toEqual([]);
  } finally {
    await o.close();
  }
}, 60_000);

test("a transcript message that arrives while its turn is still live is held, and the reply settles when the turn ends", async () => {
  const o = await host(b, st);
  try {
    await mount(o.page, CHAT.slice(0, 1));
    await addLive(o.page, [hello(), turnEv("T2", "msg_live2"), text("T2", 0, "Second reply streaming.\n\n")]);
    await pw(turnEl(o.page, "T2")).toContainText("Second reply streaming.", { timeout: SOON });
    await addHistory(o.page, [{ index: 60, role: "assistant", text: "Second reply streaming.", message: "msg_live2" }]);
    await o.page.waitForTimeout(300);
    expect(count(await readable(o.page), "Second reply streaming.")).toBe(1);
    await pw(turnEl(o.page, "T2")).toHaveCount(1);
    await pw(o.page.locator('.rv [data-index="60"]')).toHaveCount(0);
    // a transcript message of no live reply is drawn at once, above the live turn
    await addHistory(o.page, [{ index: 61, role: "peer", text: "A made-up note from a teammate.", from: "map-builder" }]);
    await pw(o.page.locator('.rv [data-index="61"]')).toHaveCount(1, { timeout: SOON });
    expect(await before(o.page, '.rv [data-index="61"]', sel("T2"))).toBe(true);
    await addLive(o.page, [end("T2")]);
    await pw(turnEl(o.page, "T2")).toHaveCount(0, { timeout: SOON });
    await pw(o.page.locator('.rv [data-index="60"]')).toHaveCount(1);
    expect(await before(o.page, '.rv [data-index="60"]', '.rv [data-index="61"]')).toBe(true);
    expect(count(await readable(o.page), "Second reply streaming.")).toBe(1);
  } finally {
    await o.close();
  }
}, 60_000);

test("a compaction gives way to the transcript's summary, and a subagent's ended turn stays as its line", async () => {
  const o = await host(b, st);
  try {
    await mount(o.page, CHAT.slice(0, 1));
    await addLive(o.page, [
      hello(),
      turnEv("T3", "msg_sub3", { agent: "agent-7" }),
      text("T3", 0, "The subagent looks at the harbor map.\n\n"),
      end("T3"),
      turnEv("T4", null, { compact: true }),
      text("T4", 0, "Made-up compaction summary.\n\n"),
      end("T4"),
    ]);
    await pw.poll(() => turnEl(o.page, "T4").getAttribute("data-state"), { timeout: SOON }).toBe("ended");
    await addHistory(o.page, [
      { index: 70, role: "system", text: "This session is being continued from a previous conversation. Summary: made-up summary.", compact: true },
      { index: 71, role: "user", text: "Carry on with the map." },
    ]);
    await pw(turnEl(o.page, "T4")).toHaveCount(0, { timeout: SOON });
    await pw(o.page.locator(".rv details.rv-compaction")).toHaveCount(1);
    await pw(o.page.locator('.rv [data-index="70"]')).toHaveCount(1);
    await pw(o.page.locator('.rv details.rv-compaction > summary').first()).toContainText("Conversation compacted");
    // the subagent's turn is never in the main transcript: it stays, its one closed line
    await pw(turnEl(o.page, "T3")).toHaveCount(1);
    await pw(turnEl(o.page, "T3").locator("summary").first()).toContainText("Subagent");
    expect(o.errors).toEqual([]);
  } finally {
    await o.close();
  }
}, 60_000);

test("on the reader's own page a reply streams in as it is written and settles when its message reaches the transcript", async () => {
  const o = await open(b, st, `/reply-view/index.html?session=${SESSION}`, PHONE, "night");
  try {
    await pw(o.page.locator(".rv .rv-assistant", { hasText: "split on tabs only" })).toBeVisible({ timeout: 10_000 });
    await pw.poll(() => st.followers(SESSION), { timeout: 10_000 }).toBeGreaterThan(0);
    st.push(SESSION, "turn", { turn: "P1", message: "msg_page1", agent: null, model: "claude-opus-5-5", head: "claude-splice", compact: false, at: Date.now() });
    st.push(SESSION, "text", { turn: "P1", index: 0, delta: "The harbor parser " });
    st.push(SESSION, "text", { turn: "P1", index: 0, delta: "is fixed.\n\n" });
    await pw(o.page.locator('.rv .rv-turn[data-turn="P1"]')).toContainText("The harbor parser is fixed.", { timeout: 5_000 });
    const settled: Msg = { index: 90, role: "assistant", text: "The harbor parser is fixed.", message: "msg_page1", model: "claude-opus-5-5" };
    st.transcripts[SESSION]!.push(settled);
    st.push(SESSION, "end", { turn: "P1", stop_reason: "end_turn", error: null });
    await pw(o.page.locator('.rv .rv-turn[data-turn="P1"]')).toHaveCount(0, { timeout: 8_000 });
    await pw(o.page.locator('.rv [data-index="90"]')).toHaveCount(1);
    expect(count(await readable(o.page, "body"), "The harbor parser is fixed.")).toBe(1);
    expect(o.errors).toEqual([]);
    expect(await o.csp()).toEqual([]);
  } finally {
    await o.close();
    st.transcripts[SESSION] = [...WORLD];
  }
}, 60_000);
