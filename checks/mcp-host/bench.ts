#!/usr/bin/env bun
/** Shared MCP hosting benchmark (FEATURES.md §8): N parallel Claude Code sessions, the same
 *  stdio MCP servers, once launched per session (Claude Code's own behaviour) and once through the
 *  splice host. The receipt records the PEAK summed RSS of the MCP server processes in each mode
 *  (the denominator the >50% target is measured against), the daemon's RSS delta while hosting,
 *  whole-workload RSS, and wall time.
 *
 *  Usage:
 *    bench.ts --control-port 3196 --mgmt-key-file ~/.splice/state/mgmt-key \
 *      (a pre-0.4 install still keeps its key at ~/.claude-codex/state/mgmt-key — splice
 *       adopts that root in place, so pass the one `splice doctor` names)
 *             --servers ast-grep,remotion-docs,torad-fleet,figma-comments --sessions 4 \
 *             --out checks/e2e/receipts/mcp-host-bench.json
 *
 *  Each session is `claude -p` with --strict-mcp-config and a prompt that lists the tools and stops,
 *  so the servers are actually initialized and queried. The tool surface is read from Claude Code's
 *  own stream-json `system/init` event (the tools it loaded and each MCP server's connection status),
 *  never from the model's prose — a session's answer once named tools of servers it did not have
 *  (review 2, 2026-09-13). Sampling is /proc based (Linux only).
 *
 *  ─────────────────────────────────────────────────────────────────────────────────────────────
 *  V4-145: converted to TypeScript (bun). An ADAPTATION, not a transcription, in three places:
 *
 *    THE SELFTEST PATCHED EIGHT GLOBALS (proc_table, time.sleep, time.monotonic, os.killpg,
 *    os.getpgid, owned_group_alive, terminate_owned_children, subprocess.Popen). ESM bindings are
 *    read-only, so every one of those is reached through the `seams` object below and the selftest
 *    swaps entries on it, restoring them in `finally` exactly where the original restored globals.
 *    A function that the original bound at DEF time (terminate_owned_children's proc_snapshot
 *    default) stays bound to the real procTable, not the seam, because that is what the original did.
 *
 *    THE SAMPLER WAS A THREAD. Here it is an async loop on the same event loop the client waits run
 *    on, which is sound because every /proc read is synchronous and short: the loop samples, yields
 *    SAMPLE_S, samples again. join(timeout) races the loop against the timeout, and is_alive() is
 *    "the loop has not returned" — which is all stop_sampler's contract needs.
 *
 *    NODE HAS NO os.getpgid. getpgid reads field 5 (pgrp) of /proc/<pid>/stat, measured equal to
 *    Python's os.getpgid, and a missing pid raises ProcessLookupError as the original's did. A spawn
 *    of a missing binary returns pid === undefined with only an ASYNC ENOENT, where Popen raised
 *    synchronously — so the spawn seam throws FileNotFoundError synchronously on an absent pid.
 *
 *  Not exercisable from a build seat: a real run spawns paid `claude -p` sessions against the
 *  operator's account and needs a live splice daemon. What IS exercised: --selftest (every arm), and
 *  a differential of every pure function against the Python original on generated inputs.
 */
import { spawn as nodeSpawn } from "node:child_process";
import { createHash, createHmac } from "node:crypto";
import {
  closeSync, existsSync, mkdirSync, mkdtempSync, openSync, readdirSync, readFileSync, realpathSync,
  rmSync, symlinkSync, writeFileSync,
} from "node:fs";
import os from "node:os";
import { dirname, isAbsolute, join, resolve } from "node:path";
import { dumps, dumpsIndent, floatRepr, isPyNum, isPyObj, loads, obj, type PyValue } from "../e2e/pyjson.ts";

const PROMPT = "List the names of every MCP tool you have available, one per line, then stop. Do not call any tool.";
const SAMPLE_S = 0.5;
const SESSION_TIMEOUT_S = 180;
const TERMINATE_GRACE_S = 5;
const SAMPLER_JOIN_S = 1;
const GROUP_POLL_S = 0.05;
const MCP_SCOPE = "splice:mcp-access:v1";

// ---------------------------------------------------------------------------------------------
// Python exception classes the original catches BY TYPE. Keeping the type is what keeps each
// `except X` arm catching exactly what it caught before, and nothing else.
// ---------------------------------------------------------------------------------------------
class PyError extends Error {
  constructor(message = "") {
    super(message);
    this.name = new.target.name;
  }
}
export class OSError extends PyError {}
export class ProcessLookupError extends OSError {}
export class FileNotFoundError extends OSError {}
export class TimeoutExpired extends PyError {}
export class AssertionError extends PyError {}
export class KeyboardInterrupt extends PyError {}
export class KeyError extends PyError {}
export class AttributeError extends PyError {}

type Sig = "SIGTERM" | "SIGKILL";
export type ProcRow = [number, number | null, number | null, string | null];
type Mapping = [string, PyValue][];

/** The slice of subprocess.Popen this benchmark uses. `wait` may be sync in a fake; callers await. */
export interface Proc {
  pid: number;
  poll(): number | null;
  wait(timeout?: number): Promise<number> | number;
}

interface SpawnOpts {
  cwd: string;
  stdoutFd: number;
  env: Record<string, string>;
  startNewSession: boolean;
}

// ---------------------------------------------------------------------------------------------
// Python text semantics the parser and /proc reader lean on.
// ---------------------------------------------------------------------------------------------

const PY_WS = " \t\n\r\x0b\x0c\x1c\x1d\x1e\x1f\x85\xa0                　";
/** str.strip() — Python's whitespace set, which is NOT JS trim()'s (it adds \x1c-\x1f and \x85, and
 *  does not strip ﻿). */
export function pyStrip(s: string): string {
  let a = 0;
  let b = s.length;
  while (a < b && PY_WS.includes(s[a])) a++;
  while (b > a && PY_WS.includes(s[b - 1])) b--;
  return s.slice(a, b);
}
/** str.split() with no separator. */
function pySplitWs(s: string): string[] {
  const out: string[] = [];
  let cur = "";
  for (const ch of s) {
    if (PY_WS.includes(ch)) {
      if (cur) out.push(cur);
      cur = "";
    } else cur += ch;
  }
  if (cur) out.push(cur);
  return out;
}
/** str.splitlines() — splits on \r\n and on every one of Python's line boundaries, including
 *  \x0b, \x0c, \x1c-\x1e, \x85,   and  , which a raw JSON string may legally carry. */
export function pySplitlines(s: string): string[] {
  const out: string[] = [];
  let cur = "";
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    if (ch === "\r" && s[i + 1] === "\n") {
      out.push(cur);
      cur = "";
      i++;
    } else if ("\n\r\x0b\x0c\x1c\x1d\x1e\x85  ".includes(ch)) {
      out.push(cur);
      cur = "";
    } else cur += ch;
  }
  if (cur) out.push(cur);
  return out;
}
/** int() of a /proc token: optional sign, digits, single underscores between digits. */
function pyInt(tok: string | undefined): number {
  if (tok === undefined) throw new RangeError("IndexError");
  const t = pyStrip(tok);
  if (!/^[+-]?\d+(_\d+)*$/.test(t)) throw new SyntaxError(`invalid literal for int(): '${tok}'`);
  return Number(t.replaceAll("_", ""));
}
const strictUtf8 = new TextDecoder("utf-8", { fatal: true });
const lossyUtf8 = new TextDecoder("utf-8", { fatal: false });
/** Path.read_text(): strict UTF-8 AND universal newlines (\r\n and \r both become \n). */
function readText(path: string): string {
  let raw: Buffer;
  try {
    raw = readFileSync(path);
  } catch (e) {
    throw toOSError(e);
  }
  return strictUtf8.decode(raw).replaceAll("\r\n", "\n").replaceAll("\r", "\n");
}
function toOSError(e: unknown): OSError {
  const code = (e as { code?: string }).code;
  if (code === "ENOENT") return new FileNotFoundError(String((e as Error).message));
  if (code === "ESRCH") return new ProcessLookupError(String((e as Error).message));
  return new OSError(String((e as Error).message));
}
/** Code-point order, which is Python's string order; JS's default sort is UTF-16 unit order. */
function cpCompare(a: string, b: string): number {
  const x = Array.from(a);
  const y = Array.from(b);
  for (let i = 0; i < Math.min(x.length, y.length); i++) {
    const d = (x[i].codePointAt(0) as number) - (y[i].codePointAt(0) as number);
    if (d !== 0) return d;
  }
  return x.length - y.length;
}

