#!/usr/bin/env bun
/**
 * IDLE WATCH for campaign seats, joined to the ledger.
 *
 * PORTED BACK 2026-09-18. This was idle-watch.py, and that file's own docstring recorded the
 * defect: it was "vendored from grailseeker-bot .dev/campaigns/idle-watch.ts … ported to python".
 * The rule said TypeScript and the tree taught Python, so a session that matched the surrounding
 * style learned the wrong one — which is the drift checks/no-python.ts exists to stop, and this
 * file was its clearest exhibit. The original never left: three other repos on this box run the
 * .ts under bun right now. THIS IS NOT A COPY OF ANY OF THEM. What comes forward is the sibling
 * idiom (Bun.file, Bun.sleep, top-level await, a --once self-check); what is spliced ON TOP of it
 * and exists nowhere else is the LEDGER JOIN, which is the only reason this watcher's output is
 * worth reading.
 *
 * A transition detector, not a timer: one line when a seat changes state, nothing otherwise.
 * State comes from the seat's own transcript: idle = the last assistant entry ended its turn
 * (stop_reason end_turn), no user entry after it, file quiet for at least IDLE seconds;
 * working = a tool is running or input is pending. The join decides what an idle seat MEANS:
 *
 *   STALL <seat> <ID>: <detail>   idle while holding an in_flight row (context excuse, silent
 *                                 stop after a mistake, or a report sent to the wrong place)
 *   FREE  <seat>: <detail>        idle with no in_flight row (done rows to stage, next row to give)
 *   BUSY  <seat>: resumed         back to work after an idle line
 *   LOST  <seat>: no transcript   re-announced, never said once and then silent
 *
 * Usage: bun .dev/web-console/idle-watch.ts <ledger.toml> <idle-secs> <poll-secs> <dir1,dir2,...> <seat>...
 *        bun .dev/web-console/idle-watch.ts --once <ledger.toml> <idle-secs> <poll-secs> <dirs> <seat>...
 * The --once form prints every seat's current state and exits; it is the self-check.
 *
 * LINEAGE, kept because it is the file's own history: the registry resolution and the bounded
 * tail read come from the python port; the widening re-announce on both the IDLE and the LOST
 * path came from the operator noticing an idle seat seventeen minutes before the one-shot
 * detector would have mentioned it again (campaign law 23 — a check must be able to say it did
 * not run, pointed at the watcher itself).
 */
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import process from "node:process";

const TAIL_BYTES = 512 * 1024;   // the tail that decides; transcripts reach tens of megabytes
const HEAD_BYTES = 64 * 1024;    // the customTitle fallback window
const RECENT_S = 24 * 3600;      // a seat's transcript was written today; skip the archive
const NAG_FIRST = 300;           // five minutes after the first STALL/FREE
const NAG_MAX = 1800;            // then doubling, capped at half an hour, so a parked seat never goes quiet

const argv = process.argv.slice(2);
const once = argv[0] === "--once";
const [ledger, idleArg, pollArg, dirArg, ...seats] = once ? argv.slice(1) : argv;
if (ledger === undefined || idleArg === undefined || pollArg === undefined || dirArg === undefined || seats.length === 0) {
  console.error("usage: bun .dev/web-console/idle-watch.ts [--once] <ledger.toml> <idle-secs> <poll-secs> <dir1,dir2,...> <seat>...");
  process.exit(2);
}
const IDLE = Number(idleArg);
const POLL_MS = Number(pollArg) * 1000;
const DIRS = dirArg.split(",").filter((d) => existsSync(d));

/** Resolve a seat through the session REGISTRY, where the name is a field.
 *
 *  ~/.claude…/sessions/<pid>.json carries {name, sessionId, pid, status, cwd}; the transcript is
 *  <projects-dir>/<sessionId>.jsonl. No window, no offset, no rename hazard. It is mirrored
 *  across the config roots (.claude, .claude-claude-kimi, …), so glob them all and dedupe by
 *  taking the newest whose transcript exists.
 *
 *  This replaced a scan for `"customTitle":"<seat>"` inside the first 64 KiB of every recent
 *  transcript, which assumed a seat is NAMED AT BIRTH. Measured 2026-09-18: splice-builder's
 *  customTitle sits at byte 23 and splice-design's — a seat renamed mid-session — at byte
 *  2,328,657 of a 33 MB transcript, 35x past the window. That seat was invisible to its own
 *  watcher, and the miss was silent by construction. */
