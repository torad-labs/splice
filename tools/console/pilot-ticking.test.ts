// THE PILOT NEVER REPORTS A LINE THAT ONLY TICKS AS NEW WORDS. An agent acting on a person's console must never read
// "done" for a press the page ignored (pilot.js's header), and a line made only of figures and their units ("52k / 200k",
// "3s ago", "13 min") changes as time passes, not because of an act: a Sessions row reads "12 min" before the press and
// "13 min" after it, and that is not a change the act made. The rule is PILOT.ticking, read here through the real page in
// Chromium, since pilot.js loads on every console page. Review of PR 450, round 2: the regex's replacement (CodeQL
// js/redos, alert 31) dropped the units the old pattern spelled from two ("min" from "m" and "in"), so a minutes line
// became new words.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return path !== "/" && existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});

let browser: Browser;
let page: Page;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox"] });
  page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  await page.goto(`http://127.0.0.1:${server.port}/requests.html`, { waitUntil: "load" });
}, 30_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

// pilot.js declares PILOT with `const` at script level, a binding of the page's global scope and no property of window,
// so the page's code names it bare.
declare const PILOT: { ticking: (line: string) => boolean };
const ticking = (line: string) => page.evaluate<boolean, string>((l) => PILOT.ticking(l), line);

// What the console draws as a figure and its unit: DRAW.ms ("3 min 12 s"), spanWord ("12 min", "3 w"), dur ("5 min"),
// a window ("52k / 200k"), an age ("3s ago", "5 min ago"), what is left ("4 min left", "in 3 mins"), a share ("100%").
const TICKS = [
  "52k / 200k", "3s ago", "1m 12s", "1h2m", "0:42", "13 min", "3 min 12 s", "5 min ago", "4 min left", "in 3 mins",
  "12 tokens", "3 w", "100%", "1.5 MB of 2 GB", "2 d 4 h",
];
// A line with a word in it is what an act may have changed, however many figures stand beside the word.
const WORDS = ["Alpha One", "No sessions yet", "13 minutes", "Started 3 min ago", "Try now", "3 min · held", "running"];

for (const line of TICKS) {
  test(`"${line}" only ticks, so it is never new words`, async () => {
    expect(await ticking(line)).toBe(true);
  }, 30_000);
}
for (const line of WORDS) {
  test(`"${line}" is words the act may have changed`, async () => {
    expect(await ticking(line)).toBe(false);
  }, 30_000);
}
