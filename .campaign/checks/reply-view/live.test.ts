// rv-live: a reply being written grows in place by whole blocks, never by the character or the token: the reader holds
// a block's text until the block closes (a line of prose, a list item, a heading or a table row at its line break; a
// fenced code block at its closing fence), or until about a second has passed, then draws it whole, as Markdown. While
// thinking lasts it is one quiet "Thinking…" line and none of its text; once it closes it is the closed Thinking fold
// history draws. A tool call shows as its one closed line the moment it starts. The turn's end draws everything held.
// A subagent's turn and a compaction are told apart from the main thread, each its own closed line, worded the same
// everywhere, and a fold a person opened stays open. A follower that joins again gets a snapshot, drawn at once in place
// of what was drawn, and a turn that failed says why.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { expect as pw, type Browser, type Page } from "@playwright/test";
import { addLive, browser, CHAT, DESK, ev, host, mount, PHONE, readable, SESSION, spills, stub, THEMES, turnEv, type Stub } from "./lib/rv.ts";

let b: Browser;
let st: Stub;
beforeAll(async () => {
  b = await browser();
  st = stub();
}, 30_000);
afterAll(async () => {
  await b?.close();
  st?.stop();
});

/** A block that closed is drawn within this. */
const SOON = 700;
/** A block still being written is not drawn this soon (the reader waits about a second). */
const HELD = 350;
/** A block still being written is drawn whole by then. */
const LATE = 2_500;

const hello = (live: string[] = []) => ev("hello", { session: SESSION, live });
const text = (turn: string, index: number, delta: string) => ev("text", { turn, index, delta });
const think = (turn: string, index: number, delta: string) => ev("thinking", { turn, index, delta });
const end = (turn: string, stop: string | null = "end_turn", error: string | null = null) => ev("end", { turn, stop_reason: stop, error });
const count = (hay: string, needle: string) => hay.split(needle).length - 1;
const sel = (id: string) => `.rv .rv-turn[data-turn="${id}"]`;
const turnEl = (page: Page, id: string) => page.locator(sel(id));
/** Everything in the DOM under [selector], drawn or folded away: held text must not be anywhere in it. */
const inDom = (page: Page, selector: string) => page.evaluate((s) => document.querySelector(s)?.textContent ?? "", selector);
const isOpen = (page: Page, selector: string) => page.locator(selector).evaluate((d) => d.tagName === "DETAILS" && (d as HTMLDetailsElement).open);

