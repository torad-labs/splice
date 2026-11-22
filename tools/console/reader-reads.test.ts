// A conversation's highlighted code reads on the console's night and day grounds, wherever the console shows one: the
// open session on the Sessions page and the reader's own page. Each kind of code text (a keyword, a string, a number, a
// type, a comment) is measured against the ground it is drawn on, as the browser composites both, and reads at 4.5:1
// or more. (Nov 21, 2026: the day ground drew code in the kit's pigments as they are, which read at 1.3:1 to 1.9:1
// there.) Through the real pages against a stub daemon with a made-up conversation.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { expect, type Browser } from "@playwright/test";
import { launch, stub, type Stub } from "./deck-stub.ts";

const MIN = 60_000;
const CODE = "```ts\nexport function row(line: string): Tide {\n  const n = Number(line) + 42; // a note\n  return { time: \"00:14\", phase: n };\n}\n```";
const SESSION = {
  session_id: "s-code", name: "tide-fixer", head: "acme", route: "head", availability: "live", status: "working", cwd: "/work/harbor",
  last: { role: "assistant", tool: null, text: "Done.", ts: Date.now() - MIN },
  updated_at: new Date(Date.now() - 2 * MIN).toISOString(), status_updated_at: Date.now() - 2 * MIN,
  project: { id: "/work/harbor", name: "harbor", label: "harbor" },
};

let s: Stub;
let browser: Browser;
beforeAll(async () => {
  s = stub({
    "/api/heads": { heads: [{ key: "acme", label: "claude-acme", gate: {} }] },
    "/api/status": { registry: [{ key: "acme", label: "claude-acme", family: "anthropic" }] },
    "/api/sessions": { reading: true, sessions: [SESSION] },
    "/api/teams": { teams: [] },
    "/api/sessions/history": { sessions: [] },
    "/api/sessions/s-code/transcript": (url: URL) => (url.searchParams.get("before") === "end" ? { messages: [
      { index: 0, role: "user", text: "Show the row reader.", ts: Date.now() - 2 * MIN },
      { index: 1, role: "assistant", text: CODE, ts: Date.now() - MIN, message: "msg_c", model: "acme-1" },
    ], earlier: null } : { messages: [] }),
  });
  browser = await launch();
}, 30_000);
afterAll(async () => {
  await browser?.close();
  s?.stop();
});

/** Each kind of code text under [root] with what it reads at against its ground: the text's colour and every
 *  background behind it, composited from the page's ground up. */
async function reads(path: string, root: string, theme: "night" | "day"): Promise<Record<string, number>> {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript((t) => { try { localStorage.setItem("splice-console-theme", t); } catch { /* storage off */ } }, theme);
  const page = await ctx.newPage();
  try {
    await page.goto(`${s.url}/${path}`);
    await expect(page.locator(`${root} .rv pre code .hljs-keyword`).first()).toBeVisible({ timeout: 15_000 });
    return await page.evaluate((root) => {
      // any CSS colour as sRGB with its alpha, by drawing one pixel of it
      const cv = document.createElement("canvas");
      cv.width = cv.height = 1;
      const cx = cv.getContext("2d", { willReadFrequently: true })!;
      const rgba = (c: string) => {
        cx.clearRect(0, 0, 1, 1);
        cx.fillStyle = c;
        cx.fillRect(0, 0, 1, 1);
        const d = cx.getImageData(0, 0, 1, 1).data;
        return [d[0]!, d[1]!, d[2]!, d[3]! / 255];
      };
      const over = (f: number[], g: number[]) => [0, 1, 2].map((i) => f[i]! * f[3]! + g[i]! * (1 - f[3]!)).concat(1);
      const ground = (el: Element) => {
        const chain: Element[] = [];
        for (let e: Element | null = el; e; e = e.parentElement) chain.unshift(e);
        let g = rgba(getComputedStyle(document.documentElement).getPropertyValue("--ground"));
        for (const e of chain) {
          const c = rgba(getComputedStyle(e).backgroundColor);
          if (c[3]! > 0) g = over(c, g);
        }
        return g;
      };
      const lin = (c: number) => { c /= 255; return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
      const lum = (c: number[]) => 0.2126 * lin(c[0]!) + 0.7152 * lin(c[1]!) + 0.0722 * lin(c[2]!);
      const out: Record<string, number> = {};
      for (const el of document.querySelectorAll(`${root} .rv pre code span[class^="hljs"], ${root} .rv pre code`)) {
        const kind = el.className || "code";
        if (kind in out) continue;
        const g = ground(el), f = over(rgba(getComputedStyle(el).color), g);
        const [hi, lo] = [lum(f), lum(g)].sort((p, q) => q - p);
        out[kind] = Math.round(((hi! + 0.05) / (lo! + 0.05)) * 100) / 100;
      }
      return out;
    }, root);
  } finally {
    await ctx.close();
  }
}

for (const [name, path, root] of [
  ["the open session on the Sessions page", "sessions.html#s-code", "#thread"],
  ["the reader's own page", "reply-view/index.html?session=s-code", "#reader"],
] as const) {
  for (const theme of ["night", "day"] as const) {
    test(`in ${name}, by ${theme}, every kind of highlighted code reads at 4.5:1 or more`, async () => {
      const got = await reads(path, root, theme);
      // the code this draws has a keyword, a string, a number, a type and a comment in it
      for (const kind of ["hljs-keyword", "hljs-string", "hljs-number", "hljs-title class_", "hljs-comment"]) bunExpect(Object.keys(got)).toContain(kind);
      const faint = Object.entries(got).filter(([, ratio]) => ratio < 4.5);
      bunExpect(faint).toEqual([]);
    }, 40_000);
  }
}
