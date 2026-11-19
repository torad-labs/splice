// Pictures of the console as a person sees it, for the feature reviewer to look at before it passes an item (the
// goal's "When an item, and the whole feature, is done"). Chromium on a Mac opens each page named, or every page in
// the console folder when none is, on the stub daemon with the made-up acme world (lib/deck.ts), in night and day, at
// 1440 and 3840 px, and writes one PNG of each into a folder of its own under build/night-deck-captures/ in this
// checkout, with what each page threw and whether it spilled past the right edge in report.txt beside them. Run
// anywhere but a Mac, it sends itself to one through rig and brings the folder back. It never runs Chromium here.
//
//   bun .campaign/checks/console-night-deck/capture.ts [page ...] [--full] [--then STEP ...]
//
// A page is a console file without .html, with a query if it takes one: sessions, accounts, activity?win=30d.
// --full takes the whole page as it scrolls, not only the first screen.
// --then runs a step on every page, theme and width after it loads, in the order given:
//   click:SELECTOR  hover:SELECTOR  fill:SELECTOR|TEXT  press:KEY  wait:MS  shot:NAME (a picture of the state now)
// A step that fails is written in the report and the next width goes on.

import { type Page } from "@playwright/test";
import { spawnSync } from "node:child_process";
import { mkdirSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { browser, CONSOLE, daemon, frame, NARROW, open, ROOT, WIDE, world } from "./lib/deck.ts";

const HERE_SCRIPT = ".campaign/checks/console-night-deck/capture.ts";
const NOT_STARTED = 75;

const args = process.argv.slice(2);
let out = "";
let full = false;
const steps: string[] = [];
const pages: string[] = [];
const passed: string[] = [];
for (let i = 0; i < args.length; i++) {
  const a = args[i]!;
  if (a === "--out") out = args[++i] ?? "";
  else if (a === "--full") {
    full = true;
    passed.push(a);
  } else if (a === "--then") {
    const step = args[++i] ?? "";
    steps.push(step);
    passed.push(a, step);
  } else if (a === "--help" || a === "-h") {
    console.log("bun .campaign/checks/console-night-deck/capture.ts [page ...] [--full] [--then STEP ...]");
    process.exit(0);
  } else {
    pages.push(a);
    passed.push(a);
  }
}

if (process.platform !== "darwin" && process.env.CAPTURE_HERE !== "1") {
  // This machine keeps browser runs off itself: the same command runs on a Mac, and the pictures come back.
  const run = out || join("build", "night-deck-captures", new Date().toISOString().replace(/[:.]/g, "-"));
  // A Mac busy with other runs answers 75 and nothing starts: ask again every 30 s, for 20 minutes unless
  // CAPTURE_WAIT_S says otherwise.
  const until = Date.now() + Number(process.env.CAPTURE_WAIT_S ?? 1200) * 1000;
  const send = () =>
    spawnSync("rig", ["remote", "run", "--needs", "system=Darwin/arm64", "--fetch", run, "--", "bun", HERE_SCRIPT, "--out", run, ...passed], {
      cwd: ROOT,
      stdio: "inherit",
    });
  let r = send();
  while (r.status === NOT_STARTED && Date.now() < until) {
    console.error("capture: no Mac has room yet; asking again in 30 s");
    Bun.sleepSync(30_000);
    r = send();
  }
  if (r.status !== 0) {
    const why = r.status === NOT_STARTED ? "no Mac had room for it (rig answered 75 throughout)" : `the run on the Mac ended with ${r.status ?? r.signal}`;
    console.error(`capture: ${why}. Nothing was captured here, and nothing runs here in its place.`);
    process.exit(r.status ?? 1);
  }
  const dir = join(ROOT, run);
  console.log(`\nThe pictures, here (open each one and look at it):`);
  for (const f of readdirSync(dir).sort()) console.log(join(dir, f));
  process.exit(0);
}

const dir = join(ROOT, out || join("build", "night-deck-captures", "here"));
rmSync(dir, { recursive: true, force: true });
mkdirSync(dir, { recursive: true });

const names = pages.length ? pages : readdirSync(CONSOLE).filter((f) => f.endsWith(".html")).map((f) => f.slice(0, -5)).sort();
const slug = (s: string) => s.replace(/[^a-z0-9]+/gi, "-").replace(/^-|-$/g, "").toLowerCase();

async function act(page: Page, step: string, shot: (name: string) => Promise<void>) {
  const colon = step.indexOf(":");
  const verb = colon < 0 ? step : step.slice(0, colon);
  const rest = colon < 0 ? "" : step.slice(colon + 1);
  if (verb === "click") await page.locator(rest).first().click({ timeout: 10_000 });
  else if (verb === "hover") await page.locator(rest).first().hover({ timeout: 10_000 });
  else if (verb === "fill") {
    const bar = rest.lastIndexOf("|");
    await page.locator(rest.slice(0, bar)).first().fill(rest.slice(bar + 1), { timeout: 10_000 });
  } else if (verb === "press") await page.keyboard.press(rest);
  else if (verb === "wait") await page.waitForTimeout(Number(rest) || 500);
  else if (verb === "shot") await shot(slug(rest) || "step");
  else throw new Error(`no step called ${verb}: click, hover, fill, press, wait or shot`);
  if (verb !== "shot" && verb !== "wait") await page.waitForTimeout(400);
}

const report: string[] = [];
const b = await browser();
const d = daemon(world());
try {
  for (const name of names) {
    const [file, query] = name.split("?") as [string, string | undefined];
    const target = `${file}.html${query ? `?${query}` : ""}`;
    for (const theme of ["night", "day"] as const) {
      for (const size of [NARROW, WIDE]) {
        const base = `${file}${query ? `-${slug(query)}` : ""}-${theme}-${size.width}`;
        const o = await open(b, d, target, size, theme);
        const notes: string[] = [];
        try {
          const shoot = async (suffix: string) => {
            await o.page.screenshot({ path: join(dir, `${base}${suffix ? `-${suffix}` : ""}.png`), fullPage: full });
          };
          await shoot("");
          for (const step of steps) {
            try {
              await act(o.page, step, shoot);
            } catch (e) {
              notes.push(`step ${step} failed: ${(e as Error).message.split("\n")[0]}`);
              await shoot("failed").catch(() => {});
              break;
            }
          }
          const f = await frame(o.page);
          if (f.scrollW > f.innerW) notes.push(`spills ${f.scrollW - f.innerW}px past the right edge`);
          notes.push(...o.errors.map((e) => `threw: ${e}`));
        } finally {
          await o.close();
        }
        report.push(`${base}: ${notes.length ? notes.join("; ") : "no error, nothing past the right edge"}`);
      }
    }
  }
} finally {
  d.stop();
  await b.close();
}
writeFileSync(join(dir, "report.txt"), `${report.join("\n")}\n`);
console.log(report.join("\n"));
