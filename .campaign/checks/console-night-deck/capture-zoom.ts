// Pictures of the console on a 3840 x 2160 screen as the browser's zoom shows it: at 100%, at 80% (the page is given
// 4800 px) and at 125% (3072 px), and on a 1920 px window, to see that type keeps its size, a wider window shows more,
// and zoom scales the page as it scales any page (Nov 21, 2026). Each zoom is taken the way the browser zooms: the
// browser is started at that scale factor with a window of 3840 / zoom px, and nothing is emulated afterwards. (A
// window emulated at 4800 px and a factor of 0.8 comes back half unpainted, which the browser itself never shows.)
// Chromium on the machine it is run on, the stub daemon with a made-up world. Everything shown is made up.
//
//   bun .campaign/checks/console-night-deck/capture-zoom.ts --out <folder>
import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { chromium } from "@playwright/test";
import { daemon, HOUR, MIN, NOW, ROOT, session, world } from "./lib/deck.ts";

const out = process.argv[process.argv.indexOf("--out") + 1] || "build/night-deck-captures/zoom-here";
const dir = join(ROOT, out);
rmSync(dir, { recursive: true, force: true });
mkdirSync(dir, { recursive: true });

// eleven teams' worth of sessions, as a busy machine has, so the board has columns to lay out
const names = ["harbor", "lighthouse", "tide", "chart", "dock", "pier", "buoy", "ferry", "quay", "reef", "delta"];
const rows = names.flatMap((n, i) => [session(`${n}0001`, `${n}-lead`, n, { status: i % 3 === 0 ? "working" : "idle" }), session(`${n}0002`, `${n}-mate`, n)]);
const slot = (id: string, role: string, s: string, lead = false) => ({ id, role, head: "claude-splice", lead, session: s });
const teams = names.map((n) => ({ id: `t-${n}`, name: `${n}-crew`, goal: `Ship the ${n}`, repo: `/work/${n}`, archived: false, slots: [slot("lead", "Lead", `${n}0001`, true), slot("mate", "Mate", `${n}0002`)] }));
const TALK = { messages: [
  { index: 0, role: "user", text: "Read the tide table and fix the parser.", ts: NOW - 30 * MIN },
  { index: 1, role: "assistant", message: "m1", model: "claude-opus-5-5", ts: NOW - 29 * MIN,
    text: "The parser reads the new column now. What changed:\n\n- **Columns** are split on two or more spaces.\n- The **phase** is kept as text.\n\n```ts\nexport function row(line: string): Tide {\n  const [time, height, phase] = line.trim().split(/\\s{2,}/);\n  return { time, height: Number(height), phase };\n}\n```\n\nAll four tests pass, and last year's file still reads: a row with no phase comes back with `phase: null`, which is what the chart expects for the years before the moon's phase was recorded in the table." },
], earlier: null };
// nine providers, as a machine with most of them added has, each with requests over the week
const MAKERS = [["claude-splice", "claude", "anthropic", "client"], ["acme", "claude-acme", "openai", "api-key"], ["north", "claude-north", "xai", "api-key"], ["south", "claude-south", "moonshot", "api-key"],
  ["east", "claude-east", "openrouter", "api-key"], ["west", "claude-west", "deepseek", "api-key"], ["ridge", "claude-ridge", "openai", "api-key"], ["vale", "claude-vale", "openai", "api-key"], ["delta", "claude-delta", "openai", "api-key"]] as const;
