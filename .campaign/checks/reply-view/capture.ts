// Pictures of the reader as a person sees it, for the feature review to look at before it passes an item. Chromium on a
// Mac draws each scene named (every scene when none is) on the stub daemon with the made-up world (lib/rv.ts), in night
// and day, at 375 and 1440 px, and writes one PNG of each, whole page, into a folder of its own under
// build/reply-view-captures/ in this checkout, with what each scene threw, refused under its CSP or spilled sideways in
// report.txt beside them. Run anywhere but a Mac, it sends itself to one through rig and brings the folder back. It
// never runs Chromium here.
//
//   bun .campaign/checks/reply-view/capture.ts [scene ...]
//
// Scenes:
//   history   the host fixture with the whole made-up conversation, every fold closed
//   open      the same, every fold opened (tool calls, long results, thinking, the compaction)
//   thinking  a reply that is still thinking: its one quiet "Thinking…" line
//   live      a reply mid-stream: thinking, Markdown text with code, a tool started, a subagent and a compaction beside it
//   settled   the same reply after its turn ended and its transcript message arrived
//   page      the reader's own page on a session, with a reply streaming in
//   picker    the reader's own page with no session named
//   locked    the reader's own page in a browser that is not signed in

import { type Page } from "@playwright/test";
import { spawnSync } from "node:child_process";
import { mkdirSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import {
  addHistory, addLive, browser, CHAT, DESK, ev, host, MARKDOWN, mount, open, PHONE, ROOT, SESSION, SESSIONS, spills, stub, THEMES, TOOLS,
  turnEv, WORLD, type Ev, type Msg, type Opened, type Stub, type Theme,
} from "./lib/rv.ts";

const HERE_SCRIPT = ".campaign/checks/reply-view/capture.ts";
const NOT_STARTED = 75;
const SCENES = ["history", "open", "thinking", "live", "settled", "page", "picker", "locked"] as const;
type Scene = (typeof SCENES)[number];

const args = process.argv.slice(2);
let out = "";
const asked: string[] = [];
for (let i = 0; i < args.length; i++) {
  const a = args[i]!;
  if (a === "--out") out = args[++i] ?? "";
  else if (a === "--help" || a === "-h") {
    console.log(`bun ${HERE_SCRIPT} [${SCENES.join("|")} ...]`);
    process.exit(0);
  } else asked.push(a);
}
const unknown = asked.filter((s) => !(SCENES as readonly string[]).includes(s));
if (unknown.length) {
  console.error(`capture: no scene called ${unknown.join(", ")}; the scenes are ${SCENES.join(", ")}`);
  process.exit(2);
}

if (process.platform !== "darwin" && process.env.CAPTURE_HERE !== "1") {
  const run = out || join("build", "reply-view-captures", new Date().toISOString().replace(/[:.]/g, "-"));
  const until = Date.now() + Number(process.env.CAPTURE_WAIT_S ?? 1200) * 1000;
  const send = () =>
    spawnSync("rig", ["remote", "run", "--needs", "system=Darwin/arm64", "--fetch", run, "--", "bun", HERE_SCRIPT, "--out", run, ...asked], { cwd: ROOT, stdio: "inherit" });
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
  console.log("\nThe pictures, here (open each one and look at it):");
  for (const f of readdirSync(dir).sort()) console.log(join(dir, f));
  process.exit(0);
}

const dir = join(ROOT, out || join("build", "reply-view-captures", "here"));
rmSync(dir, { recursive: true, force: true });
mkdirSync(dir, { recursive: true });

const LIVE: Ev[] = [
  ev("hello", { session: SESSION, live: ["L1"] }),
  turnEv("L1", "msg_cap1"),
  ev("thinking", { turn: "L1", index: 0, delta: "The header row splits on tabs only; commas are next." }),
  ev("text", { turn: "L1", index: 1, delta: "The parser now splits on **tabs and commas**:\n\n```ts\nconst cols = row.split(/[\\t,]/);\n```\n\nRunning the tests" }),
  ev("tool", { turn: "L1", index: 2, id: "toolu_cap1", name: "Bash" }),
  turnEv("L2", "msg_cap2", { agent: "agent-3" }),
  ev("text", { turn: "L2", index: 0, delta: "The subagent checks the harbor map labels." }),
  turnEv("L3", null, { compact: true }),
  ev("text", { turn: "L3", index: 0, delta: "Made-up summary of the work so far." }),
];
const SETTLE: Msg[] = [
  { index: 70, role: "assistant", text: "The parser now splits on **tabs and commas**:\n\n```ts\nconst cols = row.split(/[\\t,]/);\n```\n\nRunning the tests", message: "msg_cap1", model: "claude-opus-5-5", thinking: "The header row splits on tabs only; commas are next." },
  { index: 71, role: "assistant", text: JSON.stringify({ command: "bun test parser" }), tool: "Bash", tool_use_id: "toolu_cap1", message: "msg_cap1", model: "claude-opus-5-5" },
  { index: 72, role: "tool", text: "3 pass\n0 fail", tool: "Bash", tool_use_id: "toolu_cap1" },
];

async function openAll(page: Page) {
  for (const d of await page.locator(".rv details").all()) {
    if (!(await d.evaluate((el) => (el as HTMLDetailsElement).open))) await d.locator("summary").first().click().catch(() => {});
  }
}

async function scene(st: Stub, s: Scene, size: typeof PHONE, theme: Theme): Promise<Opened> {
  const b = await shared;
  if (s === "page" || s === "picker" || s === "locked") {
    const path = s === "picker" ? "/reply-view/index.html" : `/reply-view/index.html?session=${SESSION}`;
    const o = await open(b, st, path, size, theme, { key: s !== "locked" });
    await o.page.waitForTimeout(2_000);
    if (s === "page") {
      for (const e of LIVE.slice(1, 5)) st.push(SESSION, e.event, e.data);
      await o.page.waitForTimeout(800);
    }
    return o;
  }
  const o = await host(b, st, size, theme);
  if (s === "history" || s === "open") await mount(o.page, [...CHAT, ...TOOLS, ...MARKDOWN]);
  if (s === "open") await openAll(o.page);
  if (s === "thinking") {
    await mount(o.page, [...CHAT.slice(0, 1)]);
    await addLive(o.page, [ev("hello", { session: SESSION, live: [] }), turnEv("K1", "msg_cap0"), ev("thinking", { turn: "K1", index: 0, delta: "Made-up thought about the parser." })]);
  }
  if (s === "live" || s === "settled") {
    await mount(o.page, [...CHAT.slice(0, 1), ...MARKDOWN]);
    await addLive(o.page, LIVE);
  }
  if (s === "settled") {
    await addLive(o.page, [ev("end", { turn: "L1", stop_reason: "tool_use", error: null }), ev("end", { turn: "L2", stop_reason: "end_turn", error: null }), ev("end", { turn: "L3", stop_reason: "end_turn", error: null })]);
    await addHistory(o.page, SETTLE);
  }
  await o.page.waitForTimeout(s === "live" ? 1_500 : 500);
  return o;
}

const shared = browser();
const report: string[] = [];
const st = stub({ transcripts: { [SESSION]: [...WORLD] }, answers: { "/api/sessions": SESSIONS } });
try {
  for (const s of (asked.length ? asked : SCENES) as Scene[]) {
    for (const theme of THEMES) {
      for (const size of [PHONE, DESK]) {
        const name = `${s}-${theme}-${size.width}`;
        const notes: string[] = [];
        let o: Opened | null = null;
        try {
          o = await scene(st, s, size, theme);
          await o.page.screenshot({ path: join(dir, `${name}.png`), fullPage: true });
          notes.push(...(await spills(o.page)).map((x) => `spills: ${x}`));
          notes.push(...(await o.csp()).map((x) => `refused by its CSP: ${x}`));
          notes.push(...o.errors.map((e) => `threw: ${e}`));
        } catch (e) {
          notes.push(`failed: ${(e as Error).message.split("\n")[0]}`);
        } finally {
          await o?.close();
        }
        report.push(`${name}: ${notes.length ? notes.join("; ") : "no error, nothing refused, nothing past the edge"}`);
      }
    }
  }
} finally {
  st.stop();
  await (await shared).close();
}
writeFileSync(join(dir, "report.txt"), `${report.join("\n")}\n`);
console.log(report.join("\n"));
