// Each "By session" line in Requests' Cost reads as a bar however small its share (lead walk of the installed console, Oct 29:
// at 1440 the column held about 70px, and a session worth a few percent of the dearest drew a hollow sliver a few px wide
// beside its figure, which read as a "0"). Every line's bar sits on a faint track as wide as its column, the way the band's
// other bar rows (Outcomes, Times, Largest) sit on theirs, so a short bar reads as the start of a bar. A line with no bar
// draws no track. Over several windows of generated shares, all on a plan, all paid, and mixed, through the real page
// against a stub daemon, measured in the rendered DOM.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
type Share = { paid: number; plan: number };
const row = (i: number, s: Share) => ({
  turn: `t${i}`, turn_id: `t${i}`, ts: now - (i + 1) * 60_000, outcome: "ok", model: "gpt-6.1-sol", session: `s${i}aaaaaaa`, session_id: `s${i}aaaaaaa-1`,
  total: 9000, attempts: 1, retries: 0, account: "primary", in_tokens: 1000, out_tokens: 10, cached_tokens: 0,
  cost_usd: s.paid > 0 ? s.paid : null, cost_reason: s.paid > 0 ? null : "plan", ...(s.paid > 0 ? {} : { plan_value_usd: s.plan }),
});
// one window per case: the dearest session first, then shares down to a few percent of it, as his 24 hours read
const WINDOWS: { name: string; shares: Share[] }[] = [
  { name: "all on a plan", shares: [1387, 313, 113, 86.34, 64.75, 40, 12].map((plan) => ({ paid: 0, plan })) },
  { name: "all paid", shares: [920, 210, 48, 30, 9.5, 4].map((paid) => ({ paid, plan: 0 })) },
  { name: "mixed", shares: [{ paid: 0, plan: 640 }, { paid: 120, plan: 0 }, { paid: 0, plan: 22 }, { paid: 15, plan: 0 }, { paid: 0, plan: 7.5 }, { paid: 0, plan: 3 }] },
];
let rows: Record<string, unknown>[] = [];
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    const answers: Record<string, unknown> = {
      "/api/heads": { heads: [{ key: "codex", label: "codex" }] },
      "/api/models": { heads: [] },
      "/api/sessions": { sessions: [] },
      "/api/keys": { keys: [] },
      "/api/topology": { topology: { providers: {} } },
      "/api/heads/codex/capture": { enabled: false, retention_days: 7 },
      "/api/config": {},
      "/api/perf/turns": { heads: [{ key: "codex", label: "codex", count: rows.length, returned: rows.length, truncated: false, rows, usage: { models: [], accounts: [], sessions: [] } }] },
    };
    if (path in answers) return Response.json(answers[path]);
    const file = join(consoleDir, path === "/" ? "requests.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : Response.json({}, { status: 404 });
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

test("at 1440 every By session line's bar sits on a track as wide as its column, whatever its share", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    const failures: string[] = [];
    for (const w of WINDOWS) {
      rows = w.shares.map((s, i) => row(i, s));
      await page.goto(`http://127.0.0.1:${server.port}/requests.html`);
      // the band draws three blocks of bar lines (Slowest, Largest, By session); this one is headed "By session"
      const bySession = page.locator(".bys", { has: page.locator(".byh", { hasText: "By session" }) });
      await bySession.locator(".byr .bb").nth(4).waitFor({ timeout: 15_000 });
      await page.waitForTimeout(400); // the bars grow to their widths
      const m = await bySession.evaluate((grid) => {
        const col = parseFloat(getComputedStyle(grid).gridTemplateColumns.split(" ")[1] ?? "0");
        const alpha = (c: string) => (c === "transparent" ? 0 : /rgba?\(([^)]+)\)/.test(c) ? Number(c.match(/rgba?\(([^)]+)\)/)![1]!.split(",")[3] ?? 1) : 1);
        return {
          col,
          lines: [...grid.querySelectorAll<HTMLElement>(".byr .bb")].map((bb) => ({
            bar: bb.children.length > 0,
            width: bb.getBoundingClientRect().width,
            height: bb.getBoundingClientRect().height,
            track: alpha(getComputedStyle(bb).backgroundColor),
          })),
        };
      });
      m.lines.forEach((l, i) => {
        if (!l.bar) {
          if (l.track > 0) failures.push(`${w.name}: line ${i} has no bar but draws a track`);
          return;
        }
        if (l.track <= 0) failures.push(`${w.name}: line ${i}'s bar sits on no track`);
        if (l.width < m.col - 1) failures.push(`${w.name}: line ${i}'s track is ${l.width.toFixed(1)}px of a ${m.col.toFixed(1)}px column`);
        if (l.height < 4) failures.push(`${w.name}: line ${i}'s track is ${l.height.toFixed(1)}px tall`);
      });
    }
    expect(failures).toEqual([]);
  } finally {
    await page.close();
  }
}, 120_000);