// ---------------------------------------------------------------------------------------------
// Python value helpers over pyjson's tagged tree.
// ---------------------------------------------------------------------------------------------

const int = (x: number): PyValue => ({ __pyNum: String(x), isFloat: false });
const float = (x: number): PyValue => ({ __pyNum: String(x), isFloat: true });
function get(m: PyValue, key: string, dflt: PyValue = null): PyValue {
  if (!isPyObj(m)) throw new AttributeError(`'${typeName(m)}' object has no attribute 'get'`);
  const hit = m.__pyObj.find(([k]) => k === key);
  return hit === undefined ? dflt : hit[1];
}
function sub(m: PyValue, key: string): PyValue {
  if (!isPyObj(m)) throw new TypeError(`'${typeName(m)}' object is not subscriptable`);
  const hit = m.__pyObj.find(([k]) => k === key);
  if (hit === undefined) throw new KeyError(`'${key}'`);
  return hit[1];
}
function has(m: PyValue, key: string): boolean {
  return isPyObj(m) && m.__pyObj.some(([k]) => k === key);
}
/** `d[k] = v`: a new key appends, an existing key keeps its position. */
function setKey(pairs: Mapping, key: string, value: PyValue): void {
  const at = pairs.findIndex(([k]) => k === key);
  if (at >= 0) pairs[at][1] = value;
  else pairs.push([key, value]);
}
function typeName(v: PyValue): string {
  if (v === null) return "NoneType";
  if (typeof v === "boolean") return "bool";
  if (typeof v === "string") return "str";
  if (Array.isArray(v)) return "list";
  if (isPyNum(v)) return v.isFloat ? "float" : "int";
  return "dict";
}
/** Iterating a value the way `for x in v` does: a dict yields its keys, a str its characters. */
function iter(v: PyValue): PyValue[] {
  if (Array.isArray(v)) return v;
  if (typeof v === "string") return Array.from(v);
  if (isPyObj(v)) return v.__pyObj.map(([k]) => k);
  throw new TypeError(`'${typeName(v)}' object is not iterable`);
}
const num = (v: PyValue): number => (typeof v === "boolean" ? (v ? 1 : 0) : Number((v as { __pyNum: string }).__pyNum));
/** Python truthiness. */
function truthy(v: PyValue): boolean {
  if (v === null || v === false) return false;
  if (v === true) return true;
  if (typeof v === "string" || Array.isArray(v)) return v.length > 0;
  if (isPyNum(v)) return num(v) !== 0;
  return (v as { __pyObj: Mapping }).__pyObj.length > 0;
}
/** Python ==: dicts compare regardless of key order, 1 == 1.0 == True. */
export function pyEq(a: PyValue, b: PyValue): boolean {
  const numeric = (v: PyValue) => typeof v === "boolean" || isPyNum(v);
  if (numeric(a) && numeric(b)) return num(a) === num(b);
  if (a === null || b === null || typeof a !== "object" || typeof b !== "object") return a === b;
  if (Array.isArray(a) || Array.isArray(b)) {
    return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((x, i) => pyEq(x, b[i]));
  }
  if (isPyObj(a) && isPyObj(b)) {
    if (a.__pyObj.length !== b.__pyObj.length) return false;
    return a.__pyObj.every(([k, v]) => has(b, k) && pyEq(v, get(b, k)));
  }
  return false;
}
/** Python repr() — the shape every f-string of a list, dict, tuple or None renders. */
export function pyRepr(v: PyValue | PyTuple): string {
  if (v instanceof PyTuple) {
    return v.items.length === 1 ? `(${pyRepr(v.items[0])},)` : "(" + v.items.map(pyRepr).join(", ") + ")";
  }
  if (v === null) return "None";
  if (v === true) return "True";
  if (v === false) return "False";
  if (isPyNum(v)) return v.isFloat ? floatRepr(v.__pyNum) : v.__pyNum;
  if (typeof v === "string") {
    const q = v.includes("'") && !v.includes('"') ? '"' : "'";
    let body = "";
    for (const ch of v) {
      const cp = ch.codePointAt(0) as number;
      if (ch === "\\") body += "\\\\";
      else if (ch === q) body += "\\" + q;
      else if (ch === "\n") body += "\\n";
      else if (ch === "\r") body += "\\r";
      else if (ch === "\t") body += "\\t";
      else if (cp < 0x20 || cp === 0x7f) body += "\\x" + cp.toString(16).padStart(2, "0");
      else body += ch;
    }
    return q + body + q;
  }
  if (Array.isArray(v)) return "[" + v.map(pyRepr).join(", ") + "]";
  return "{" + (v as { __pyObj: Mapping }).__pyObj.map(([k, x]) => `${pyRepr(k)}: ${pyRepr(x)}`).join(", ") + "}";
}
/** A tuple, only so the selftest's failure messages render `(11, 10, 20, None)` as Python does. */
class PyTuple {
  constructor(readonly items: PyValue[]) {}
}
const rowValue = (r: ProcRow): PyTuple =>
  new PyTuple(r.map((x) => (x === null ? null : typeof x === "number" ? int(x) : x)));
/** repr() of a set of ints: `{11, 21}`, `set()` when empty, members in ascending order (CPython's
 *  small-int hash order, which is what the original's messages show for these values). */
function reprIntSet(s: Set<number>): string {
  return s.size === 0 ? "set()" : "{" + [...s].sort((a, b) => a - b).join(", ") + "}";
}

/** round(x, nd) — Python rounds the EXACT binary value half-to-even. Math.round(x*10**nd) rounds the
 *  product (already inexact) half-up, and toFixed rounds exact ties up; both miss a case the saving
 *  can really hit: round(0.0625, 3) is 0.062 in Python (1 - 15/16 is exactly 0.0625). toFixed(100)
 *  exposes the exact decimal expansion, so the tie is decided on the real digits. */
export function pyRound(x: number, nd: number): number {
  if (!Number.isFinite(x)) return x;
  const neg = x < 0 || Object.is(x, -0);
  const [ip, fp] = Math.abs(x).toFixed(100).split(".");
  const keep = fp.slice(0, nd);
  const rest = fp.slice(nd);
  let digits = BigInt(ip + keep);
  if (rest[0] > "5" || (rest[0] === "5" && /[1-9]/.test(rest.slice(1)))) digits += 1n;
  else if (rest[0] === "5" && digits % 2n === 1n) digits += 1n;
  const s = digits.toString().padStart(nd + 1, "0");
  const text = nd > 0 ? `${s.slice(0, s.length - nd)}.${s.slice(s.length - nd)}` : s;
  return (neg ? -1 : 1) * Number(text);
}

// ---------------------------------------------------------------------------------------------

export function readGlobalServers(): PyValue {
  const doc = loads(readText(join(os.homedir(), ".claude.json")));
  return get(doc, "mcpServers", obj([]));
}

