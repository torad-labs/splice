// Goal: a test that reads what the page draws (a box, a computed style, a scroll size) measures the element the page has,
// never one a redraw already replaced, however often the page redraws.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { live } from "./live.ts";

let browser: Browser;
let page: Page;
beforeAll(async () => {
  browser = await chromium.launch();
  page = await browser.newPage();
  // a page that replaces the measured element's markup on every turn of its event loop
  await page.setContent(`<div id="box"></div><script>
    const box = document.getElementById("box");
    const draw = () => { box.innerHTML = '<span class="q" style="display:inline-block;width:40px;height:10px">x</span>'; setTimeout(draw, 0); };
    draw();
  </script>`);
}, 60_000);
afterAll(async () => {
  await browser.close();
});

const READS = 300;

test("a box read on a page that redraws without pause is the drawn element's box on every read", async () => {
  const q = page.locator("#box .q");
  for (let i = 0; i < READS; i++) expect(await live(q, (el) => el.getBoundingClientRect().width)).toBe(40);
}, 60_000);

test("a computed style read on a page that redraws without pause is the drawn element's style on every read", async () => {
  const q = page.locator("#box .q");
  for (let i = 0; i < READS; i++) expect(await live(q, (el) => getComputedStyle(el).display)).toBe("inline-block");
}, 60_000);
