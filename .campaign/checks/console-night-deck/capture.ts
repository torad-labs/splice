// Pictures of the console as a person sees it, for the feature reviewer to look at before it passes an item (the
// goal's "When an item, and the whole feature, is done"). Chromium on a Mac opens each page named, or every page in
// the console folder when none is, on the stub daemon with the made-up acme world (lib/deck.ts), in night and day, at
// 1440 and 3840 px (and at 375 px too when --phone is given), and writes one PNG of each into a folder of its own under
// build/night-deck-captures/ in this checkout, with what each page threw and whether it spilled past the right edge in
// report.txt beside them. Run anywhere but a Mac, it sends itself to one through rig and brings the folder back. It never
// runs Chromium here.
//
//   bun .campaign/checks/console-night-deck/capture.ts [page ...] [--full] [--locked] [--phone] [--then STEP ...]
//
// A page is a console file without .html, with a query if it takes one: sessions, accounts, activity?win=30d.
// --full takes the whole page as it scrolls, not only the first screen.
// --locked answers every /api read with 401, as a daemon does to a console that holds no key or a wrong one.
// --phone adds a 375 px capture beside the two widths, where a page's phone layout is judged.
// --then runs a step on every page, theme and width after it loads, in the order given:
//   click:SELECTOR  dblclick:SELECTOR  hover:SELECTOR  fill:SELECTOR|TEXT  type:TEXT (keys typed where the focus is)
//   select:SELECTOR|VALUE  check:SELECTOR  uncheck:SELECTOR  scroll:SELECTOR (into view)  press:KEY  wait:MS
//   goto:PAGE (another console page, in the same tab)  shot:NAME (a picture of the state now)
// A step that fails is written in the report and the next width goes on.
// What a page sends that is not a read (a write: POST, PUT, PATCH, DELETE) is what the stub saw, and each capture
// lists it in the report, its whole bodies in <picture>-sent.json beside the pictures: a write is judged by what the
// page sent, never by a Splice that is running.

