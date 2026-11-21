// A figure that counts from the number last drawn in its place to the new one only ever shows numbers between the two
// (a look at Usage: one frame about four seconds into the load drew four rows of Where it went with negative
// dollars, "$-23,491" among them, between two positive figures). Chrome can hand a frame a timestamp from before the
// moment the count began, when the page was busy as it drew; a count must read that as its start, never as a step back.
// Over generated pairs of figures and frame lags, through the Night Deck kit's own count (Deck.roll, deck.js, which
// every page loads) in a real page, with the frame clock as the one stand-in. (Rewritten from the old kit's count,
// which went with the old pages.)
import { afterAll, beforeAll, expect, test } from "bun:test";
import { type Browser } from "@playwright/test";
import { launch, stub, type Stub } from "./deck-stub.ts";

let s: Stub;
let browser: Browser;
beforeAll(async () => { s = stub({}); browser = await launch(); }, 20_000);
afterAll(async () => { await browser?.close(); s?.stop(); });

// pairs of figures as Activity's Where it went draws them, rising and falling, each with how far the next frame's
// timestamp lags the moment the count began (0 is a frame on time)
const CASES = [
  { from: 1276, to: 2486, lag: 120 }, { from: 2670, to: 512, lag: 200 }, { from: 5, to: 1240, lag: 60 },
  { from: 9910, to: 23491, lag: 239 }, { from: 56501, to: 56620, lag: 30 }, { from: 300, to: 1349, lag: 0 },
];

test("a counting figure never shows a number outside the two it counts between, however late its frames' clock runs", async () => {
  const page = await browser.newPage();
  try {
    await page.goto(`${s.url}/providers.html`);
    await page.waitForFunction(() => typeof (window as unknown as { Deck?: { roll?: unknown } }).Deck?.roll === "function");
    const seen = await page.evaluate(async (cases) => {
      const D = (window as unknown as { Deck: { roll: (el: HTMLElement, to: number) => void } }).Deck;
      const real = window.requestAnimationFrame.bind(window);
      const num = (t: string) => parseFloat(t.replace(/[^\d.-]/g, ""));
      const out: { from: number; to: number; lag: number; shown: number[] }[] = [];
      for (const c of cases) {
        const el = document.createElement("b");
        document.body.append(el);
        D.roll(el, c.from);
        // the frames' clock: the first frame's timestamp is [lag] behind the moment the count began, then catches up
        let first = true;
        window.requestAnimationFrame = (cb) => real((t) => { const at = first ? performance.now() - c.lag : t; first = false; cb(at); });
        D.roll(el, c.to);
        window.requestAnimationFrame = real;
        const shown: number[] = [num(el.textContent ?? "")];
        const want = c.to.toLocaleString("en-US");
        await new Promise<void>((done) => { const look = () => { shown.push(num(el.textContent ?? "")); if (el.textContent === want) done(); else real(look); }; real(look); });
        out.push({ ...c, shown: shown.filter((v) => Number.isFinite(v)) });
        el.remove();
      }
      return out;
    }, CASES);
    const failures: string[] = [];
    for (const c of seen) {
      const lo = Math.min(c.from, c.to), hi = Math.max(c.from, c.to);
      const off = c.shown.filter((v) => v < lo || v > hi);
      if (off.length) failures.push(`${c.from} to ${c.to}, frames ${c.lag} ms late: showed ${off.join(", ")}`);
    }
    expect(failures).toEqual([]);
  } finally {
    await page.close();
  }
}, 60_000);
