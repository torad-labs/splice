// A row's time bar keeps its room on a wide board whatever a working session is doing (lead walk of the installed console,
// Oct 29: at 3828 in dark mode, the moment one session showed a long line of what it was doing, every row's bar on the board
// narrowed to about a third and the axis over it dropped to "s min h d w"). The board gives each row at least 16rem of bar,
// and a long name or a long line of words gives way first (Sessions' finish pass, Oct 29). Over generated name and word
// lengths, through the real Sessions page against a stub daemon, measured in the rendered DOM.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const BAR_MIN_REM = 16;
const row = (n: number, name: string, p: string, over: Record<string, unknown> = {}) => ({
  session_id: `s-${n}`, name, head: "alpha", route: "head", availability: "live", status: "idle",
  cwd: `/work/${p}/${n}`, title: null, last: { role: "assistant", tool: null, text: "Said its last line.", ts: Date.now() - n * 60_000 },
  updated_at: new Date(Date.now() - n * 60_000).toISOString(),
  project: { id: `/work/${p}`, name: p, label: p, source: "folder", folder: "/work" }, ...over,
});
// names and lines of words of the lengths the live board showed the moment its bars narrowed (lead walk, Oct 29, 17:55 CT)
const NAMES = ["postbox1", "harbor-readonly-review-ledger-a9f3", "Model prefill speed on two × large cards, run 9"];
const WORDS = ["Read the freshness module", "Run the worker package tests", "Read the group dot's exit and the queue it feeds"];
// one board per case, in three projects so it stands in columns as his does: a working session with a line of words under a
// name, beside idle rows
const board = (name: string, words: string) => [
  row(1, name, "atlas", { status: "working", last: { role: "assistant", tool: "Bash", text: words, ts: Date.now() - 5_000 } }),
  row(2, "fen-evening", "atlas"), row(3, "postbox1", "atlas"),
  row(4, "kiln-north-star-repairs", "birch"), row(5, "fern-main-6b", "birch"),
  row(6, "larks-optimization", "cobalt"), row(7, "checkr", "cobalt"),
];
let sessions: Record<string, unknown>[] = board(NAMES[0]!, WORDS[0]!);
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/sessions") return Response.json({ sessions, reading: true });
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-splice" }] });
    // the working session has a turn running through splice, so its row carries Stop beside its command, as live rows do
    if (path === "/api/heads/alpha/turns/live") return Response.json({ turns: sessions.filter((s) => s.status === "working").map((s) => ({ session: s.session_id, model: "m" })) });
    if (path === "/api/models") return Response.json({ heads: [] });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
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

// every name crossed with every line of words, so the longest line meets the longest name
const CASES = NAMES.flatMap((name) => WORDS.map((words) => ({ name, words })));

test("at his width every row keeps at least 16rem of bar, and the axis keeps its whole words, whatever a working session is doing", async () => {
  const page = await browser.newPage({ viewport: { width: 3828, height: 1860 } });
  try {
    const failures: string[] = [];
    for (const c of CASES) {
      sessions = board(c.name, c.words);
      await page.goto(`http://127.0.0.1:${server.port}/sessions.html`);
      await page.locator(".list:not(.ghosts) .s > .top").nth(3).waitFor({ timeout: 15_000 });
      await page.waitForTimeout(300); // the tracks are fitted once the rows are drawn
      const m = await page.evaluate(() => {
        const rem = parseFloat(getComputedStyle(document.documentElement).fontSize);
        const tops = [...document.querySelectorAll<HTMLElement>(".list:not(.ghosts) .s > .top")];
        return {
          words: tops.map((t) => (t.querySelector(".last") as HTMLElement | null)?.innerText.trim() ?? "").filter(Boolean).length,
          bars: tops.map((t) => parseFloat(getComputedStyle(t).gridTemplateColumns.split(" ")[2] ?? "0") / rem),
          axis: [...document.querySelectorAll<HTMLElement>(".sx-axis")].map((a) => a.innerText.replace(/\s+/g, " ").trim()),
        };
      });
      // the case is only a case if the working session shows its words
      if (m.words < 1) failures.push(`"${c.name}" doing "${c.words}": the working row showed no words`);
      const narrow = Math.min(...m.bars);
      if (narrow < BAR_MIN_REM - 0.05) failures.push(`"${c.name}" doing "${c.words}": a bar track of ${narrow.toFixed(1)}rem`);
      if (!m.axis.length || !m.axis.every((a) => a.startsWith("1 s"))) failures.push(`"${c.name}" doing "${c.words}": axis read "${m.axis[0]}"`);
    }
    expect(failures).toEqual([]);
  } finally {
    await page.close();
  }
}, 120_000);