/** Domain-separated HMAC bearer for MCP access; never send the management secret. */
export function mcpAccessBearer(managementSecret: string): string {
  return createHmac("sha256", Buffer.from(managementSecret, "utf8")).update(MCP_SCOPE).digest("hex");
}

export function hostedServers(names: string[], controlPort: number, mcpBearer: string): PyValue {
  const pairs: Mapping = [];
  for (const name of names) {
    setKey(pairs, name, obj([
      ["type", "http"],
      ["url", `http://127.0.0.1:${controlPort}/mcp/${name}`],
      ["headers", obj([["Authorization", `Bearer ${mcpBearer}`]])],
    ]));
  }
  return obj(pairs);
}

export function statPpid(path: string): number | null {
  try {
    const text = readText(path);
    const at = text.lastIndexOf(")");
    if (at < 0) return null; // rsplit(")", 1)[1] -> IndexError
    return pyInt(pySplitWs(text.slice(at + 1))[1]);
  } catch (e) {
    // IndexError, OSError and ValueError. A UnicodeDecodeError IS a ValueError, so a stat line
    // whose comm is not UTF-8 lands here too.
    if (e instanceof OSError || e instanceof RangeError || e instanceof SyntaxError || e instanceof TypeError) return null;
    throw e;
  }
}

/** (pid, ppid, rss_kb, cmdline) from a /proc snapshot.
 *
 *  A raced cmdline read retains status-derived linkage. A raced status read falls back to
 *  `/proc/<pid>/stat` for PPID; without either linkage the row is unrelated/unknown, not evidence
 *  about this workload. `null` distinguishes unavailable data from a real zero/empty value. */
export function procTable(procRoot = "/proc"): ProcRow[] {
  const out: ProcRow[] = [];
  let names: string[];
  try {
    names = readdirSync(procRoot);
  } catch (e) {
    throw toOSError(e);
  }
  for (const name of names) {
    if (!/^[0-9]+$/.test(name)) continue;
    const pid = Number(name);
    const p = join(procRoot, name);
    let stat: string;
    try {
      stat = readText(join(p, "status"));
    } catch (e) {
      // Only OSError is caught here; a status file that is not UTF-8 propagates, as it did.
      if (!(e instanceof OSError)) throw e;
      out.push([pid, statPpid(join(p, "stat")), null, null]);
      continue;
    }
    let cmd: string | null;
    try {
      cmd = pyStrip(lossyUtf8.decode(Buffer.from(readFileSync(join(p, "cmdline")).map((b) => (b === 0 ? 0x20 : b)))));
    } catch {
      cmd = null;
    }
    let rss: number | null = null;
    let ppid: number | null = null;
    for (const line of pySplitlines(stat)) {
      if (line.startsWith("VmRSS:")) {
        try {
          rss = pyInt(pySplitWs(line)[1]);
        } catch {
          /* IndexError / ValueError */
        }
      } else if (line.startsWith("PPid:")) {
        try {
          ppid = pyInt(pySplitWs(line)[1]);
        } catch {
          /* IndexError / ValueError */
        }
      }
    }
    out.push([pid, ppid, rss, cmd]);
  }
  return out;
}

export function procAlive(pid: number): boolean {
  return existsSync(join("/proc", String(pid)));
}

export function descendants(rootPids: Set<number | null>, table: ProcRow[]): Set<number> {
  const children = new Map<number | null, number[]>();
  for (const [pid, ppid] of table) {
    if (!children.has(ppid)) children.set(ppid, []);
    children.get(ppid)!.push(pid);
  }
  const seen = new Set<number>();
  const stack = [...rootPids];
  while (stack.length > 0) {
    const pid = stack.pop()!;
    for (const c of children.get(pid) ?? []) {
      if (!seen.has(c)) {
        seen.add(c);
        stack.push(c);
      }
    }
  }
  return seen;
}

// ---------------------------------------------------------------------------------------------
// THE SEAMS. Every global the original's selftest replaced is reached through here.
// ---------------------------------------------------------------------------------------------

/** getpgid via /proc/<pid>/stat field 5 — Node has no os.getpgid. */
function getpgid(pid: number): number {
  let text: string;
  try {
    text = readFileSync(`/proc/${pid}/stat`, "latin1");
  } catch {
    throw new ProcessLookupError(`[Errno 3] No such process`);
  }
  return Number(text.slice(text.lastIndexOf(")") + 1).trim().split(/\s+/)[2]);
}

function killpg(pgid: number, sig: Sig): void {
  try {
    process.kill(-pgid, sig);
  } catch (e) {
    throw toOSError(e);
  }
}

/** subprocess.Popen for this benchmark's one launch shape. */
function spawnProc(argv: string[], opts: SpawnOpts): Proc {
  const child = nodeSpawn(argv[0], argv.slice(1), {
    cwd: opts.cwd,
    env: opts.env,
    stdio: ["inherit", opts.stdoutFd, opts.stdoutFd],
    detached: opts.startNewSession,
  });
  if (child.pid === undefined) {
    // Popen raises synchronously; node only emits an async 'error'. Swallow that event (it would
    // otherwise be an unhandled error) and raise the synchronous one the original raised.
    child.on("error", () => {});
    throw new FileNotFoundError(`[Errno 2] No such file or directory: '${argv[0]}'`);
  }
  let returncode: number | null = null;
  const exited = new Promise<number>((res) => {
    child.on("exit", (code, signal) => {
      returncode = code ?? -(os.constants.signals[signal as NodeJS.Signals] ?? 0);
      res(returncode);
    });
  });
  const pid = child.pid;
  return {
    pid,
    poll: () => returncode,
    wait: (timeout?: number) => {
      if (returncode !== null) return Promise.resolve(returncode);
      if (timeout === undefined) return exited;
      return new Promise<number>((res, rej) => {
        const t = setTimeout(() => rej(new TimeoutExpired(`Command '${argv.join(" ")}' timed out after ${timeout} seconds`)), timeout * 1000);
        exited.then((rc) => {
          clearTimeout(t);
          res(rc);
        });
      });
    },
  };
}

export const seams = {
  procTable: (): ProcRow[] => procTable(),
  sleep: (s: number): Promise<void> | void => Bun.sleep(s * 1000),
  monotonic: (): number => performance.now() / 1000,
  killpg,
  getpgid,
  ownedGroupAlive: (proc: Proc, owned: Set<number>): boolean => ownedGroupAlive(proc, owned),
  terminateOwnedChildren: (procs: Proc[], owned?: Set<number> | null, graceS?: number, snap?: () => ProcRow[]) =>
    terminateOwnedChildren(procs, owned, graceS, snap),
  spawn: spawnProc as (argv: string[], opts: SpawnOpts) => Proc,
};

// ---------------------------------------------------------------------------------------------

/** Peak summed RSS, sampled while the sessions run, of:
 *  (a) MCP server processes — every descendant of the client sessions that is not a claude
 *      binary (unhosted), plus every descendant of the daemon (hosted); process-tree based so
 *      npm/uvx wrappers and their grandchildren count the same way in both modes;
 *  (b) the whole workload (sessions + everything under them + the daemon tree);
 *  (c) the daemon JVM itself. A still-live PID with demonstrable session/daemon lineage that
 *      disappears or lacks RSS makes measurement incomplete. An exited race and a first-sample
 *      row with no derivable linkage are not evidence about this workload. */
export class Sampler {
  peakServers = 0;
  peakWorkload = 0;
  peakDaemon = 0;
  serverPidsSeen = new Set<number>();
  sessionDescendantsSeen = new Set<number>();
  relevantPidsSeen: Set<number>;
  measurementGaps = new Set<number>();
  failed = false;
  stop = false;
  private loop: Promise<void> | null = null;
  private finished = false;