test("a reply grows by whole blocks: a paragraph, a list item, a code block at a time, never by the character", async () => {
  const o = await host(b, st);
  try {
    await mount(o.page, CHAT.slice(0, 1));
    await addLive(o.page, [hello(), turnEv("T1", "msg_live1"), think("T1", 0, "Checking the header row ")]);
    const t1 = turnEl(o.page, "T1");
    await pw(t1).toHaveCount(1);
    expect(await t1.getAttribute("data-state")).toBe("live");
    // thinking, while it lasts, is one quiet line, and none of its text is anywhere
    await pw(t1.locator(".rv-thinking-live")).toHaveText(/^\s*Thinking/, { timeout: SOON });
    await addLive(o.page, [think("T1", 0, "before the body.")]);
    await o.page.waitForTimeout(HELD);
    expect(await inDom(o.page, sel("T1"))).not.toContain("Checking the header row");

    // a paragraph still being written is held
    await addLive(o.page, [text("T1", 1, "The parser now splits on **tabs**")]);
    await o.page.waitForTimeout(HELD);
    expect(await inDom(o.page, sel("T1"))).not.toContain("The parser");
    // the thinking closed when the text began: now it is the closed Thinking fold history draws
    await pw(t1.locator(".rv-thinking-live")).toHaveCount(0);
    await pw(t1.locator("details.rv-thinking")).toHaveCount(1);
    expect(await isOpen(o.page, `${sel("T1")} details.rv-thinking`)).toBe(false);
    await pw(t1.locator("details.rv-thinking > summary")).toContainText("Thinking");
    expect(await readable(o.page, sel("T1"))).not.toContain("Checking the header row");

    // the paragraph closes: drawn whole, as Markdown; a list item at its line break
    await addLive(o.page, [text("T1", 1, " and commas.\n\n- first item\n")]);
    await pw(t1.locator(".rv-md p", { hasText: "The parser now splits on tabs and commas." })).toHaveCount(1, { timeout: SOON });
    await pw(t1.locator(".rv-md strong")).toHaveText("tabs");
    await pw(t1.locator(".rv-md li", { hasText: "first item" })).toHaveCount(1, { timeout: SOON });

    // an item still being written is held, then drawn whole once about a second has passed
    await addLive(o.page, [text("T1", 1, "- sec")]);
    await o.page.waitForTimeout(HELD);
    expect(await inDom(o.page, sel("T1"))).not.toMatch(/\bsec\b/);
    await pw(t1.locator(".rv-md li", { hasText: /^\s*sec\s*$/ })).toHaveCount(1, { timeout: LATE });

    // a code block is drawn when its fence closes, not line by line
    await addLive(o.page, [text("T1", 1, "ond item\n\n```ts\nconst a = 1;\n")]);
    await pw(t1.locator(".rv-md li", { hasText: "second item" })).toHaveCount(1, { timeout: SOON });
    expect(await inDom(o.page, sel("T1"))).not.toContain("const a = 1;");
    await pw(t1.locator(".rv-code")).toHaveCount(0);
    await addLive(o.page, [text("T1", 1, "const b = 2;\n```\n")]);
    await pw(t1.locator(".rv-code")).toHaveCount(1, { timeout: SOON });
    expect(await t1.locator(".rv-code pre code").evaluate((c) => c.textContent)).toBe("const a = 1;\nconst b = 2;");

    // a tool call is its one closed line the moment it starts
    await addLive(o.page, [ev("tool", { turn: "T1", index: 2, id: "toolu_l1", name: "Bash" })]);
    await pw(t1.locator("details.rv-tool > summary")).toContainText("Bash", { timeout: SOON });
    expect(await isOpen(o.page, `${sel("T1")} details.rv-tool`)).toBe(false);

    // the turn's end draws what is still held, at once
    await addLive(o.page, [text("T1", 3, "Waiting for the tests to finish")]);
    await addLive(o.page, [end("T1", "tool_use")]);
    await pw.poll(() => t1.getAttribute("data-state"), { timeout: SOON }).toBe("ended");
    await pw(t1).toContainText("Waiting for the tests to finish", { timeout: SOON });
    const now = await readable(o.page);
    expect(count(now, "The parser now splits on tabs and commas.")).toBe(1);
    expect(now.indexOf("Fix the tide table parser")).toBeLessThan(now.indexOf("The parser now splits"));
    expect(o.errors).toEqual([]);
    expect(await o.csp()).toEqual([]);
  } finally {
    await o.close();
  }
}, 60_000);

test("a subagent's turn and a compaction are told apart from the main thread, each its own closed line", async () => {
  const o = await host(b, st);
  try {
    await mount(o.page, CHAT.slice(0, 1));
    await addLive(o.page, [
      hello(),
      turnEv("T3", "msg_sub1", { agent: "agent-7" }),
      text("T3", 0, "The subagent looks at the harbor map.\n\n"),
      turnEv("T4", null, { compact: true }),
      text("T4", 0, "Made-up compaction summary of the work so far.\n\n"),
    ]);
    const sub = turnEl(o.page, "T3");
    await pw(sub).toHaveClass(/\brv-subagent\b/);
    expect(await sub.evaluate((el) => el.tagName)).toBe("DETAILS");
    expect(await isOpen(o.page, sel("T3"))).toBe(false);
    await pw(sub.locator("summary").first()).toContainText("Subagent");
    await pw(sub.locator("summary").first()).toContainText("claude-opus-5-5");
    await pw(sub).toContainText("The subagent looks at the harbor map.", { timeout: SOON });
    const compaction = turnEl(o.page, "T4");
    await pw(compaction).toHaveClass(/\brv-compaction\b/);
    expect(await isOpen(o.page, sel("T4"))).toBe(false);
    await pw(compaction.locator("summary").first()).toContainText("Compacting…");
    const text0 = await readable(o.page);
    expect(text0).not.toContain("Made-up compaction summary");
    expect(text0).not.toContain("The subagent looks at the harbor map.");
    // neither is mixed into a main-thread turn
    await pw(o.page.locator(".rv .rv-turn:not(.rv-subagent):not(.rv-compaction)")).toHaveCount(0);

    // a person opens the subagent's line while it works: it stays open when the turn ends
    await sub.locator("summary").first().click();
    expect(await readable(o.page)).toContain("The subagent looks at the harbor map.");
    await addLive(o.page, [end("T3"), end("T4")]);
    await pw.poll(() => sub.getAttribute("data-state"), { timeout: SOON }).toBe("ended");
    await pw.poll(() => compaction.getAttribute("data-state"), { timeout: SOON }).toBe("ended");
    expect(await isOpen(o.page, sel("T3"))).toBe(true);
    expect(await isOpen(o.page, sel("T4"))).toBe(false);
    await pw(compaction.locator("summary").first()).toContainText("Conversation compacted");
    await pw(sub.locator("summary").first()).toContainText("Subagent");
    expect(o.errors).toEqual([]);
  } finally {
    await o.close();
  }
}, 60_000);

