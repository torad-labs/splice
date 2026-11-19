// rv-page: the reader's own page, served beside the console at /reply-view/index.html, shows one session through the
// reader and its connector: its name, and its history as the reader draws it, in night and day (the console's own theme
// choice), at 375 and 1440 px, under a strict CSP. With no session named it lists the sessions to open; a browser that is
// not signed in is told how to sign in. It opens at the newest message and stays pinned there while the person has not
// scrolled up. (A reply streaming into the page and settling is rv-settle's check.)
import { afterAll, beforeAll, expect, test } from "bun:test";
import { expect as pw, type Browser, type Page } from "@playwright/test";
import { browser, DESK, open, PHONE, readable, SESSION, SESSIONS, spills, stub, THEMES, WORLD, type Msg, type Stub } from "./lib/rv.ts";

// A made-up 40-message session (bbbb2222, "map-builder" in SESSIONS), long enough that the page scrolls at both widths.
const LONG = "bbbb2222";
const long: Msg[] = Array.from({ length: 40 }, (_, i) => ({
  index: i,
  role: i % 2 ? "assistant" : "user",
  ts: 1,
  text: `Made-up message ${i}. ` + "The tide table has rows and columns to read. ".repeat(6),
  ...(i % 2 ? { message: `msg_long${i}`, model: "claude-opus-5-5" } : {}),
}));

let b: Browser;
let st: Stub;
beforeAll(async () => {
  b = await browser();
  st = stub({ transcripts: { [SESSION]: [...WORLD], [LONG]: [...long] }, answers: { "/api/sessions": SESSIONS } });
}, 30_000);
afterAll(async () => {
  await b?.close();
  st?.stop();
});

const PAGE = `/reply-view/index.html?session=${SESSION}`;

for (const size of [PHONE, DESK]) {
  test(`at ${size.width} px, night and day, the page shows the session through the reader with nothing spilling`, async () => {
    const bg: Record<string, string> = {};
    for (const theme of THEMES) {
      const o = await open(b, st, PAGE, size, theme);
      try {
        await pw(o.page.locator(".rv .rv-assistant", { hasText: "split on tabs only" })).toBeVisible({ timeout: 10_000 });
        await pw(o.page.locator("body")).toContainText("api-fixer");
        const text = await readable(o.page, "body");
        for (const never of ["system-reminder", "Made-up reminder", '{"', "command-name"]) expect(text, `shows ${never}`).not.toContain(never);
        expect(await o.page.locator(".rv details.rv-tool").evaluateAll((els) => els.every((d) => !(d as HTMLDetailsElement).open))).toBe(true);
        expect(await o.page.evaluate(() => document.documentElement.dataset.theme)).toBe(theme);
        expect(await spills(o.page)).toEqual([]);
        bg[theme] = await o.page.evaluate(() => getComputedStyle(document.body).backgroundColor);
        expect(o.errors).toEqual([]);
        expect(await o.csp()).toEqual([]);
      } finally {
        await o.close();
      }
    }
    expect(bg.night).not.toBe(bg.day);
  }, 120_000);
}

test("with no session named it lists the sessions, each opening its conversation", async () => {
  const o = await open(b, st, "/reply-view/index.html", DESK, "day");
  try {
    const link = o.page.locator(`a[href*="session=${SESSION}"]`);
    await pw(link).toContainText("api-fixer", { timeout: 10_000 });
    await pw(o.page.locator('a[href*="session=bbbb2222"]')).toContainText("map-builder");
    await link.click();
    await pw(o.page.locator(".rv .rv-assistant", { hasText: "split on tabs only" })).toBeVisible({ timeout: 10_000 });
    expect(o.errors).toEqual([]);
  } finally {
    await o.close();
  }
}, 60_000);

test("a browser that is not signed in is told how to sign in", async () => {
  const o = await open(b, st, PAGE, PHONE, "night", { key: false });
  try {
    await pw(o.page.locator("body")).toContainText("This browser is not signed in to splice.", { timeout: 10_000 });
    await pw(o.page.locator("body")).toContainText("splice console");
    expect(await spills(o.page)).toEqual([]);
    expect(o.errors).toEqual([]);
  } finally {
    await o.close();
  }
}, 60_000);

// Where the newest drawn message ends against the bottom of the window, and whether "Jump to newest" shows. A page
// pinned to the newest line has it within 2 px of the bottom and the button hidden.
const where = (p: Page) =>
  p.evaluate(() => {
    const all = [...document.querySelectorAll(".rv [data-index]")];
    const last = all.reduce<Element | null>((a, e) => (!a || +e.getAttribute("data-index")! > +a.getAttribute("data-index")! ? e : a), null);
    const e = document.scrollingElement!;
    return {
      newestBelow: last ? Math.round(last.getBoundingClientRect().bottom - innerHeight) : null,
      fromBottom: Math.round(e.scrollHeight - e.scrollTop - e.clientHeight),
      jump: !(document.getElementById("jump") as HTMLElement).hidden,
    };
  });
const pinned = async (p: Page, what: string) => {
  const v = await where(p);
  expect(v.newestBelow, `${what}: no message drawn`).not.toBeNull();
  expect(v.newestBelow!, `${what}: the newest message ends ${v.newestBelow} px below the window (${v.fromBottom} px from the bottom)`).toBeLessThanOrEqual(2);
  expect(v.jump, `${what}: "Jump to newest" shows though the person never scrolled`).toBe(false);
};

// The page opens at the newest message and stays pinned while the person has not scrolled up: the page growing under
// it (the console's web fonts arriving after the first draw, a long history drawn) does not unpin it, and a new message
// is followed. Round 2's feature review measured these as its handed-over case; they run here now, on the Mac.
for (const size of [PHONE, DESK]) {
  test(`at ${size.width} px the page opens at the newest message and follows a new one`, async () => {
    for (const id of [SESSION, LONG]) {
      const o = await open(b, st, `/reply-view/index.html?session=${id}`, size, "night");
      try {
        await o.page.waitForSelector(".rv [data-index]", { timeout: 10_000 });
        await o.page.waitForTimeout(2000);
        await pinned(o.page, `${id} 2 s after it opened`);
        if (id === LONG) {
          st.transcripts[LONG] = [...long, { index: 40, role: "user", ts: 2, text: "Made-up newest question from the person." }];
          st.push(LONG, "turn", { turn: "T-pin", message: "msg_pin", agent: null, model: "claude-opus-5-5", head: "claude-splice", compact: false, at: 2 });
          await o.page.waitForSelector('.rv [data-index="40"]', { timeout: 10_000 });
          await o.page.waitForTimeout(1500);
          await pinned(o.page, `${id} 1.5 s after a new message arrived`);
        }
        expect(o.errors).toEqual([]);
        expect(await o.csp()).toEqual([]);
      } finally {
        st.transcripts[LONG] = [...long];
        await o.close();
      }
    }
  }, 120_000);
}