  constructor(
    readonly sessionPids: Set<number>,
    readonly daemonPid: number | null,
    readonly pidExists: (pid: number) => boolean = procAlive,
  ) {
    this.relevantPidsSeen = new Set(sessionPids);
    if (daemonPid) this.relevantPidsSeen.add(daemonPid);
  }

  sample(table: ProcRow[]): void {
    const observed = new Set(table.map(([pid]) => pid));
    for (const pid of this.relevantPidsSeen) {
      if (!observed.has(pid) && this.pidExists(pid)) this.measurementGaps.add(pid);
    }
    const underSessions = descendants(this.sessionPids, table);
    const underDaemon = this.daemonPid ? descendants(new Set([this.daemonPid]), table) : new Set<number>();
    const relevant = new Set([...this.sessionPids, ...underSessions, ...underDaemon]);
    if (this.daemonPid) relevant.add(this.daemonPid);
    for (const pid of underSessions) this.sessionDescendantsSeen.add(pid);
    for (const pid of relevant) this.relevantPidsSeen.add(pid);
    let servers = 0;
    let workload = 0;
    let daemon = 0;
    for (const [pid, , rss, cmd] of table) {
      const isClaude = cmd !== null && cmd.split(" ")[0].includes("claude");
      const isServer = (underSessions.has(pid) && !isClaude) || underDaemon.has(pid);
      const isWorkload = this.sessionPids.has(pid) || underSessions.has(pid) || underDaemon.has(pid);
      if (isServer) this.serverPidsSeen.add(pid);
      if (this.relevantPidsSeen.has(pid) && rss === null) this.measurementGaps.add(pid);
      if (rss === null) continue;
      if (isServer) servers += rss;
      if (isWorkload) workload += rss;
      if (pid === this.daemonPid) daemon = rss;
    }
    this.peakServers = Math.max(this.peakServers, servers);
    this.peakWorkload = Math.max(this.peakWorkload, workload + daemon);
    this.peakDaemon = Math.max(this.peakDaemon, daemon);
  }

  async run(): Promise<void> {
    try {
      while (!this.stop) {
        this.sample(seams.procTable());
        await seams.sleep(SAMPLE_S);
      }
    } catch {
      this.failed = true;
    }
  }

  start(): void {
    this.loop = this.run().finally(() => {
      this.finished = true;
    });
  }

  async join(timeout?: number): Promise<void> {
    if (this.loop === null) throw new Error("cannot join thread before it is started");
    if (timeout === undefined) {
      await this.loop;
      return;
    }
    await Promise.race([this.loop, Bun.sleep(timeout * 1000)]);
  }

  isAlive(): boolean {
    return this.loop !== null && !this.finished;
  }
}

/** The one splice JVM running `app-all.jar daemon`; pass --daemon-pid when several run. */
export function findDaemonPid(): number | null {
  const pids = procTable()
    .filter(([, , , cmd]) => cmd && cmd.includes("app-all.jar daemon") && cmd.split(" ")[0].endsWith("java"))
    .map(([pid]) => pid);
  return pids.length === 1 ? pids[0] : null;
}

/** Path.resolve(strict=False): resolve what can be resolved, keep the rest literal. */
function resolveLoose(p: string): string {
  try {
    return realpathSync(p);
  } catch {
    return resolve(p);
  }
}

/** The jar the daemon actually runs — resolving relative `-jar` paths from its own cwd. */
export function daemonJarSha256(pid: number, procRoot = "/proc"): string | null {
  try {
    const proc = join(procRoot, String(pid));
    let raw: Buffer;
    try {
      raw = readFileSync(join(proc, "cmdline"));
    } catch (e) {
      throw toOSError(e);
    }
    const argv: Buffer[] = [];
    let start = 0;
    for (let i = 0; i <= raw.length; i++) {
      if (i === raw.length || raw[i] === 0) {
        argv.push(raw.subarray(start, i));
        start = i + 1;
      }
    }
    const cwd = resolveLoose(join(proc, "cwd"));
    let jar: string | null = null;
    for (let i = 0; i < argv.length - 1; i++) {
      if (argv[i].toString("latin1") === "-jar") {
        jar = strictUtf8.decode(argv[i + 1]);
        break;
      }
    }
    let path = jar ? jar : null;
    if (path && !isAbsolute(path)) path = join(cwd, path);
    if (!path || !existsSync(path)) return null;
    try {
      return createHash("sha256").update(readFileSync(path)).digest("hex");
    } catch (e) {
      throw toOSError(e);
    }
  } catch (e) {
    if (e instanceof OSError) return null;
    throw e;
  }
}

/** Claude Code's `system/init` event from stream-json output; {} when the session never got there. */
export function initEvent(output: string): PyValue {
  for (const line of pySplitlines(output)) {
    let msg: PyValue;
    try {
      msg = loads(line);
    } catch {
      continue;
    }
    if (get(msg, "type") === "system" && get(msg, "subtype") === "init") return msg;
  }
  return obj([]);
}

/** The exact MCP tool names the session LOADED, grouped by server (`mcp__<server>__<tool>`).
 *  Identity, not a count: equal totals could hide one server's tools replaced by another's. */
export function toolsByServer(init: PyValue): PyValue {
  const byServer = new Map<string, Set<string>>();
  for (const name of iter(get(init, "tools", []))) {
    if (typeof name !== "string") throw new AttributeError(`'${typeName(name)}' object has no attribute 'startswith'`);
    // Split on the delimiter only; a tool name is whatever the protocol allowed (dots included).
    if (!name.startsWith("mcp__") || !name.slice(5).includes("__")) continue;
    const rest = name.slice(5);
    const at = rest.indexOf("__");
    const server = rest.slice(0, at);
    const tool = rest.slice(at + 2);
    if (server && tool) {
      if (!byServer.has(server)) byServer.set(server, new Set());
      byServer.get(server)!.add(tool);
    }
  }
  return obj(
    [...byServer.keys()].sort(cpCompare).map((s) => [s, [...byServer.get(s)!].sort(cpCompare)] as [string, PyValue]),
  );
}

export function serverStatus(init: PyValue): PyValue {
  const pairs: Mapping = [];
  for (const s of iter(get(init, "mcp_servers", []))) {
    // Server names are strings on this wire; a non-string name would be a dict key of another type
    // in Python, which this port does not model.
    setKey(pairs, String(sub(s, "name")), get(s, "status", "?"));
  }
  return obj(pairs);
}

export function waitForClients(procs: Proc[]): Promise<void> {
  // One deadline bounds the whole client workload, not one unbounded wait per client.
  return (async () => {
    const deadline = seams.monotonic() + SESSION_TIMEOUT_S;
    for (const proc of procs) {
      await proc.wait(Math.max(0, deadline - seams.monotonic()));
    }
  })();
}

/** Whether a group still has a direct child or a sampled descendant we created. */
export function ownedGroupAlive(proc: Proc, ownedPids: Set<number>): boolean {
  const pids = new Set(ownedPids);
  if (proc.poll() === null) pids.add(proc.pid);
  for (const pid of pids) {
    try {
      if (seams.getpgid(pid) === proc.pid) return true;
    } catch (e) {
      if (e instanceof ProcessLookupError) continue;
      throw e;
    }
  }
  return false;
}

export async function waitForOwnedGroups(procs: Proc[], ownedPids: Set<number>, graceS: number): Promise<Proc[]> {
  const deadline = seams.monotonic() + graceS;
  for (;;) {
    const live = procs.filter((proc) => seams.ownedGroupAlive(proc, ownedPids));
    if (live.length === 0 || seams.monotonic() >= deadline) return live;
    await seams.sleep(Math.max(0, Math.min(GROUP_POLL_S, deadline - seams.monotonic())));
  }
}