test("a snapshot after joining again is drawn at once in place of what was drawn, and a failed turn says why", async () => {
  const o = await host(b, st);
  try {
    await mount(o.page, []);
    await addLive(o.page, [hello(), turnEv("T5", "msg_live5"), text("T5", 0, "Snapshot text\n\n")]);
    await pw(turnEl(o.page, "T5")).toContainText("Snapshot text", { timeout: SOON });
    // joining again: the hello names T5 in flight, and its text so far comes whole, unfinished line and all
    await addLive(o.page, [hello(["T5"]), turnEv("T5", "msg_live5"), text("T5", 0, "Snapshot text so far, joined again")]);
    await pw(turnEl(o.page, "T5")).toContainText("Snapshot text so far, joined again", { timeout: SOON });
    expect(count(await readable(o.page), "Snapshot text")).toBe(1);
    await pw(o.page.locator(sel("T5"))).toHaveCount(1);
    // a hello that no longer names it: the turn has ended
    await addLive(o.page, [hello([])]);
    await pw.poll(() => turnEl(o.page, "T5").getAttribute("data-state"), { timeout: SOON }).toBe("ended");
    // a turn cut short keeps what it wrote and says why it stopped
    await addLive(o.page, [turnEv("T6", "msg_live6"), text("T6", 0, "Partial answer"), end("T6", null, "upstream cut the stream")]);
    await pw(turnEl(o.page, "T6")).toContainText("The reply stopped: upstream cut the stream", { timeout: SOON });
    await pw(turnEl(o.page, "T6")).toContainText("Partial answer");
    // a feed event the reader does not know is ignored
    await addLive(o.page, [ev("someday", { turn: "T6" })]);
    expect(o.errors).toEqual([]);
  } finally {
    await o.close();
  }
}, 60_000);

for (const theme of THEMES) {
  test(`a live reply at 375 and 1440 px (${theme}) spills nothing sideways`, async () => {
    for (const size of [PHONE, DESK]) {
      const o = await host(b, st, size, theme);
      try {
        await mount(o.page, CHAT.slice(0, 1));
        await addLive(o.page, [
          hello(),
          turnEv("T7", "msg_live7"),
          text("T7", 0, `Streaming a long word ${"tidetable".repeat(30)} and a block:\n\n\`\`\`ts\nconst a = "${"x".repeat(200)}";\n\`\`\`\n`),
          ev("tool", { turn: "T7", index: 1, id: "toolu_l7", name: "mcp__harbor__lookup_station_tides_for_the_whole_week" }),
          turnEv("T8", "msg_sub8", { agent: "agent-8" }),
          text("T8", 0, "A subagent's words."),
        ]);
        await o.page.waitForTimeout(1_500);
        expect(await spills(o.page)).toEqual([]);
        expect(o.errors).toEqual([]);
      } finally {
        await o.close();
      }
    }
  }, 90_000);
}