function registryFileFor(seat: string): string | null {
  const roots: string[] = [];
  try {
    for (const entry of readdirSync(homedir(), { withFileTypes: true })) {
      if (entry.isDirectory() && entry.name.startsWith(".claude")) roots.push(join(homedir(), entry.name, "sessions"));
    }
  } catch { return null; }
  let best: { mtime: number; path: string } | null = null;
  for (const root of roots) {
    let entries: string[];
    try { entries = readdirSync(root).filter((f) => f.endsWith(".json")); } catch { continue; }
    for (const e of entries) {
      let d: { name?: string; sessionId?: string };
      try { d = JSON.parse(readFileSync(join(root, e), "utf8")); } catch { continue; }
      if (d.name !== seat || d.sessionId === undefined) continue;
      for (const dir of DIRS) {
        const t = join(dir, `${d.sessionId}.jsonl`);
        try {
          const m = statSync(t).mtimeMs;
          if (best === null || m > best.mtime) best = { mtime: m, path: t };
        } catch { /* not in this dir */ }
      }
    }
  }
  return best === null ? null : best.path;
}

/** The registry first; the head scan only as a fallback for a seat it does not list. */
function fileFor(seat: string): string | null {
  const found = registryFileFor(seat);
  if (found !== null) return found;
  const needle = `"customTitle":"${seat}"`;
  const now = Date.now();
  const hits: { mtime: number; path: string }[] = [];
  for (const dir of DIRS) {
    let entries: string[];
    try { entries = readdirSync(dir).filter((f) => f.endsWith(".jsonl")); } catch { continue; }
    for (const f of entries) {
      const p = join(dir, f);
      try {
        const m = statSync(p).mtimeMs;
        if (now - m > RECENT_S * 1000) continue;
        if (readFileSync(p, { encoding: "utf8" }).slice(0, HEAD_BYTES).includes(needle)) hits.push({ mtime: m, path: p });
      } catch { /* unreadable */ }
    }
  }
  if (hits.length === 0) return null;
  hits.sort((a, b) => b.mtime - a.mtime);
  return hits[0]!.path;
}

/** The last `n` non-empty lines. A bounded read: the tail decides and the file may be huge. */
function tailLines(p: string, n = 60): string[] {
  const size = statSync(p).size;
  const start = Math.max(0, size - TAIL_BYTES);
  const fd = readFileSync(p);
  const text = fd.subarray(start).toString("utf8");
  return text.replace(/\n+$/, "").split("\n").slice(-n);
}

type Reading = { state: "idle" | "working" | "settling" | "unknown"; detail: string };

function reading(p: string): Reading {
  let lastAssistant: string | null = null;
  let seen = false;
  let userAfter = false;
  for (const line of tailLines(p)) {
    let j: { type?: string; message?: { stop_reason?: string | null } };
    try { j = JSON.parse(line); } catch { continue; }
    if (j.type === "assistant") {
      lastAssistant = j.message?.stop_reason ?? null;
      seen = true;
      userAfter = false;
    } else if (j.type === "user" && seen) userAfter = true;
  }
  const age = Math.round((Date.now() - statSync(p).mtimeMs) / 1000);
  if (!seen) return { state: "unknown", detail: `no assistant entry in tail, quiet ${age}s` };
  if (userAfter) return { state: "working", detail: `input pending, quiet ${age}s` };
  if (lastAssistant === "tool_use") return { state: "working", detail: `tool running, quiet ${age}s` };
  if (age >= IDLE) return { state: "idle", detail: `turn ended (${lastAssistant ?? "?"}), quiet ${age}s` };
  return { state: "settling", detail: `turn ended ${age}s ago` };
}