/** End only verified process groups this benchmark created; never touch the external daemon. */
export async function terminateOwnedChildren(
  procs: Proc[],
  ownedPidsIn: Set<number> | null = null,
  graceS: number = TERMINATE_GRACE_S,
  procSnapshot: () => ProcRow[] = () => procTable(),
): Promise<void> {
  const ownedPids = new Set(ownedPidsIn ?? []);
  const roots = new Set(procs.filter((p) => p.poll() === null).map((p) => p.pid));
  if (roots.size > 0) {
    try {
      for (const pid of descendants(roots, procSnapshot())) ownedPids.add(pid);
    } catch (e) {
      if (!(e instanceof OSError)) throw e;
      // A failed measurement must not prevent cleanup of the children already owned.
      process.stderr.write("BENCH: cleanup snapshot unavailable; using known owned groups\n");
    }
  }
  const groups = procs.filter((proc) => seams.ownedGroupAlive(proc, ownedPids));
  for (const proc of groups) {
    try {
      seams.killpg(proc.pid, "SIGTERM");
    } catch (e) {
      if (!(e instanceof ProcessLookupError)) throw e;
    }
  }
  const stubborn = await waitForOwnedGroups(groups, ownedPids, graceS);
  for (const proc of stubborn) {
    if (seams.ownedGroupAlive(proc, ownedPids)) {
      try {
        seams.killpg(proc.pid, "SIGKILL");
      } catch (e) {
        if (!(e instanceof ProcessLookupError)) throw e;
      }
    }
  }
  await waitForOwnedGroups(stubborn, ownedPids, graceS);
}

export async function stopSampler(sampler: Sampler): Promise<void> {
  sampler.stop = true;
  await sampler.join(SAMPLER_JOIN_S);
  if (sampler.isAlive()) sampler.failed = true;
}

export async function runSessions(n: number, mcpConfig: PyValue, model: string, daemonPid: number | null): Promise<Mapping> {
  const tmp = mkdtempSync(join(os.tmpdir(), "tmp"));
  try {
    const cfg = join(tmp, "mcp.json");
    writeFileSync(cfg, dumps(obj([["mcpServers", mcpConfig]])), "utf8");
    const procs: Proc[] = [];
    const outputFds: number[] = [];
    let sampler: Sampler | null = null;
    let samplerStarted = false;
    let t0 = 0;
    try {
      for (let i = 0; i < n; i++) {
        const fd = openSync(join(tmp, `out${i}.txt`), "w");
        outputFds.push(fd);
        procs.push(
          seams.spawn(
            ["claude", "-p", PROMPT, "--model", model, "--mcp-config", cfg, "--strict-mcp-config",
              "--output-format", "stream-json", "--verbose"],
            {
              cwd: tmp,
              stdoutFd: fd,
              env: { ...(process.env as Record<string, string>), CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: "1" },
              startNewSession: true,
            },
          ),
        );
      }
      sampler = new Sampler(new Set(procs.map((p) => p.pid)), daemonPid);
      sampler.start();
      samplerStarted = true;
      t0 = Date.now() / 1000;
      await waitForClients(procs);
    } finally {
      if (samplerStarted && sampler !== null) await stopSampler(sampler);
      await seams.terminateOwnedChildren(procs, sampler ? sampler.sessionDescendantsSeen : new Set());
      for (const fd of outputFds) closeSync(fd);
    }
    if (sampler === null) throw new Error("sampler did not start");
    const full = Array.from({ length: n }, (_, i) => readText(join(tmp, `out${i}.txt`)));
    const inits = full.map(initEvent);
    const outputs = full.map((o) => Array.from(o).slice(0, 400).join(""));
    const tools = inits.map(toolsByServer);
    return [
      ["sessions", int(n)],
      ["exit_codes", procs.map((p) => { const rc = p.poll(); return rc === null ? null : int(rc); })],
      ["mcp_servers", inits.map(serverStatus)],
      ["mcp_tools", tools],
      ["mcp_tools_listed", tools.map((t) => int((t as { __pyObj: Mapping }).__pyObj.reduce((acc, [, v]) => acc + (v as PyValue[]).length, 0)))],
      ["wall_s", float(pyRound(Date.now() / 1000 - t0, 1))],
      ["peak_server_rss_kb", int(sampler.peakServers)],
      ["peak_workload_rss_kb", int(sampler.peakWorkload)],
      ["peak_daemon_rss_kb", int(sampler.peakDaemon)],
      ["server_pids_seen", int(sampler.serverPidsSeen.size)],
      ["measurement_complete", sampler.measurementGaps.size === 0 && !sampler.failed],
      ["measurement_gaps", [...sampler.measurementGaps].sort((a, b) => a - b).map(int)],
      ["measurement_failure", sampler.failed],
      ["outputs_head", outputs],
    ];
  } finally {
    rmSync(tmp, { recursive: true, force: true });
  }
}

export function receiptProblems(a: PyValue, b: PyValue, names: string[], saving: number | null, daemonHash: string | null): string[] {
  const problems: string[] = [];
  if (!daemonHash) problems.push("daemon jar SHA-256 is unavailable");
  const codes = [...(sub(a, "exit_codes") as PyValue[]), ...(sub(b, "exit_codes") as PyValue[])];
  if (codes.some((code) => !pyEq(code, int(0)))) {
    problems.push(`session exit codes unhosted=${pyRepr(sub(a, "exit_codes"))} hosted=${pyRepr(sub(b, "exit_codes"))}`);
  }
  // Every session, both modes, must list EVERY requested server with the SAME tool names — per
  // server, by name, never by total count.
  for (const [mode, run] of [["unhosted", a], ["hosted", b]] as [string, PyValue][]) {
    if (!truthy(sub(run, "measurement_complete"))) {
      if (truthy(sub(run, "measurement_failure"))) {
        problems.push(`${mode} workload measurement incomplete: sampler failed or did not stop`);
      } else {
        problems.push(`${mode} workload measurement incomplete: missing live PIDs ${pyRepr(sub(run, "measurement_gaps"))}`);
      }
    }
    const tools = iter(sub(run, "mcp_tools"));
    const statuses = iter(sub(run, "mcp_servers"));
    for (let i = 0; i < Math.min(tools.length, statuses.length); i++) {
      const absent = names.filter((s) => !truthy(get(tools[i], s)));
      if (absent.length > 0) problems.push(`${mode} session ${i} loaded no tools for ${pyRepr(absent)}`);
      const down: Mapping = [];
      for (const s of names) {
        if (get(statuses[i], s) !== "connected") setKey(down, s, get(statuses[i], s));
      }
      if (down.length > 0) problems.push(`${mode} session ${i} MCP server status not connected: ${pyRepr(obj(down))}`);
    }
  }
  if (!pyEq(sub(a, "mcp_tools"), sub(b, "mcp_tools"))) {
    problems.push(`tool surface differs between modes: unhosted=${pyRepr(sub(a, "mcp_tools"))} hosted=${pyRepr(sub(b, "mcp_tools"))}`);
  }
  if (pyEq(sub(b, "peak_server_rss_kb"), int(0))) problems.push("hosted server RSS was zero");
  if (saving === null || saving < 0.5) {
    problems.push(`server RSS saving ${saving === null ? "None" : floatRepr(String(saving))} is below the 50% bar`);
  }
  return problems;
}

// ---------------------------------------------------------------------------------------------
// SELFTEST. Mocked parser, /proc, and child-process tests; never launches a daemon or client.
// Each arm is the original's, in the original's order, with the original's failure message.
// ---------------------------------------------------------------------------------------------

