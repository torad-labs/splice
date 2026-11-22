// A console page is one size on every screen. Its type and controls measure the same on a 1440 px window, a 3840 px
// one, and the windows a 3840 px screen has at 125% and 80% browser zoom (3072 and 4800 px): a wider window shows
// more of the page, never the same page larger. On Nov 21, 2026 the person who uses this console on a 3840 px screen
// found everything gigantic and the browser's zoom of no use: the page's type was 1/128 of the window's width, so it
// doubled there, and zooming in changed the window's width and the type back with it. What a wide window shows more of
// is checked page by page: more team columns on Sessions, two teams side by side on Teams, more providers in a row,
// and the second half of Activity and of Settings beside the first. Through the real pages against a stub daemon with
// made-up rows.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { expect, type Browser, type Page } from "@playwright/test";
import { launch, stub, type Stub } from "./deck-stub.ts";

const MIN = 60_000;
const NOW = Date.now();
const CREWS = ["harbor", "lighthouse", "tide", "chart", "dock", "pier", "buoy", "ferry", "quay", "reef", "delta"];
/** how many of the crews splice keeps: a test that wants a small board sets it, and sets it back */
let crews = CREWS.length;
const row = (id: string, name: string, crew: string, over: Record<string, unknown> = {}) => ({
  session_id: id, name, head: "acme", route: "head", availability: "live", status: "idle", cwd: `/work/${crew}`,
  last: { role: "assistant", tool: null, text: `${name} finished a step.`, ts: NOW - 5 * MIN },
  updated_at: new Date(NOW - 5 * MIN).toISOString(), status_updated_at: NOW - 5 * MIN,
  project: { id: `/work/${crew}`, name: crew, label: crew }, ...over,
});
/** nine providers, as a machine with most of them added has: enough to fill more than one row of cards */
const MAKERS = [["acme", "anthropic"], ["north", "openai"], ["south", "xai"], ["east", "moonshot"], ["west", "openrouter"], ["ridge", "deepseek"], ["vale", "openai"], ["delta", "openai"], ["cove", "openai"]] as const;
const TALK = { messages: [
  { index: 0, role: "user", text: "Read the tide table and fix the parser.", ts: NOW - 3 * MIN },
  { index: 1, role: "assistant", text: "The parser reads the new column now, and last year's file still reads.", ts: NOW - 2 * MIN, message: "m1", model: "acme-1" },
], earlier: null };

let s: Stub;
let browser: Browser;
beforeAll(async () => {
  s = stub({
    "/api/heads": { heads: MAKERS.map(([key]) => ({ key, label: `claude-${key}`, state: "running", gate: { inflight: 0, queued: 0, max: 12 } })) },
    "/api/status": { version: "9.8.7", registry: MAKERS.map(([key, family]) => ({ key, label: `claude-${key}`, family, authKind: key === "acme" ? "client" : "api-key" })) },
    "/api/models": { heads: MAKERS.map(([key]) => ({ head: key, models: [{ id: `${key}-1`, label: `${key} 1` }] })) },
    "/api/sessions": () => ({ reading: true, sessions: CREWS.slice(0, crews).flatMap((c) => [row(`${c}-1`, `${c}-lead`, c, { status: "working" }), row(`${c}-2`, `${c}-mate`, c)]) }),
    "/api/teams": () => ({ teams: CREWS.slice(0, crews).map((c) => ({
      id: `t-${c}`, name: `${c}-crew`, goal: `Chart the ${c}`, repo: `/work/${c}`, archived: false,
      slots: [{ id: "lead", role: "Lead", head: "acme", lead: true, session: `${c}-1` }, { id: "mate", role: "Mate", head: "acme", lead: false, session: `${c}-2` }],
    })) }),
    "/api/sessions/harbor-1/transcript": (url: URL) => (url.searchParams.get("before") === "end" ? TALK : { messages: [] }),
    "/api/sessions/history": { sessions: [] },
  });
  browser = await launch();
}, 30_000);
afterAll(async () => {
  await browser?.close();
  s?.stop();
});

async function open(path: string, width: number): Promise<Page> {
  const page = await browser.newPage({ viewport: { width, height: Math.round(width * 0.5625) } });
  await page.goto(`${s.url}/${path}`);
  await expect(page.locator("nav.nav")).toBeVisible({ timeout: 15_000 });
  return page;
}