const heads = MAKERS.map(([key, label], i) => ({ key, label, state: "running", port: 41001 + i, gate: { inflight: i % 3 === 0 ? 1 : 0, queued: 0, max: 12 } }));
const requests = (key: string, n: number) => Array.from({ length: 8 }, (_, i) => {
  const failed = (n + i) % 9 === 4;
  return { turn: `${key}-${i}`, turn_id: `${key}-${i}`, ts: NOW - (i * 19 + n * 3) * HOUR / 4 - (n + 1) * MIN, outcome: failed ? "error:rate-limited" : "ok", model: `${key}-large-2`,
    session: `${names[(n + i) % names.length]}0001`, session_id: `${names[(n + i) % names.length]}0001`, total: failed ? 900 : 2_400 + i * 700, first_byte: failed ? null : 600 + i * 90,
    arrival_to_upstream_write_ms: 40, attempts: 1, retries: 0, in_tokens: failed ? 0 : 12_000 + i * 900, cached_tokens: failed ? 0 : 9_000, cache_write_tokens: failed ? 0 : 500,
    out_tokens: failed ? 0 : 300 + i * 120, cost_usd: failed ? 0 : 0.01 * (i + 1), plan_value_usd: null, account_label: null, req_bytes: 48_000 };
});
const perf = (_r: Request, url: URL) => {
  const n = MAKERS.findIndex(([key]) => key === (url.searchParams.get("head") ?? ""));
  if (n < 0) return Response.json({ error: "unknown head" }, { status: 400 });
  const rows = requests(MAKERS[n]![0], n);
  return { heads: [{ key: MAKERS[n]![0], label: MAKERS[n]![0], count: rows.length, returned: rows.length, truncated: false, rows, usage: { models: [], accounts: [], sessions: [], days: [] } }] };
};
const d = daemon(world({
  "/api/heads": { heads },
  "/api/status": { version: "9.8.7", registry: MAKERS.map(([key, label, family, authKind]) => ({ key, label, family, authKind })) },
  "/api/models": { heads: MAKERS.map(([key]) => ({ head: key, models: [{ id: `${key}-large-2`, label: `${key[0]!.toUpperCase()}${key.slice(1)} Large 2` }] })) },
  "/api/perf/turns": perf,
  "/api/sessions": { reading: true, sessions: rows },
  "/api/teams": { teams },
  "/api/sessions/harbor0001/transcript": (_r: Request, url: URL) => (url.searchParams.get("before") === "end" ? TALK : { messages: [] }),
  "/api/sessions/history": { sessions: [] },
}));
// his screen is 3840 x 2160: at a zoom of z the page is given 3840 / z px, and each of its px covers z of the screen's.
// The last two are other screens at 100%: a 2560 x 1440 one, the narrowest that sets two halves of a page side by side,
// and a 1920 px window, the size the design was drawn at.
const VIEWS = [["100", 3840, 2160, 1], ["80", 4800, 2700, 0.8], ["125", 3072, 1728, 1.25], ["2560-window", 2560, 1440, 1], ["1920-window", 1920, 1080, 1]] as const;
const PAGES = ["sessions.html", "sessions.html#harbor0001", "accounts.html", "activity.html", "providers.html", "settings.html", "teams.html"];
const report: string[] = [];
try {
  for (const [zoom, width, height, scale] of VIEWS) {
    const b = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox", "--disable-background-networking", `--force-device-scale-factor=${scale}`, `--window-size=${width},${height}`] });
    try {
      for (const file of PAGES) for (const theme of ["night", "day"] as const) {
        if (theme === "day" && zoom !== "100") continue;
        const name = `${file.replace(".html", "").replace("#harbor0001", "-open")}-${zoom}-${theme}`;
        const ctx = await b.newContext({ viewport: null });
        try {
          await ctx.addInitScript((t) => { try { if (localStorage.getItem("splice-console-theme") === null) localStorage.setItem("splice-console-theme", t); } catch { /* storage off */ } }, theme);
          const page = await ctx.newPage();
          const errors: string[] = [];
          page.on("pageerror", (e) => errors.push(e.message));
          await page.goto(`${d.url}/${file}`, { waitUntil: "load" });
          await page.waitForTimeout(2600);
          await page.screenshot({ path: join(dir, `${name}.png`) });
          const m = await page.evaluate(() => ({
            root: getComputedStyle(document.documentElement).fontSize, spill: document.documentElement.scrollWidth - innerWidth, inner: `${innerWidth}x${innerHeight}`, dpr: Math.round(devicePixelRatio * 100) / 100,
            cols: document.querySelectorAll("#board .team").length ? getComputedStyle(document.querySelector("#board")!).gridTemplateColumns.split(" ").length : 0,
          }));
          report.push(`${name}: window ${m.inner} at ${m.dpr}, root ${m.root}${m.cols ? `, ${m.cols} board columns` : ""}${m.spill > 1 ? `; ${m.spill}px past the right edge` : ""}${errors.length ? `; threw ${errors.join(" | ")}` : ""}`);
        } catch (e) {
          report.push(`${name}: failed: ${(e as Error).message.split("\n")[0]}`);
        } finally {
          await ctx.close();
        }
      }
    } finally {
      await b.close();
    }
  }
} finally {
  d.stop();
}
writeFileSync(join(dir, "report.txt"), `${report.join("\n")}\n`);
console.log(report.join("\n"));