function assert(cond: boolean, message: string): void {
  if (!cond) throw new AssertionError(message);
}
const eqSet = (a: Set<number>, b: number[]) => a.size === b.length && b.every((x) => a.has(x));
const eqSignals = (a: [number, Sig][], b: [number, Sig][]) =>
  a.length === b.length && a.every(([p, s], i) => p === b[i][0] && s === b[i][1]);
const reprSignals = (a: [number, Sig][]) =>
  "[" + a.map(([p, s]) => `(${p}, <Signals.${s}: ${os.constants.signals[s]}>)`).join(", ") + "]";

class FakeProcess implements Proc {
  returncode: number | null = null;
  waitTimeouts: (number | undefined)[] = [];
  constructor(readonly pid: number) {}
  poll(): number | null {
    return this.returncode;
  }
  wait(timeout?: number): number {
    this.waitTimeouts.push(timeout);
    this.returncode = 0;
    return 0;
  }
}

function withTemp<T>(fn: (tmp: string) => T): T {
  const tmp = mkdtempSync(join(os.tmpdir(), "tmp"));
  try {
    return fn(tmp);
  } finally {
    rmSync(tmp, { recursive: true, force: true });
  }
}

export async function selftest(): Promise<number> {
  try {
    const init = fromPlain({
      tools: ["Read", "mcp__remotion-docs__remotion-documentation", "mcp__fs__files.read",
        "mcp__fs__files.write_v2", "mcp__x__", "mcp____t"],
      mcp_servers: [{ name: "fs", status: "connected" }],
    });
    const want = fromPlain({ fs: ["files.read", "files.write_v2"], "remotion-docs": ["remotion-documentation"] });
    if (!pyEq(toolsByServer(init), want) || !pyEq(serverStatus(init), fromPlain({ fs: "connected" }))) {
      throw new AssertionError("tool parser or server status");
    }

    const rawSecret: string = "bench-fixture-management-secret";
    const bearer = mcpAccessBearer(rawSecret);
    assert(
      bearer === "c815d9ac36f92c0cf480e934f0a8f17535d7fbe8401daa54b983ac23680a8be4" && bearer !== rawSecret,
      "MCP access HMAC vector",
    );
    const hosted = hostedServers(["fixture"], 3196, bearer);
    assert(
      sub(sub(sub(hosted, "fixture"), "headers"), "Authorization") === `Bearer ${bearer}` && !pyRepr(hosted).includes(rawSecret),
      "hosted config leaked management secret",
    );

    withTemp((tmp) => {
      const root = join(tmp, "proc");
      const proc = join(root, "77");
      const daemonCwd = join(tmp, "daemon");
      mkdirSync(proc, { recursive: true });
      mkdirSync(daemonCwd);
      const jar = join(daemonCwd, "app-all.jar");
      writeFileSync(jar, "fixture jar");
      writeFileSync(join(proc, "cmdline"), Buffer.from("java\0-jar\0app-all.jar\0daemon\0", "latin1"));
      symlinkSync(daemonCwd, join(proc, "cwd"), "dir");
      const expected = createHash("sha256").update(readFileSync(jar)).digest("hex");
      assert(daemonJarSha256(77, root) === expected, "relative daemon jar hash");
    });

    withTemp((tmp) => {
      const root = join(tmp, "proc");
      const fixtures: [number, string | null, string | null][] = [
        [10, "PPid:\t1\nVmRSS:\t10 kB\n", "/usr/bin/claude\0"],
        [11, "PPid:\t10\nVmRSS:\t20 kB\n", null],
        [12, "PPid:\t10\n", "node mcp\0"],
        [13, null, null],
        [14, null, null],
        [99, "PPid:\t1\n", "other\0"],
      ];
      for (const [pid, status, cmd] of fixtures) {
        const process_ = join(root, String(pid));
        mkdirSync(process_, { recursive: true });
        if (status !== null) writeFileSync(join(process_, "status"), status);
        if (cmd !== null) writeFileSync(join(process_, "cmdline"), Buffer.from(cmd, "latin1"));
      }
      writeFileSync(join(root, "13", "stat"), "13 (fixture) S 10 0");
      const table = procTable(root);
      const child = table.find((row) => row[0] === 11)!;
      const statusRace = table.find((row) => row[0] === 13)!;
      const tail3 = (r: ProcRow) => r.slice(1);
      if (!(tail3(child)[0] === 10 && tail3(child)[1] === 20 && tail3(child)[2] === null)
        || !(tail3(statusRace)[0] === 10 && tail3(statusRace)[1] === null && tail3(statusRace)[2] === null)) {
        throw new AssertionError(`first-sample linkage ${pyRepr(rowValue(child))}, ${pyRepr(rowValue(statusRace))}`);
      }
      const firstSample = new Sampler(new Set([10]), null, (pid) => [10, 11, 12, 13, 14].includes(pid));
      firstSample.sample(table);
      if (firstSample.peakServers !== 20 || !eqSet(firstSample.measurementGaps, [12, 13])) {
        throw new AssertionError(`first-sample classification ${firstSample.peakServers}, ${reprIntSet(firstSample.measurementGaps)}`);
      }
    });

    const live = new Set([10, 11, 20, 21, 99]);
    const sampler = new Sampler(new Set([10]), 20, (pid) => live.has(pid));
    sampler.sample([[10, 1, 10, "/usr/bin/claude"], [11, 10, 20, "node server"],
      [20, 1, 30, "java"], [21, 20, 40, "node hosted"], [99, 1, 50, "other"]]);
    sampler.sample([[10, 1, 10, "/usr/bin/claude"], [20, 1, 30, "java"]]);
    assert(sampler.peakWorkload === 100, `workload double-counted daemon ${sampler.peakWorkload}`);
    assert(eqSet(sampler.measurementGaps, [11, 21]), `live descendant gaps ${reprIntSet(sampler.measurementGaps)}`);
    const exited = new Sampler(new Set([10]), null, (pid) => pid === 10);
    exited.sample([[10, 1, 10, "/usr/bin/claude"], [11, 10, 20, "node server"]]);
    exited.sample([[10, 1, 10, "/usr/bin/claude"]]);
    assert(exited.measurementGaps.size === 0, `exited descendant gap ${reprIntSet(exited.measurementGaps)}`);
    const unreadable = new Sampler(new Set([10]), null, (pid) => pid === 10 || pid === 11);
    unreadable.sample([[10, 1, 10, "/usr/bin/claude"], [11, 10, 20, "node server"]]);
    unreadable.sample([[10, 1, 10, "/usr/bin/claude"], [11, null, null, null]]);
    assert(eqSet(unreadable.measurementGaps, [11]), `known unreadable descendant gap ${reprIntSet(unreadable.measurementGaps)}`);

    const failure = new Sampler(new Set([10]), null, () => false);
    const tables: (ProcRow[] | Error)[] = [[[10, 1, 10, "/usr/bin/claude"]], new OSError("fixture")];
    const oldProcTable = seams.procTable;
    const oldSleep = seams.sleep;
    try {
      seams.procTable = () => {
        const table = tables.shift();
        if (table === undefined) throw new Error("StopIteration");
        if (table instanceof Error) throw table;
        return table;
      };
      seams.sleep = () => {};
      await failure.run();
    } finally {
      seams.procTable = oldProcTable;
      seams.sleep = oldSleep;
    }
    assert(failure.failed && failure.peakWorkload === 10, "sampler exception was accepted");

    class UnfinishedSampler extends Sampler {
      joinTimeout: number | undefined;
      override async join(timeout?: number): Promise<void> {
        this.joinTimeout = timeout;
      }
      override isAlive(): boolean {
        return true;
      }
    }
    const unfinished = new UnfinishedSampler(new Set(), null);
    await stopSampler(unfinished);
    assert(unfinished.failed && unfinished.joinTimeout === SAMPLER_JOIN_S, "unfinished sampler was accepted");

    const run = fromPlain({
      exit_codes: [0], mcp_tools: [{ fixture: ["tool"] }],
      mcp_servers: [{ fixture: "connected" }], peak_server_rss_kb: 40,
      measurement_complete: true, measurement_gaps: [], measurement_failure: false,
    });
    const withKey = (base: PyValue, key: string, value: PyValue): PyValue => {
      const pairs: Mapping = (base as { __pyObj: Mapping }).__pyObj.map(([k, v]) => [k, v]);
      setKey(pairs, key, value);
      return obj(pairs);
    };
    assert(receiptProblems(run, run, ["fixture"], 0.6, null).some((p) => p.includes("jar SHA-256")), "missing daemon hash accepted");
    const zeroHosted = withKey(run, "peak_server_rss_kb", int(0));
    assert(receiptProblems(run, zeroHosted, ["fixture"], 1.0, "hash").some((p) => p === "hosted server RSS was zero"), "zero hosted RSS accepted");
    const incomplete = withKey(withKey(run, "measurement_complete", false), "measurement_gaps", [int(21)]);
    assert(receiptProblems(run, incomplete, ["fixture"], 0.6, "hash").some((p) => p.includes("measurement incomplete")), "incomplete workload accepted");
    const failedSampler = withKey(withKey(run, "measurement_complete", false), "measurement_failure", true);
    assert(receiptProblems(run, failedSampler, ["fixture"], 0.6, "hash").some((p) => p.includes("sampler failed")), "failed sampler accepted");

    let signals: [number, Sig][] = [];
    const proc = new FakeProcess(101);
    const oldKillpg = seams.killpg;
    const oldGetpgid = seams.getpgid;
    const endingGroup = (pid: number, sig: Sig) => {
      signals.push([pid, sig]);
      if (sig === "SIGTERM") proc.returncode = 0;
    };
    const lookup = () => {
      throw new ProcessLookupError();
    };
    try {
      seams.killpg = endingGroup;
      seams.getpgid = (pid) => (proc.returncode === null && pid === 101 ? 101 : lookup());
      await terminateOwnedChildren([proc], null, 0, () => []);
    } finally {
      seams.killpg = oldKillpg;
      seams.getpgid = oldGetpgid;
    }
    assert(eqSignals(signals, [[101, "SIGTERM"]]), `owned group cleanup signals ${reprSignals(signals)}`);

    const failedSnapshot = (): ProcRow[] => {
      throw new OSError("synthetic proc failure");
    };
    signals.length = 0;
    proc.returncode = null;
    try {
      seams.killpg = endingGroup;
      seams.getpgid = (pid) => (proc.returncode === null && pid === 101 ? 101 : lookup());
      await terminateOwnedChildren([proc], null, 0, failedSnapshot);
    } finally {
      seams.killpg = oldKillpg;
      seams.getpgid = oldGetpgid;
    }
    assert(eqSignals(signals, [[101, "SIGTERM"]]), `snapshot failure prevented owned cleanup ${reprSignals(signals)}`);

    const leader = new FakeProcess(101);
    leader.returncode = 0;
    const survivors = new Set([202]);
    signals = [];
    const survivingGroup = (pid: number, sig: Sig) => {
      signals.push([pid, sig]);
      if (sig === "SIGKILL") survivors.clear();
    };
    try {
      seams.killpg = survivingGroup;
      seams.getpgid = (pid) => (survivors.has(pid) ? 101 : lookup());
      await terminateOwnedChildren([leader], new Set([202]), 0, () => []);
    } finally {
      seams.killpg = oldKillpg;
      seams.getpgid = oldGetpgid;
    }
    assert(
      eqSignals(signals, [[101, "SIGTERM"], [101, "SIGKILL"]]),
      `surviving owned group cleanup signals ${reprSignals(signals)}`,
    );

    signals = [];
    try {
      seams.killpg = (pid, sig) => {
        signals.push([pid, sig]);
      };
      seams.getpgid = () => lookup();
      await terminateOwnedChildren([leader], null, 0, () => []);
    } finally {
      seams.killpg = oldKillpg;
      seams.getpgid = oldGetpgid;
    }
    assert(signals.length === 0, `recycled group was signalled ${reprSignals(signals)}`);

    const ticks = [0.0, 0.0, 1.0, 1.0];
    const sleeps: number[] = [];
    const oldClock = seams.monotonic;
    const oldGroupAlive = seams.ownedGroupAlive;
    try {
      seams.monotonic = () => {
        // next() on an exhausted iterator raises StopIteration; a port that read the clock more
        // often than the original would hit exactly that, which is the point of the fixture.
        if (ticks.length === 0) throw new Error("StopIteration");
        return ticks.shift()!;
      };
      seams.sleep = (s: number) => {
        sleeps.push(s);
      };
      seams.ownedGroupAlive = () => true;
      await waitForOwnedGroups([leader], new Set(), 0.5);
    } finally {
      seams.monotonic = oldClock;
      seams.sleep = oldSleep;
      seams.ownedGroupAlive = oldGroupAlive;
    }
    assert(!sleeps.some((delay) => delay < 0), `cleanup deadline produced a negative sleep [${sleeps.map((s) => floatRepr(String(s))).join(", ")}]`);

    class TimeoutProcess implements Proc {
      pid = 0;
      timeouts: (number | undefined)[] = [];
      poll(): number | null {
        return null;
      }
      wait(timeout?: number): number {
        this.timeouts.push(timeout);
        throw new TimeoutExpired(`Command 'claude' timed out after ${timeout ?? 0} seconds`);
      }
    }
    const timeoutProc = new TimeoutProcess();
    let raised = false;
    try {
      await waitForClients([timeoutProc]);
    } catch (e) {
      if (!(e instanceof TimeoutExpired)) throw e;
      raised = true;
    }
    assert(raised, "unbounded client wait");
    const t0 = timeoutProc.timeouts[0];
    assert(t0 !== undefined && t0 >= 0 && t0 <= SESSION_TIMEOUT_S, `client wait bound [${timeoutProc.timeouts.join(", ")}]`);

    const spawned = new FakeProcess(202);
    const popenCalls: SpawnOpts[] = [];
    const cleaned: number[] = [];
    const oldPopen = seams.spawn;
    const oldCleanup = seams.terminateOwnedChildren;
    try {
      seams.spawn = (argv, opts) => {
        if (argv.length === 0 || argv[0] !== "claude") throw new AssertionError("unexpected fixture launch");
        popenCalls.push(opts);
        if (popenCalls.length === 1) return spawned;
        throw new KeyboardInterrupt();
      };
      seams.terminateOwnedChildren = async (procs) => {
        cleaned.push(...procs.map((p) => p.pid));
      };
      let interrupted = false;
      try {
        await runSessions(2, obj([]), "fixture", null);
      } catch (e) {
        if (!(e instanceof KeyboardInterrupt)) throw e;
        interrupted = true;
      }
      assert(interrupted, "partial spawn interruption");
    } finally {
      seams.spawn = oldPopen;
      seams.terminateOwnedChildren = oldCleanup;
    }
    assert(
      cleaned.length === 1 && cleaned[0] === 202 && popenCalls[0]?.startNewSession === true,
      `partial spawn cleanup [${cleaned.join(", ")}], Popen=${popenCalls.length}`,
    );
  } catch (error) {
    if (!(error instanceof AssertionError)) throw error;
    process.stderr.write(`SELFTEST FAILED: ${error.message}\n`);
    return 1;
  }
  process.stdout.write(
    "bench selftest: PASS (MCP HMAC, parser, scoped first-sample coverage, workload, sampler failure, receipt guards, group cleanup, bounded wait)\n",
  );
  return 0;
}

