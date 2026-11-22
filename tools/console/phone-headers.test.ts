// A PHONE'S HEADER STACKS, ON EVERY PAGE. The kit stacks the pages that use `.head` (Accounts, Sessions) at 48rem;
// Activity, Providers, Settings and Teams kept their own header in one row, so at 375 px the title, a control and the
// status fought for 327 px and the status ran past the screen's edge (Activity's window choice pushed it out entirely).
// Through the real pages at 375 px against a stub daemon: each header's status sits below the title, and neither the
// header nor the status runs past the page's right edge. Accounts and Sessions are here as the pages that already had
// this treatment, so the check is the same one on all six.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser } from "@playwright/test";
import { existsSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const pages = readdirSync(consoleDir).filter((name) => name.endsWith(".html")).sort();
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
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox"] });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

interface Header {
  /** How far the header's own content runs past its box. */
  pastBox: number;
  /** How far the status runs past the page's right edge. */
  statusPastPage: number;
  /** Whether the status starts below the header's title block. */
  stacked: boolean;
  /** The boxes the three numbers were read from, so a failure says where it is. */
  boxes: { page: number; header: number[]; status: number[]; title: number[] };
}

for (const name of pages) {
  test(`${name}'s header stacks at 375 px and stays on the page`, async () => {
    const page = await browser.newPage({ viewport: { width: 375, height: 812 } });
    try {
      await page.goto(`http://127.0.0.1:${server.port}/${name}`, { waitUntil: "load" });
      const header: Header = await page.evaluate(() => {
        const head = document.querySelector("header");
        const status = head?.querySelector(".status");
        const title = head?.firstElementChild;
        if (!head || !status || !title) throw new Error("the page has no header, status and title");
        const box = head.getBoundingClientRect(), said = status.getBoundingClientRect(), top = title.getBoundingClientRect();
        const rect = (r: DOMRect) => [Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom)];
        return {
          pastBox: Math.round(head.scrollWidth - head.clientWidth),
          statusPastPage: Math.round(said.right - document.documentElement.clientWidth),
          stacked: said.top >= top.bottom - 0.5,
          boxes: { page: document.documentElement.clientWidth, header: rect(box), status: rect(said), title: rect(top) },
        };
      });
      const where = JSON.stringify(header.boxes);
      expect(header.stacked, `${name}: the status stands beside the title at 375 px ${where}`).toBe(true);
      expect(header.pastBox, `${name}: the header runs past its box ${where}`).toBeLessThanOrEqual(0);
      expect(header.statusPastPage, `${name}: the status runs past the page ${where}`).toBeLessThanOrEqual(0);
    } finally {
      await page.close();
    }
  }, 30_000);
}
