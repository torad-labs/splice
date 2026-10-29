// A figure that counts from the number last drawn in its place to the new one only ever shows numbers between the two
// (desk look at Usage, Oct 29: one frame about four seconds into the load drew four rows of Where it went with negative
// dollars, "$-23,491" among them, between two positive figures). Chrome can hand a frame a timestamp from before the
// moment the count began, when the page was busy as it drew; a count must read that as its start, never as a step back.
// Over generated pairs of figures and frame lags, through the console's own count (kit.js, as every page loads it) in a
// real page, with the frame clock as the one stand-in.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser } from "@playwright/test";
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
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox", "--disable-background-networking"] });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

// pairs of figures as Where it went draws them, rising and falling, each with how far the next frame's timestamp lags the
// moment the count began (0 is a frame on time)
const CASES = [
  { from: "$1,276", to: "$2,486", lag: 120 }, { from: "$2,670", to: "$512", lag: 200 }, { from: "$0.05", to: "$12.40", lag: 60 },
  { from: "$9,910", to: "$23,491", lag: 239 }, { from: "56,501", to: "56,620", lag: 30 }, { from: "$300", to: "$1,349", lag: 0 },
];

test("a counting figure never shows a number outside the two it counts between, however late its frames' clock runs", async () => {
  const page = await browser.newPage();
  try {
    await page.goto(`http://127.0.0.1:${server.port}/settings.html`);
    await page.waitForFunction(() => typeof (window as unknown as { tweenFigure?: unknown }).tweenFigure === "function");
    const seen = await page.evaluate(async (cases) => {
      const w = window as unknown as { tweenFigure: (el: HTMLElement) => void };
      const real = window.requestAnimationFrame.bind(window);
      const num = (s: string) => parseFloat(s.replace(/[^\d.-]/g, ""));
      const out: { from: string; to: string; lag: number; shown: string[] }[] = [];
      let n = 0;
      for (const c of cases) {
        const el = document.createElement("b");
        el.dataset.tn = `bounds-${n++}`;
        document.body.append(el);
        el.textContent = c.from;
        w.tweenFigure(el);
        // the frames' clock: each frame's timestamp is [lag] behind the moment the count began, then catches up
        let first = true;
        window.requestAnimationFrame = (cb) => real((t) => { const at = first ? performance.now() - c.lag : t; first = false; cb(at); });
        el.textContent = c.to;
        w.tweenFigure(el);
        const shown: string[] = [el.textContent ?? ""];
        await new Promise<void>((done) => { const look = () => { shown.push(el.textContent ?? ""); if (el.textContent === c.to) done(); else real(look); }; real(look); });
        window.requestAnimationFrame = real;
        out.push({ ...c, shown: shown.filter((s) => Number.isFinite(num(s))) });
        el.remove();
      }
      return out;
    }, CASES);
    const failures: string[] = [];
    for (const c of seen) {
      const a = parseFloat(c.from.replace(/[^\d.]/g, "")), b = parseFloat(c.to.replace(/[^\d.]/g, ""));
      const lo = Math.min(a, b), hi = Math.max(a, b);
      const off = c.shown.filter((s) => { const v = parseFloat(s.replace(/[^\d.-]/g, "")); return v < lo - 1e-9 || v > hi + 1e-9; });
      if (off.length) failures.push(`${c.from} to ${c.to}, frames ${c.lag} ms late: showed ${off.join(", ")}`);
    }
    expect(failures).toEqual([]);
  } finally {
    await page.close();
  }
}, 60_000);
