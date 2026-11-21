// rv-core: the reader mounts in a host's container and draws a conversation a person reads: the person's messages and
// the model's replies, a teammate's message, notes, and a compaction told apart, with none of the harness's wrapping
// shown and a message that is only wrapping not drawn at all. More messages are appended without moving what is drawn.
// It is dressed only through --rv-* custom properties, scoped to its own root, follows the host's colour scheme, writes
// nothing a strict CSP refuses, never fetches, and nothing spills sideways at 375 or 1440, night or day.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { expect as pw, type Browser } from "@playwright/test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { addHistory, browser, CHAT, DESK, host, mount, PHONE, readable, READER, spills, stub, THEMES, type Msg, type Stub } from "./lib/rv.ts";

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

test("the module is one ES module and a stylesheet that evaluate nothing and fetch nothing", () => {
  const js = readFileSync(join(READER, "reply-view.js"), "utf8");
  expect(js).toMatch(/export\s+(function|const|\{)[^]*\bmount\b/);
  expect(js).not.toMatch(/\beval\s*\(|\bnew\s+Function\b|\bFunction\s*\(\s*["'`]/);
  expect(js).not.toMatch(/\bimport\s*\(/);
  expect(js).not.toMatch(/^\s*import\s/m);
  expect(js).not.toMatch(/\bfetch\s*\(|XMLHttpRequest|EventSource|WebSocket/);
  readFileSync(join(READER, "reply-view.css"), "utf8");
});

test("a conversation draws as a person reads it, with none of the harness's wrapping", async () => {
  const o = await host(b, st);
  try {
    await mount(o.page, CHAT);
    const root = o.page.locator("#host > .rv");
    await pw(root).toHaveCount(1);
    await pw(o.page.locator(".rv-user").first()).toContainText("Fix the tide table parser so it reads the new column.");
    await pw(o.page.locator(".rv-assistant").first()).toContainText("split on tabs only");
    const text = await readable(o.page);
    expect(text).toContain("/rename tide-fix");
    expect(text).toContain("Also check the harbor map.");
    expect(text).toContain("map-builder");
    expect(text).toContain("The harbor map is rebuilt");
    expect(text).toContain("Interrupted");
    expect(text).toContain("the made-up provider is overloaded");
    // a message the person took back is still theirs, marked so; a command's output is its content, as a quiet note
    await pw(o.page.locator('.rv [data-index="10"]')).toContainText("Never mind the harbor map.");
    await pw(o.page.locator('.rv [data-index="10"]')).toContainText("Taken back");
    expect(text).toContain("Made-up command output: 3 files renamed");
    for (const never of ["system-reminder", "Made-up reminder", "command-name", "command-message", "<command", "[Request interrupted", "local-command", "task-notification", "made-up-task"]) {
      expect(text, `shows ${never}`).not.toContain(never);
    }
    // a message that was only wrapping (a reminder, a task notification) is not drawn, and no drawn message is empty
    const drawn = await o.page.locator(".rv [data-index]").evaluateAll((els) => els.map((e) => [e.getAttribute("data-index"), (e as HTMLElement).innerText.trim().length]));
    expect(drawn.map(([i]) => i)).not.toContain("1");
    expect(drawn.map(([i]) => i)).not.toContain("12");
    expect(drawn.filter(([, n]) => n === 0)).toEqual([]);
    // a compaction is a marked divider, its summary closed until opened
    const compaction = o.page.locator(".rv-compaction");
    await pw(compaction).toHaveCount(1);
    expect(text).not.toContain("the parser now reads tabs and commas");
    await compaction.locator("summary").first().click();
    expect(await readable(o.page)).toContain("the parser now reads tabs and commas");
    expect(o.errors).toEqual([]);
    expect(await o.csp()).toEqual([]);
  } finally {
    await o.close();
  }
}, 60_000);

test("appending draws only what is new: what is drawn stays where it is, and a message sent again is updated in place", async () => {
  const o = await host(b, st);
  try {
    await mount(o.page, CHAT.slice(0, 4));
    const before = await o.page.evaluate(() => {
      const el = document.querySelector('.rv [data-index="0"]') as HTMLElement & { __kept?: boolean };
      el.__kept = true;
      return el.getBoundingClientRect().top;
    });
    await addHistory(o.page, CHAT.slice(4));
    const after = await o.page.evaluate(() => {
      const el = document.querySelector('.rv [data-index="0"]') as HTMLElement & { __kept?: boolean };
      return { kept: el.__kept === true, top: el.getBoundingClientRect().top };
    });
    expect(after.kept, "the first message's element was replaced").toBe(true);
    expect(after.top).toBe(before);
    expect(await readable(o.page)).toContain("The harbor map is rebuilt");
    // the transcript route reads its last message again when it grew: the same index, more text, drawn once
    const grown: Msg = { ...CHAT[3]!, text: `${CHAT[3]!.text} The fix is to split on commas too.` };
    await addHistory(o.page, [grown]);
    const text = await readable(o.page);
    expect(text).toContain("split on commas too");
    expect(text.split("split on tabs only").length - 1).toBe(1);
    await pw(o.page.locator('.rv [data-index="3"]')).toHaveCount(1);
  } finally {
    await o.close();
  }
}, 60_000);

test("its look comes only from --rv-* custom properties on its own root, and its rules never reach outside it", async () => {
  const o = await host(b, st);
  try {
    await mount(o.page, CHAT);
    const outside = await o.page.evaluate(() => {
      const bad: string[] = [];
      const walk = (rules: CSSRuleList) => {
        for (const r of rules) {
          if (r instanceof CSSStyleRule) {
            for (const sel of r.selectorText.split(",").map((s) => s.trim())) if (!/^\.rv(?![\w-])/.test(sel)) bad.push(sel);
          } else if (r instanceof CSSMediaRule || r instanceof CSSSupportsRule || r instanceof CSSContainerRule || r instanceof CSSLayerBlockRule) {
            walk(r.cssRules);
          } else if (r instanceof CSSKeyframesRule) {
            if (!r.name.startsWith("rv-")) bad.push(`@keyframes ${r.name}`);
          } else if (r instanceof CSSFontFaceRule || r instanceof CSSImportRule) {
            bad.push(r.cssText.slice(0, 60));
          }
        }
      };
      for (const s of document.styleSheets) if (s.href?.endsWith("/reply-view/reply-view.css")) walk(s.cssRules);
      return bad;
    });
    expect(outside).toEqual([]);
    // the host dresses it by setting a custom property on its container
    const color = () => o.page.evaluate(() => getComputedStyle(document.querySelector(".rv-assistant")!).color);
    const was = await color();
    await o.page.evaluate(() => (document.getElementById("host") as HTMLElement).style.setProperty("--rv-text", "rgb(1, 2, 3)"));
    expect(await color()).toBe("rgb(1, 2, 3)");
    expect(was).not.toBe("rgb(1, 2, 3)");
    // the markup it writes carries no style attribute and no inline handler
    const inline = await o.page.evaluate(() =>
      [...document.querySelectorAll(".rv, .rv *")].flatMap((el) => [...el.attributes].map((a) => a.name)).filter((n) => n === "style" || n.startsWith("on")),
    );
    expect(inline).toEqual([]);
  } finally {
    await o.close();
  }
}, 60_000);

for (const size of [PHONE, DESK]) {
  test(`at ${size.width} px it follows night and day and nothing spills sideways`, async () => {
    const seen: Record<string, { bg: string; fg: string }> = {};
    for (const theme of THEMES) {
      const o = await host(b, st, size, theme);
      try {
        await mount(o.page, CHAT);
        expect(await spills(o.page)).toEqual([]);
        seen[theme] = await o.page.evaluate(() => {
          const a = document.querySelector(".rv-assistant")!;
          let bg = "rgba(0, 0, 0, 0)";
          for (let el: Element | null = document.querySelector(".rv"); el && bg === "rgba(0, 0, 0, 0)"; el = el.parentElement) bg = getComputedStyle(el).backgroundColor;
          return { bg: bg === "rgba(0, 0, 0, 0)" ? getComputedStyle(document.body).backgroundColor : bg, fg: getComputedStyle(a).color };
        });
        expect(o.errors).toEqual([]);
        expect(await o.csp()).toEqual([]);
      } finally {
        await o.close();
      }
    }
    expect(seen.night!.fg, "the reply's text is the same colour in night and day").not.toBe(seen.day!.fg);
  }, 90_000);
}