/** what a person would hold a ruler to: the nav's height, a tab's type, the page's heading */
const sizes = (page: Page) => page.evaluate(() => {
  const size = (sel: string) => { const el = document.querySelector(sel); return el ? getComputedStyle(el).fontSize : "none"; };
  return { nav: Math.round(document.querySelector("nav.nav")!.getBoundingClientRect().height), tab: size("nav.nav .tab"), heading: size("h1"), sideways: document.documentElement.scrollWidth > innerWidth };
});

for (const file of ["sessions.html", "accounts.html", "teams.html", "providers.html", "activity.html", "settings.html"]) {
  test(`${file} is the same size at 1440, 3072, 3840 and 4800 px, and none scrolls sideways`, async () => {
    const seen = [];
    for (const width of [1440, 3072, 3840, 4800]) {
      const page = await open(file, width);
      try {
        seen.push(await sizes(page));
      } finally {
        await page.close();
      }
    }
    for (const at of seen) bunExpect(at).toEqual({ ...seen[0]!, sideways: false });
  }, 60_000);
}

/** the board's columns, as the distinct places a team's column starts, and the widest of them */
async function board(width: number) {
  const page = await open("sessions.html", width);
  try {
    await expect(page.locator("#board section.team").first()).toBeVisible({ timeout: 10_000 });
    return await page.evaluate(() => {
      const boxes = [...document.querySelectorAll("#board section.team")].map((el) => el.getBoundingClientRect());
      return { columns: new Set(boxes.map((b) => Math.round(b.left))).size, widest: Math.round(Math.max(...boxes.map((b) => b.width))) };
    });
  } finally {
    await page.close();
  }
}

test("a wider window shows more team columns, and a board of two teams does not stretch across it", async () => {
  const [narrow, desk, wide] = [await board(1440), await board(1920), await board(3840)];
  bunExpect(desk.columns).toBeGreaterThan(narrow.columns);
  bunExpect(wide.columns).toBeGreaterThan(desk.columns * 2);
  crews = 2;
  try {
    // two teams had the whole of a 3840 px window between them, some 1,150 px a column
    bunExpect((await board(3840)).widest).toBeLessThan(640);
  } finally {
    crews = CREWS.length;
  }
}, 60_000);

test("an open conversation stands on a wide window's middle, and starts at the page's left on a 1440 px one", async () => {
  const placed = async (width: number) => {
    const page = await open("sessions.html#harbor-1", width);
    try {
      await expect(page.locator("#thread .rv")).toBeVisible({ timeout: 15_000 });
      return await page.evaluate(() => {
        const t = document.querySelector("#thread")!.getBoundingClientRect(), title = document.querySelector("#title")!.getBoundingClientRect();
        return { off: Math.round(t.left + t.width / 2 - innerWidth / 2), left: Math.round(t.left - title.left) };
      });
    } finally {
      await page.close();
    }
  };
  bunExpect(Math.abs((await placed(3840)).off)).toBeLessThanOrEqual(2);
  bunExpect((await placed(1440)).left).toBe(0);
}, 60_000);

test("a 3840 px window sets two teams side by side, six providers in a row, and Activity's and Settings' second half beside the first", async () => {
  const seen = async (width: number) => {
    const out: Record<string, unknown> = {};
    for (const [file, ready] of [["teams.html", "#bands .band"], ["providers.html", "#cloud > *"], ["activity.html", "#latestH"], ["settings.html", "#sys"]] as const) {
      const page = await open(file, width);
      try {
        await expect(page.locator(ready).first()).toBeVisible({ timeout: 15_000 });
        Object.assign(out, await page.evaluate(() => {
          const box = (sel: string) => document.querySelector(sel)!.getBoundingClientRect();
          /** how many of these stand in the first one's row */
          const row = (sel: string) => { const tops = [...document.querySelectorAll(sel)].map((el) => Math.round(el.getBoundingClientRect().top)); return tops.filter((t) => t === tops[0]).length; };
          if (location.pathname.endsWith("teams.html")) return { teams: row("#bands .band") };
          if (location.pathname.endsWith("providers.html")) return { providers: row("#cloud > *") };
          if (location.pathname.endsWith("activity.html")) return { requestsBeside: box("#latestH").top < box("#strip").bottom };
          return { partsBeside: box("#sys").top < box("#flow").bottom };
        }));
      } finally {
        await page.close();
      }
    }
    return out;
  };
  bunExpect(await seen(1920)).toEqual({ teams: 1, providers: 3, requestsBeside: false, partsBeside: false });
  bunExpect(await seen(3840)).toEqual({ teams: 2, providers: 6, requestsBeside: true, partsBeside: true });
}, 90_000);
