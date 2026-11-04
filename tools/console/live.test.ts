// Goal: a test that reads what the page draws (a box, a computed style, a scroll size) measures the element the page has,
// never one a redraw already replaced, on a page that redraws in bursts and holds still between them.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { live } from "./live.ts";

const READS = 300;
// why: each replacement is its own zero-delay timer, which Chromium holds to at least 4 ms once timers nest five deep, so
// ten in a row churn for about 38 ms, long enough that a two-trip read often straddles one
const BURST = 10;
// why: a quiet spell several times longer than three protocol trips on a CI runner busy compiling, so a read always
// lands in one; a 4 ms spell let a loaded runner miss all 50 tries
const QUIET_MS = 200;
let browser: Browser;
let page: Page;
beforeAll(async () => {
  browser = await chromium.launch();
  page = await browser.newPage();
  // a page that redraws the way a console page does when reads land, only far more often: bursts of back-to-back
  // replacements of the measured element's markup, each burst followed by a short quiet spell. A page that never holds
  // still for one read cannot be measured at all, so it is no part of the goal.
  await page.setContent(`<div id="box"></div><script>
    const box = document.getElementById("box");
    let drawn = 0;
    const draw = () => {
      box.innerHTML = '<span class="q" style="display:inline-block;width:40px;height:10px">x</span>';
      drawn++;
      setTimeout(draw, drawn % ${BURST} === 0 ? ${QUIET_MS} : 0);
    };
    draw();
  </script>`);
}, 60_000);
afterAll(async () => {
  await browser.close();
});

test("a box read on a page that redraws in bursts is the drawn element's box on every read", async () => {
  const q = page.locator("#box .q");
  for (let i = 0; i < READS; i++) expect(await live(q, (el) => el.getBoundingClientRect().width)).toBe(40);
}, 60_000);

test("a computed style read on a page that redraws in bursts is the drawn element's style on every read", async () => {
  const q = page.locator("#box .q");
  for (let i = 0; i < READS; i++) expect(await live(q, (el) => getComputedStyle(el).display)).toBe("inline-block");
}, 60_000);