/** A plain literal -> the tagged tree, for the selftest's fixtures (all string keys, all ints). */
function fromPlain(v: unknown): PyValue {
  if (v === null || v === undefined) return null;
  if (typeof v === "boolean" || typeof v === "string") return v;
  if (typeof v === "number") return Number.isInteger(v) ? int(v) : float(v);
  if (Array.isArray(v)) return v.map(fromPlain);
  return obj(Object.keys(v as object).map((k) => [k, fromPlain((v as Record<string, unknown>)[k])]));
}

// ---------------------------------------------------------------------------------------------

interface Args {
  control_port: number;
  mgmt_key_file: string;
  servers: string;
  sessions: number;
  model: string;
  daemon_pid: number | null;
  out: string;
}

/** argparse for this script's seven options: `--opt value` and `--opt=value`, unique-prefix
 *  abbreviations, int validation, the required set. Errors exit 2 with a `usage:` / `error:` pair. */
function parseArgs(argv: string[]): Args | { error: string } {
  const spec: Record<string, "int" | "str"> = {
    "--control-port": "int", "--mgmt-key-file": "str", "--servers": "str", "--sessions": "int",
    "--model": "str", "--daemon-pid": "int", "--out": "str",
  };
  const vals: Record<string, string> = {};
  for (let i = 0; i < argv.length; i++) {
    let flag = argv[i];
    let value: string | undefined;
    const eq = flag.indexOf("=");
    if (flag.startsWith("--") && eq > 0) {
      value = flag.slice(eq + 1);
      flag = flag.slice(0, eq);
    }
    const matches = Object.keys(spec).filter((k) => k === flag || (flag.startsWith("--") && flag.length > 2 && k.startsWith(flag)));
    const exact = matches.includes(flag) ? [flag] : matches;
    if (exact.length === 0) return { error: `unrecognized arguments: ${argv.slice(i).join(" ")}` };
    if (exact.length > 1) return { error: `ambiguous option: ${flag} could match ${exact.join(", ")}` };
    const key = exact[0];
    if (value === undefined) {
      value = argv[++i];
      if (value === undefined) return { error: `argument ${key}: expected one argument` };
    }
    if (spec[key] === "int" && !/^\s*[+-]?\d+(_\d+)*\s*$/.test(value)) {
      return { error: `argument ${key}: invalid int value: ${pyRepr(value)}` };
    }
    vals[key] = value;
  }
  const missing = ["--control-port", "--mgmt-key-file", "--servers", "--out"].filter((k) => !(k in vals));
  if (missing.length > 0) return { error: `the following arguments are required: ${missing.join(", ")}` };
  const n = (k: string) => pyInt(vals[k]);
  return {
    control_port: n("--control-port"),
    mgmt_key_file: vals["--mgmt-key-file"],
    servers: vals["--servers"],
    sessions: "--sessions" in vals ? n("--sessions") : 4,
    model: vals["--model"] ?? "claude-haiku-4-5-20251001",
    daemon_pid: "--daemon-pid" in vals ? n("--daemon-pid") : null,
    out: vals["--out"],
  };
}

