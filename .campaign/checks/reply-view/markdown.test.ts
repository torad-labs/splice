// rv-markdown: a reply's text is real Markdown (headings, emphasis, lists, tables, links, quotes, inline code), its
// code blocks are highlighted by language with a copy button that copies exactly the code, raw HTML in a reply is never
// run or drawn as markup, and a code block scrolls sideways inside itself instead of widening the page. The person's own
// words stay as typed. The vendored libraries are named in THIRD_PARTY_NOTICES.md.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { expect as pw, type Browser } from "@playwright/test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { browser, DESK, host, MARKDOWN, mount, PHONE, readable, ROOT, SH_CODE, spills, stub, THEMES, TS_CODE, type Stub } from "./lib/rv.ts";

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

test("a reply's Markdown draws as headings, emphasis, a list, a table, a quote, a link and inline code", async () => {
  const o = await host(b, st);
  try {
    await mount(o.page, MARKDOWN);
    const md = o.page.locator(".rv-assistant .rv-md").first();
    await pw(md.locator("h2")).toHaveText("Tide table fix");
    await pw(md.locator("strong").first()).toHaveText("both");
    await pw(md.locator("ul > li")).toHaveText(["tabs", "commas"]);
    await pw(md.locator("table th")).toHaveText(["Column", "Kept"]);
    await pw(md.locator("table tbody tr")).toHaveCount(2);
    await pw(md.locator("blockquote")).toContainText("the harbor feed changed in spring");
    const link = md.locator('a[href="https://example.com/harbor"]');
    await pw(link).toHaveText("the harbor docs");
    expect(await link.getAttribute("rel")).toMatch(/noopener/);
    expect(await link.getAttribute("rel")).toMatch(/noreferrer/);
    await pw(md.locator(":not(pre) > code", { hasText: "splitHeader(row)" })).toHaveCount(1);
    const text = await readable(o.page, ".rv-assistant");
    expect(text).not.toContain("## ");
    expect(text).not.toContain("**both**");
    expect(text).not.toContain("| --- |");
    // the person's words are drawn as typed, not as Markdown
    await pw(o.page.locator(".rv-user strong")).toHaveCount(0);
    await pw(o.page.locator(".rv-user").first()).toContainText("**these stars**");
    expect(o.errors).toEqual([]);
    expect(await o.csp()).toEqual([]);
  } finally {
    await o.close();
  }
}, 60_000);

test("raw HTML in a reply is never run or drawn as markup, and a javascript: link is not a link", async () => {
  const o = await host(b, st);
  try {
    await mount(o.page, MARKDOWN);
    await o.page.waitForTimeout(500);
    await pw(o.page.locator(".rv img")).toHaveCount(0);
    await pw(o.page.locator(".rv script")).toHaveCount(0);
    await pw(o.page.locator('.rv a[href^="javascript"]')).toHaveCount(0);
    expect(await o.page.evaluate(() => (window as unknown as { __pwned?: number }).__pwned)).toBeUndefined();
    expect(await readable(o.page, ".rv-assistant")).toContain("a bad link");
  } finally {
    await o.close();
  }
}, 60_000);

test("code blocks are highlighted by language, and the copy button copies exactly the code", async () => {
  const o = await host(b, st);
  try {
    await mount(o.page, MARKDOWN);
    const blocks = o.page.locator(".rv .rv-code");
    await pw(blocks).toHaveCount(2);
    await pw(blocks.nth(0).locator(".rv-code-lang")).toHaveText(/^(ts|typescript)$/i);
    await pw(blocks.nth(1).locator(".rv-code-lang")).toHaveText(/^(bash|sh|shell)$/i);
    expect(await blocks.nth(0).locator('pre code [class*="hljs-"]').count()).toBeGreaterThan(2);
    expect(await blocks.nth(0).locator("pre code").evaluate((c) => c.textContent)).toBe(TS_CODE);
    for (const [i, code] of [[0, TS_CODE], [1, SH_CODE]] as const) {
      await blocks.nth(i).locator("button.rv-copy").click();
      await pw.poll(() => o.page.evaluate(() => navigator.clipboard.readText()), { timeout: 5_000 }).toBe(code);
      await pw(blocks.nth(i).locator("button.rv-copy")).toContainText(/copied/i);
    }
    expect(o.errors).toEqual([]);
    expect(await o.csp()).toEqual([]);
  } finally {
    await o.close();
  }
}, 60_000);

for (const theme of THEMES) {
  test(`at 375 and 1440 px (${theme}) a long code line scrolls inside its block and nothing spills sideways`, async () => {
    for (const size of [PHONE, DESK]) {
      const o = await host(b, st, size, theme);
      try {
        await mount(o.page, MARKDOWN);
        expect(await spills(o.page)).toEqual([]);
        if (size === PHONE) {
          const pre = o.page.locator(".rv .rv-code").nth(1).locator("pre");
          const { sw, cw, ox } = await pre.evaluate((p) => ({ sw: p.scrollWidth, cw: p.clientWidth, ox: getComputedStyle(p).overflowX }));
          expect(sw).toBeGreaterThan(cw);
          expect(["auto", "scroll"]).toContain(ox);
        }
        expect(o.errors).toEqual([]);
      } finally {
        await o.close();
      }
    }
  }, 90_000);
}

test("the vendored Markdown parser and highlighter are named, with their licenses, in THIRD_PARTY_NOTICES.md", () => {
  const notices = readFileSync(join(ROOT, "THIRD_PARTY_NOTICES.md"), "utf8");
  const start = notices.search(/^##[^\n]*reply[- ]view/im);
  expect(start, "no section of THIRD_PARTY_NOTICES.md names the reply view").toBeGreaterThan(-1);
  const rest = notices.slice(start + 3);
  const section = notices.slice(start, rest.search(/^## /m) < 0 ? undefined : start + 3 + rest.search(/^## /m));
  expect(section).toMatch(/\bmarked\b/i);
  expect(section).toMatch(/highlight\.js/i);
  expect(section.match(/License:/g)?.length ?? 0).toBeGreaterThanOrEqual(2);
  expect(section).toMatch(/app\/src\/main\/resources\/console\/reply-view\//);
});
