// rv-tools: a tool call is one line saying what ran (the tool and its main argument), which opens to its input and its
// result, read as fields and text, never as raw JSON. A long result is one more line inside it until opened. A result
// whose call is not drawn is a line of its own. Thinking is one closed line until toggled. None of the harness's
// wrapping shows inside a result. Each line stays one line at 375 and at 1440.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { expect as pw, type Browser } from "@playwright/test";
import { browser, CHAT, DESK, host, mount, PHONE, readable, spills, stub, TOOLS, type Stub } from "./lib/rv.ts";

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

const RAW_JSON = /\{\s*"|"\s*:\s*["{[\d]|\[\s*"|"\s*,\s*"/;

test("each tool call is one closed line naming the tool and what it ran on", async () => {
  const o = await host(b, st);
  try {
    await mount(o.page, [...CHAT.slice(0, 1), ...TOOLS]);
    const calls = o.page.locator(".rv details.rv-tool");
    await pw(calls).toHaveCount(5);
    const lines = await calls.evaluateAll((els) => els.map((d) => ({ open: (d as HTMLDetailsElement).open, line: (d.querySelector("summary") as HTMLElement).innerText })));
    expect(lines.every((l) => !l.open)).toBe(true);
    expect(lines[0]!.line).toContain("Bash");
    expect(lines[0]!.line).toContain("bun test parser");
    expect(lines[1]!.line).toContain("Read");
    expect(lines[1]!.line).toContain("header-row.ts");
    expect(lines[2]!.line).toContain("Grep");
    expect(lines[2]!.line).toContain("splitHeader");
    expect(lines[3]!.line).toContain("Edit");
    expect(lines[3]!.line).toContain("header-row.ts");
    expect(lines[4]!.line).toContain("lookup");
    expect(lines[4]!.line).toContain("North Pier");
    const text = await readable(o.page);
    expect(text).not.toMatch(RAW_JSON);
    expect(text).not.toContain("3 pass");
    expect(text).toContain("Running the parser tests first.");
    // the result whose call is on an earlier page is a closed line of its own
    const lone = o.page.locator(".rv details.rv-result").filter({ hasNot: o.page.locator("xpath=ancestor::details[contains(@class,'rv-tool')]") });
    await pw(lone).toHaveCount(1);
    expect(text).not.toContain("Output from a call on an earlier page.");
    await lone.locator("summary").first().click();
    expect(await readable(o.page)).toContain("Output from a call on an earlier page.");
    expect(o.errors).toEqual([]);
    expect(await o.csp()).toEqual([]);
  } finally {
    await o.close();
  }
}, 60_000);

test("opened, a call shows its input and its result as fields and text, with no raw JSON and no harness wrapping", async () => {
  const o = await host(b, st);
  try {
    await mount(o.page, TOOLS);
    const calls = o.page.locator(".rv details.rv-tool");
    for (let i = 0; i < 5; i++) await calls.nth(i).locator("summary").first().click();
    const text = await readable(o.page);
    expect(text).toContain("bun test parser");
    expect(text).toContain("3 pass");
    expect(text).not.toContain("system-reminder");
    expect(text).not.toContain("Made-up reminder inside a result.");
    expect(text).toContain("src/tide.ts:12: const cols = splitHeader(row)");
    expect(text).toContain('row.split("\\t")');
    expect(text).toContain("row.split(/[\\t,]/)");
    expect(text).toContain("has been updated");
    // the MCP call's nested input and its JSON result read as fields
    for (const word of ["North Pier", "06:00", "18:00", "height", "09:12", "3.4"]) expect(text).toContain(word);
    expect(text).not.toMatch(RAW_JSON);
    expect(o.errors).toEqual([]);
  } finally {
    await o.close();
  }
}, 60_000);

test("a long result is one more closed line inside its call, naming how long it is, until opened", async () => {
  const o = await host(b, st);
  try {
    await mount(o.page, TOOLS);
    const read = o.page.locator(".rv details.rv-tool").nth(1);
    await read.locator("summary").first().click();
    const long = read.locator("details.rv-result");
    await pw(long).toHaveCount(1);
    expect(await long.evaluate((d) => (d as HTMLDetailsElement).open)).toBe(false);
    await pw(long.locator("summary").first()).toContainText("300 lines");
    expect(await readable(o.page)).not.toContain("line 300 of the tide table");
    await long.locator("summary").first().click();
    expect(await readable(o.page)).toContain("line 300 of the tide table");
    // a short result opens with its call, with no fold of its own
    const bash = o.page.locator(".rv details.rv-tool").nth(0);
    await bash.locator("summary").first().click();
    await pw(bash.locator("details.rv-result")).toHaveCount(0);
  } finally {
    await o.close();
  }
}, 60_000);

test("thinking is one closed line until toggled", async () => {
  const o = await host(b, st);
  try {
    await mount(o.page, TOOLS);
    const thinking = o.page.locator(".rv details.rv-thinking");
    await pw(thinking).toHaveCount(1);
    expect(await thinking.evaluate((d) => (d as HTMLDetailsElement).open)).toBe(false);
    expect(await readable(o.page)).not.toContain("commas as well as tabs");
    await thinking.locator("summary").first().click();
    expect(await readable(o.page)).toContain("commas as well as tabs");
    await thinking.locator("summary").first().click();
    expect(await readable(o.page)).not.toContain("commas as well as tabs");
  } finally {
    await o.close();
  }
}, 60_000);

for (const size of [PHONE, DESK]) {
  test(`at ${size.width} px every closed line is one line, and opened calls spill nothing sideways`, async () => {
    const o = await host(b, st, size);
    try {
      await mount(o.page, TOOLS);
      // a summary's content box is one line tall: under one and a half of its line height (an icon may stand a little taller)
      const lines = await o.page.locator(".rv details > summary").evaluateAll((els) =>
        els.map((s) => {
          const cs = getComputedStyle(s);
          const px = (v: string) => parseFloat(v) || 0;
          const lh = px(cs.lineHeight) || px(cs.fontSize) * 1.6;
          const content = s.getBoundingClientRect().height - px(cs.paddingTop) - px(cs.paddingBottom) - px(cs.borderTopWidth) - px(cs.borderBottomWidth);
          return { text: (s as HTMLElement).innerText.slice(0, 40), content, limit: Math.max(lh * 1.5, 26) };
        }),
      );
      expect(lines.length).toBeGreaterThanOrEqual(7);
      for (const l of lines) expect(l.content, `"${l.text}" wraps`).toBeLessThan(l.limit);
      for (const d of await o.page.locator(".rv details").all()) {
        if (!(await d.evaluate((el) => (el as HTMLDetailsElement).open))) await d.locator("summary").first().click();
      }
      expect(await spills(o.page)).toEqual([]);
    } finally {
      await o.close();
    }
  }, 60_000);
}