/** seat -> row id for every in_flight row, from the row's LAST claim note. Read-only: the watcher
 *  never writes the ledger, and it takes the last CLAIM in the block so a row that changed hands
 *  reports the seat that holds it now. */
function inFlightBySeat(): Map<string, string> {
  const out = new Map<string, string>();
  let text: string;
  try { text = readFileSync(ledger, "utf8"); } catch { return out; }
  for (const block of text.split(/^\[\[items\]\]\s*$/m).slice(1)) {
    const id = /^id = "([^"]+)"/m.exec(block);
    const status = /^status = "([^"]+)"/m.exec(block);
    if (id === null || status === null || status[1] !== "in_flight") continue;
    const owners = [...block.matchAll(/CLAIM: owner=(\S+)/g)];
    if (owners.length === 0) continue;
    out.set(owners[owners.length - 1]![1]!, id[1]!);
  }
  return out;
}

const last = new Map<string, string>();
const files = new Map<string, string>();
// A seat that goes idle and STAYS idle used to produce exactly one line and then silence, so
// silence meant two different things — "every seat is busy" and "a seat has been idle for twenty
// minutes and you have forgotten" — and the orchestrator could not tell them apart. Measured
// 2026-09-18: the operator noticed an idle seat seventeen minutes before the one-shot detector
// would have mentioned it again. Re-announced on a widening interval, so silence means one thing.
const idleSince = new Map<string, number>();
const nextNag = new Map<string, number>();
const lostSince = new Map<string, number>();
const lostNag = new Map<string, number>();

function tick(): string[] {
  const out: string[] = [];
  const holding = inFlightBySeat();
  for (const seat of seats) {
    let p = files.get(seat);
    if (p === undefined || !existsSync(p)) {
      const found = fileFor(seat);
      if (found === null) {
        const now = Date.now();
        const first = !lostSince.has(seat);
        if (first) { lostSince.set(seat, now); lostNag.set(seat, now + NAG_FIRST * 1000); }
        const due = now >= (lostNag.get(seat) ?? 0);
        if (first || due) {
          const mins = Math.floor((now - lostSince.get(seat)!) / 60000);
          out.push(`LOST  ${seat}: no transcript found` + (first ? "" : ` — still unseen after ${mins}m`));
          lostNag.set(seat, first ? now + NAG_FIRST * 1000 : now + Math.min(NAG_MAX * 1000, (now - lostNag.get(seat)!) * 2 + NAG_FIRST * 1000));
        }
        continue;
      }
      files.set(seat, found);
      if (lostSince.has(seat)) {
        out.push(`FOUND ${seat}: transcript located after ${Math.floor((Date.now() - lostSince.get(seat)!) / 60000)}m`);
        lostSince.delete(seat);
        lostNag.delete(seat);
      }
      p = found;
    }
    const { state, detail } = reading(p);
    const prev = last.get(seat);
    const now = Date.now();
    if (state !== "idle") { idleSince.delete(seat); nextNag.delete(seat); }
    else if (!idleSince.has(seat)) { idleSince.set(seat, now); nextNag.set(seat, now + NAG_FIRST * 1000); }
    else if (now >= (nextNag.get(seat) ?? Infinity)) {
      const mins = Math.floor((now - idleSince.get(seat)!) / 60000);
      out.push(`STILL ${seat} ${holding.get(seat) ?? "no row"}: idle ${mins}m`);
      nextNag.set(seat, now + Math.min((nextNag.get(seat)! - idleSince.get(seat)!) * 2, NAG_MAX * 1000));
    }
    if (state === "idle" && prev !== "idle") {
      const row = holding.get(seat);
      out.push(row === undefined ? `FREE  ${seat}: ${detail}` : `STALL ${seat} ${row}: ${detail}`);
    }
    if (state === "working" && prev === "idle") out.push(`BUSY  ${seat}: resumed`);
    if (state === "idle" || state === "working") last.set(seat, state);
  }
  return out;
}

if (once) {
  for (const line of tick()) console.log(line);
  process.exit(0);
}
while (true) {
  for (const line of tick()) console.log(line);
  await Bun.sleep(POLL_MS);
}