/** os.path.expanduser for the `~` and `~/…` forms. */
function expandUser(p: string): string {
  if (p === "~") return os.homedir();
  if (p.startsWith("~/")) return join(os.homedir(), p.slice(2));
  return p;
}

const USAGE =
  "usage: bench.ts [-h] --control-port CONTROL_PORT --mgmt-key-file MGMT_KEY_FILE\n" +
  "                --servers SERVERS [--sessions SESSIONS] [--model MODEL]\n" +
  "                [--daemon-pid DAEMON_PID] --out OUT\n";

export async function main(argv: string[]): Promise<number> {
  if (argv.length === 1 && argv[0] === "--selftest") return selftest();
  const parsed = parseArgs(argv);
  if ("error" in parsed) {
    process.stderr.write(USAGE + `bench.ts: error: ${parsed.error}\n`);
    return 2;
  }
  const args = parsed;

  const names = args.servers.split(",").filter((s) => pyStrip(s)).map(pyStrip);
  const globalServers = readGlobalServers();
  const missing = names.filter((n) => !has(globalServers, n));
  if (missing.length > 0) {
    process.stderr.write(`not in ~/.claude.json mcpServers: ${pyRepr(missing)}\n`);
    return 2;
  }
  const managementSecret = pyStrip(readText(expandUser(args.mgmt_key_file)));
  const unhostedPairs: Mapping = [];
  for (const n of names) setKey(unhostedPairs, n, sub(globalServers, n));
  const unhosted = obj(unhostedPairs);
  const hosted = hostedServers(names, args.control_port, mcpAccessBearer(managementSecret));
  const daemonPid = args.daemon_pid || findDaemonPid();
  if (daemonPid === null) {
    process.stderr.write("no splice daemon found (pass --daemon-pid)\n");
    return 2;
  }
  const daemonHash = daemonJarSha256(daemonPid);
  if (!daemonHash) {
    process.stderr.write("BENCH FAILED: daemon jar SHA-256 is unavailable\n");
    return 2;
  }

  const withoutHead = (run: Mapping) => obj(run.filter(([k]) => k !== "outputs_head"));
  let a: Mapping;
  let b: Mapping;
  try {
    process.stdout.write(`unhosted: ${args.sessions} sessions x ${names.length} servers\n`);
    a = await runSessions(args.sessions, unhosted, args.model, null);
    process.stdout.write(dumps(withoutHead(a)) + "\n");
    process.stdout.write(`hosted via splice :${args.control_port}\n`);
    b = await runSessions(args.sessions, hosted, args.model, daemonPid);
    process.stdout.write(dumps(withoutHead(b)) + "\n");
  } catch (e) {
    if (!(e instanceof TimeoutExpired)) throw e;
    process.stderr.write(`BENCH FAILED: client workload exceeded ${SESSION_TIMEOUT_S}s\n`);
    return 1;
  }

  const aRss = num(sub(obj(a), "peak_server_rss_kb"));
  const bRss = num(sub(obj(b), "peak_server_rss_kb"));
  const saving = aRss ? 1 - bRss / aRss : null;
  // Fail closed: a receipt is only written when the run PROVES the claim — every session exited 0
  // in both modes, every session saw the same non-empty tool surface hosted as unhosted, the
  // measurement is complete, and the saving clears the 50% bar.
  const problems = receiptProblems(obj(a), obj(b), names, saving, daemonHash);
  if (problems.length > 0) {
    for (const problem of problems) process.stderr.write(`BENCH FAILED: ${problem}\n`);
    return 1;
  }
  const rounded = saving === null ? null : pyRound(saving, 3);
  const receipt = obj([
    ["kind", "mcp-host-bench"],
    ["at", new Date().toISOString().replace(/\.\d{3}Z$/, "Z")],
    ["servers", names],
    ["daemon_pid", int(daemonPid)],
    ["daemon_jar_sha256", daemonHash],
    ["model", args.model],
    ["unhosted", obj(a)],
    ["hosted", obj(b)],
    ["server_rss_saving", rounded === null ? null : float(rounded)],
    ["note", "server_rss = peak summed RSS of the MCP server process trees: under the sessions when unhosted, " +
      "under the daemon when hosted (the >50% denominator); " +
      "workload = every process under the sessions (+ daemon when hosted); daemon = the splice JVM."],
  ]);
  mkdirSync(dirname(args.out), { recursive: true });
  writeFileSync(args.out, dumpsIndent(receipt, 2) + "\n", "utf8");
  process.stdout.write(`receipt -> ${args.out}; server RSS saving = ${rounded === null ? "None" : floatRepr(String(rounded))}\n`);
  return 0;
}

if (import.meta.main) {
  process.exit(await main(process.argv.slice(2)));
}