import { type Page } from "@playwright/test";
import { spawnSync } from "node:child_process";
import { mkdirSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { browser, CONSOLE, daemon, frame, NARROW, open, PHONE, ROOT, WIDE, world } from "./lib/deck.ts";

const HERE_SCRIPT = ".campaign/checks/console-night-deck/capture.ts";
const NOT_STARTED = 75;

const args = process.argv.slice(2);
let out = "";
let full = false;
let locked = false;
let phone = false;
const steps: string[] = [];
const pages: string[] = [];
const passed: string[] = [];
for (let i = 0; i < args.length; i++) {
  const a = args[i]!;
  if (a === "--out") out = args[++i] ?? "";
  else if (a === "--full") {
    full = true;
    passed.push(a);
  } else if (a === "--locked") {
    locked = true;
    passed.push(a);
  } else if (a === "--phone") {
    phone = true;
    passed.push(a);
  } else if (a === "--then") {
    const step = args[++i] ?? "";
    steps.push(step);
    passed.push(a, step);
  } else if (a === "--help" || a === "-h") {
    console.log("bun .campaign/checks/console-night-deck/capture.ts [page ...] [--full] [--locked] [--phone] [--then STEP ...]");
    process.exit(0);
  } else {
    pages.push(a);
    passed.push(a);
  }
}

// The reviewer picks these arguments and the script runs on this machine with the operator's keys, then on a Mac, so
// it acts only on what it was made for: a console page (with a query), steps from the list above with no shell
// characters in them, and a picture folder under build/night-deck-captures/. Anything else is refused, naming it.
const PAGE = /^[a-z0-9][a-z0-9-]*(\/[a-z0-9][a-z0-9-]*)?(\?[A-Za-z0-9=&%._,-]*)?$/;
const VERBS = ["click", "dblclick", "hover", "fill", "type", "select", "check", "uncheck", "scroll", "press", "wait", "goto", "shot"];
const OUT = /^build\/night-deck-captures\/[A-Za-z0-9][A-Za-z0-9._-]*$/;
const refuse = (why: string) => {
  console.error(`capture: refused: ${why}. Nothing was captured.`);
  process.exit(64);
};
for (const p of pages) if (!PAGE.test(p)) refuse(`${JSON.stringify(p)} is no console page (a name such as sessions, probe/probe or activity?win=30d)`);
for (const s of steps) {
  const verb = s.includes(":") ? s.slice(0, s.indexOf(":")) : s;
  if (!VERBS.includes(verb)) refuse(`${JSON.stringify(s)} is no step: ${VERBS.join(", ")}`);
  if (/[`$\\;\x00-\x1f\x7f]/.test(s)) refuse(`${JSON.stringify(s)} holds a character a step never needs (\`, $, \\, ; or a control character)`);
  if (verb === "goto" && !PAGE.test(s.slice(5))) refuse(`${JSON.stringify(s)} goes to no console page`);
}
if (out && (!OUT.test(out) || out.split("/").includes(".."))) refuse(`--out ${JSON.stringify(out)} is not a folder under build/night-deck-captures/`);

if (process.platform !== "darwin" && process.env.CAPTURE_HERE !== "1") {
  // This machine keeps browser runs off itself: the same command runs on a Mac, and the pictures come back.
  const run = out || join("build", "night-deck-captures", new Date().toISOString().replace(/[:.]/g, "-"));
  // A Mac busy with other runs answers 75 and nothing starts: ask again every 30 s, for 20 minutes unless
  // CAPTURE_WAIT_S says otherwise.
  const until = Date.now() + Number(process.env.CAPTURE_WAIT_S ?? 1200) * 1000;
  // A Mac whose Chromium is a full build rather than Playwright's headless shell names it in
  // SCREEN_REVIEW_BROWSER; rig passes no environment on its own, so the name rides along explicitly.
  const named = process.env.SCREEN_REVIEW_BROWSER ? ["--env", "SCREEN_REVIEW_BROWSER"] : [];
  const send = () =>
    spawnSync(
      "rig",
      ["remote", "run", "--needs", "system=Darwin/arm64", "--fetch", run, ...named, "--", "bun", HERE_SCRIPT, "--out", run, ...passed],
      { cwd: ROOT, stdio: "inherit" },
    );
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

async function act(page: Page, step: string, shot: (name: string) => Promise<void>, url: string) {
  const colon = step.indexOf(":");
  const verb = colon < 0 ? step : step.slice(0, colon);
  const rest = colon < 0 ? "" : step.slice(colon + 1);
  const at = (sel: string) => page.locator(sel).first();
  const split = (r: string) => [r.slice(0, r.lastIndexOf("|")), r.slice(r.lastIndexOf("|") + 1)] as const;
  if (verb === "click") await at(rest).click({ timeout: 10_000 });
  else if (verb === "dblclick") await at(rest).dblclick({ timeout: 10_000 });
  else if (verb === "hover") await at(rest).hover({ timeout: 10_000 });
  else if (verb === "type") await page.keyboard.type(rest);
  else if (verb === "select") {
    const [sel, value] = split(rest);
    await at(sel).selectOption(value, { timeout: 10_000 });
  } else if (verb === "check") await at(rest).check({ timeout: 10_000 });
  else if (verb === "uncheck") await at(rest).uncheck({ timeout: 10_000 });
  else if (verb === "scroll") await at(rest).scrollIntoViewIfNeeded({ timeout: 10_000 });
  else if (verb === "goto") {
    const [file, query] = rest.split("?") as [string, string | undefined];
    await page.goto(`${url}/${file}.html${query ? `?${query}` : ""}`);
  }
  else if (verb === "fill") {
    const bar = rest.lastIndexOf("|");
    await page.locator(rest.slice(0, bar)).first().fill(rest.slice(bar + 1), { timeout: 10_000 });
  } else if (verb === "press") await page.keyboard.press(rest);
  else if (verb === "wait") await page.waitForTimeout(Number(rest) || 500);
  else if (verb === "shot") await shot(slug(rest) || "step");
  else throw new Error(`no step called ${verb}: click, dblclick, hover, fill, type, select, check, uncheck, scroll, press, wait, goto or shot`);
  if (verb !== "shot" && verb !== "wait") await page.waitForTimeout(400);
}

const report: string[] = [];
const b = await browser();
// Locked: every read answers 401, as a daemon answers a console with no key or a wrong one.
const d = daemon(locked ? { "*": () => Response.json({ error: "unauthorized" }, { status: 401 }) } : world());
try {
  for (const name of names) {
    const [file, query] = name.split("?") as [string, string | undefined];
    const target = `${file}.html${query ? `?${query}` : ""}`;
    for (const theme of ["night", "day"] as const) {
      for (const size of phone ? [PHONE, NARROW, WIDE] : [NARROW, WIDE]) {
        const base = `${file}${query ? `-${slug(query)}` : ""}${locked ? "-locked" : ""}-${theme}-${size.width}`;
        const from = d.seen.length;
        const o = await open(b, d, target, size, theme);
        const notes: string[] = [];
        try {
          const shoot = async (suffix: string) => {
            await o.page.screenshot({ path: join(dir, `${base}${suffix ? `-${suffix}` : ""}.png`), fullPage: full });
          };
          await shoot("");
          for (const step of steps) {
            try {
              await act(o.page, step, shoot, d.url);
            } catch (e) {
              notes.push(`step ${step} failed: ${(e as Error).message.split("\n")[0]}`);
              await shoot("failed").catch(() => {});
              break;
            }
          }
          const f = await frame(o.page);
          if (f.scrollW > f.innerW) notes.push(`spills ${f.scrollW - f.innerW}px past the right edge`);
          notes.push(...o.errors.map((e) => `threw: ${e}`));
          const writes = d.seen.slice(from).filter((w) => w.method !== "GET" && w.method !== "HEAD");
          if (writes.length) {
            writeFileSync(join(dir, `${base}-sent.json`), `${JSON.stringify(writes, null, 2)}\n`);
            const brief = (v: unknown) => (v === null || v === "" ? "" : ` ${JSON.stringify(v).slice(0, 200)}`);
            notes.push(...writes.map((w) => `sent ${w.method} ${w.path}${w.query}${brief(w.body)}`));
          }
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
