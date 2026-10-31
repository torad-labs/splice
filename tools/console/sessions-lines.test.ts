// The lines of an open conversation as he reads them (Oct 27; Marcos, Oct 26: "the tool call ui is very busy"). A run of
// the same notice from the client stands as one line with its count, which opens every text in place; and a call's file
// path longer than its line keeps the file's own name whole while the folders before it give way. Through the real
// Sessions page against a stub daemon, read off the rendered DOM.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const MIN = 60_000;
const session = {
  session_id: "s-1", name: "scribe", head: "alpha", route: "head", availability: "live", status: "idle", cwd: "/work/atlas/s-1", title: null,
  last: { role: "assistant", tool: null, text: "scribe said its last line.", ts: now - MIN }, updated_at: new Date(now - MIN).toISOString(),
  project: { id: "/work/atlas", name: "atlas", label: "atlas", source: "folder", folder: "/work" },
};
const FILE = "quarterly-report-final-v2.json";
const LONG = `/var/lib/shared-data/${"nested-folder/".repeat(14)}${FILE}`;
const NOTICES = 8;
const transcript = {
  path: "/home/me/.claude/projects/atlas/s-1.jsonl", earlier: null, moves: [], unmoved: [], unparseable_lines: 0,
  messages: [
    { index: 0, role: "user", text: "Check the report.", ts: now - 9 * MIN },
    { index: 1, role: "assistant", text: "I will read the report first.", ts: now - 8 * MIN },
    { index: 2, role: "assistant", tool: "Read", tool_use_id: "t1", text: JSON.stringify({ file_path: LONG }), ts: now - 7 * MIN },
    { index: 3, role: "tool", tool_use_id: "t1", text: "{}", ts: now - 7 * MIN + 1_000 },
    ...Array.from({ length: NOTICES }, (_, i) => ({ index: 4 + i, role: "system", text: `[Cross-session delivery notice] message ${i + 1} was handed over`, ts: now - 6 * MIN + i * 1_000 })),
    { index: 4 + NOTICES, role: "assistant", text: "The report is read.", ts: now - 2 * MIN },
  ],
};
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/sessions") return Response.json({ reading: true, sessions: [session] });
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-alpha" }] });
    if (/^\/api\/sessions\/[^/]+\/transcript$/.test(path)) return Response.json(transcript);
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

async function opened() {
  const page = await browser.newPage({ viewport: { width: 2548, height: 1240 }, deviceScaleFactor: 1.5 });
  await page.goto(`http://127.0.0.1:${server.port}/sessions.html#s-1`);
  await expect(page.locator("section.pane header .who .name")).toBeVisible({ timeout: 15_000 });
  await expect(page.locator(".pane .log .msg.agent").first()).toBeVisible({ timeout: 15_000 });
  return page;
}

test("eight delivery notices side by side read as one line with their count, and open to all eight", async () => {
  const page = await opened();
  try {
    const lines = page.locator(".pane .log .sysline");
    await expect(lines).toHaveCount(1);
    await expect(lines.first()).toContainText("Delivery notice");
    await expect(lines.first().locator(".times")).toHaveText(`×${NOTICES}`);
    await lines.first().locator(".sysdisc").click();
    await expect(lines.first().locator(".sysbody")).toHaveCount(NOTICES);
    await expect(lines.first()).toContainText(`message ${NOTICES} was handed over`);
  } finally { await page.close(); }
});

test("a file path longer than its line keeps the file's name whole and gives way in the folders before it", async () => {
  const page = await opened();
  try {
    const name = page.locator(".pane .trail .tsum.pathed .tfile");
    await expect(name).toHaveText(FILE);
    const m = await page.evaluate(() => {
      const sum = document.querySelector(".pane .trail .tsum.pathed") as HTMLElement;
      const dir = sum.querySelector(".tdir") as HTMLElement, file = sum.querySelector(".tfile") as HTMLElement;
      return { fileRight: file.getBoundingClientRect().right, sumRight: sum.getBoundingClientRect().right, dirClipped: dir.scrollWidth > dir.clientWidth + 1 };
    });
    bunExpect(m.dirClipped).toBe(true); // the folders did not fit the line, so they are the part that gave way
    bunExpect(m.fileRight).toBeLessThanOrEqual(m.sumRight + 1); // and the file's name stands wholly inside it
  } finally { await page.close(); }
});
