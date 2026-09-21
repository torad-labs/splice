#!/usr/bin/env bun
/** Local models e2e (FEATURES.md §10): starts a splice daemon from a jar against a config with one
 *  good local head and deliberately bad heads, then proves — against the live runtime — that a row
 *  naming an unlisted model or over-declaring context is refused at boot, that the good head streams,
 *  survives a cancelled turn, carries a tool result into the next turn, and that doctor reports it all
 *  as "local". Fail-closed: the receipt is written only when every check passes. One run = one runtime;
 *  run it once per runtime (Ollama, LM Studio) and keep one receipt each.
 *
 *    e2e.ts --runtime ollama --jar app-all.jar --config splice-local.toml --home /isolated/home \
 *           --good-head ollama --bad-heads ollama-unlisted,ollama-overclaim --out receipts/local-models-ollama.json
 *    e2e.ts --runtime lmstudio --jar app-all.jar --config splice-lmstudio.toml --home /isolated/home \
 *           --good-head lmstudio --bad-heads lmstudio-unlisted,lmstudio-overclaim \
 *           --out receipts/local-models-lmstudio.json
 *
 *  Stdlib only. The runtime is the operator's: this script never pulls a model or starts a runtime.
 *
 *  ─────────────────────────────────────────────────────────────────────────────────────────────
 *  V4-145: converted to TypeScript (bun). READ THIS BEFORE TRUSTING A GREEN RUN.
 *
 *  THIS FILE IS NOT EXERCISED BY THE CONVERSION, and cannot be from a build seat: it needs a live
 *  Ollama or LM Studio on localhost AND a built fat jar, and it starts a real daemon. What that means:
 *
 *    COVERED — differentialled in-process against the Python original on synthetic inputs:
 *      the SSE frame parser and its stop-after-first-delta close, text_of, stop_reason,
 *      tool_use_blocks (including input_json_delta reassembly), perf_rows, the argument parser,
 *      and receipt rendering (key order and indent).
 *
 *    NOT COVERED — transcribed only, each needing the live rig to falsify:
 *      ollama_facts / lmstudio_facts (two vendors' response shapes), the Daemon lifecycle (spawn,
 *      the "[daemon] up" wait, the process-GROUP SIGTERM/SIGKILL escalation), check_boot's refusal
 *      line reading, check_cancellation's journal evidence, and check_doctor's Java invocation.
 *      Treat the first live run as the real acceptance, not a formality.
 *
 *  Three Python behaviours are carried deliberately rather than translated:
 *
 *    json.loads, NOT JSON.parse, on every response body. The payloads are re-read with Python's
 *    value semantics — ordered keys, int/float distinction — and JSON.parse would silently reorder
 *    integer-like keys and collapse the number forms the receipt records.
 *
 *    THE RECEIPT IS A PYTHON json.dumps ARTIFACT. Receipts from the two runtimes are committed and
 *    were reviewed against each other, so key ORDER and indent=2 are part of the artifact. It is
 *    built as an ordered pair list and emitted through python-json's dumpsIndent, not JSON.stringify.
 *
 *    `print(f"runtime: {receipt['runtime']}")` IS str() OF A DICT — Python renders that with single
 *    quotes and repr()d members, not as JSON. pyStr reproduces it.
 *
 *  Bun.TOML replaces tomllib. Measured before relying on it: 27 of 27 parseable repo TOMLs give an
 *  identical structure. The one file my first comparator flagged was that comparator's own json.dumps
 *  separator bug, not a TOML divergence. The one real gap is date values — tomllib yields
 *  datetime.date, Bun.TOML yields Temporal.PlainDate; both render as "YYYY-MM-DD" through JSON, but
 *  the in-memory types differ. This script reads only strings, ints and nested tables from the
 *  topology, and exactly 2 date values exist across every TOML in this repo (both in the oracle
 *  expectations file, which nothing here reads), so the gap is recorded rather than bridged. A
 *  future consumer of a dated TOML must bridge it.
 */
import { spawn, spawnSync, type ChildProcess } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, openSync, readFileSync, unlinkSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { dumps, dumpsIndent, loads, obj, isPyNum, isPyObj, type PyValue } from "../src/compat/python-json.ts";

const ROOT = resolve(import.meta.dir, "..", "..", "..");
const STREAM_PROBE = resolve(ROOT, "tools", "e2e", "probes", "stream_probe.ts");
const TOOL: Mapping = [
  ["name", "get_weather"],
  ["description", "Current weather for a city."],
  ["input_schema", obj([
    ["type", "object"],
    ["properties", obj([["city", obj([["type", "string"]])]])],
    ["required", ["city"]],
  ])],
];

/** Python's `raise Failed(...)`: control flow, never a bug. The name is set so the traceback and
 *  any RAISED line reads the same as the original's. */
class Failed extends Error {
  constructor(message: string) {
    super(message);
    this.name = "Failed";
  }
}

const RUNTIME_LABEL: Record<string, string> = { ollama: "Ollama", lmstudio: "LM Studio" };
const RUNTIME_URL: Record<string, string> = { ollama: "http://localhost:11434", lmstudio: "http://localhost:1234" };

type Mapping = [string, PyValue][];

// ---------------------------------------------------------------------------------------------
// Python value helpers. The receipt and two print() calls are Python-formatted artifacts.
// ---------------------------------------------------------------------------------------------

function get(m: PyValue, key: string): PyValue {
  if (!isPyObj(m)) return null;
  const hit = m.__pyObj.find(([k]) => k === key);
  return hit === undefined ? null : hit[1];
}
function jsMap(m: PyValue): Record<string, PyValue> {
  const out: Record<string, PyValue> = {};
  if (isPyObj(m)) for (const [k, v] of m.__pyObj) out[k] = v;
  return out;
}
/** Python `a != b` for the scalar shapes this script compares (int/float/str/bool/None). */
function pyNe(a: PyValue, b: PyValue): boolean {
  if (isPyNum(a) && isPyNum(b)) return Number(a.__pyNum) !== Number(b.__pyNum);
  return a !== b;
}

function pyTypeName(v: PyValue): string {
  if (v === null) return "NoneType";
  if (typeof v === "boolean") return "bool";
  if (typeof v === "string") return "str";
  if (Array.isArray(v)) return "list";
  if (isPyNum(v)) return v.isFloat ? "float" : "int";
  return "dict";
}
/** `v[key]` — KeyError when absent, TypeError when v is not a mapping. THE POINT OF THIS HELPER:
 *  the original indexes these payloads directly, so a malformed frame CRASHES the harness rather
 *  than quietly yielding a default. A port that returned null instead would turn a loud failure
 *  into a silent wrong answer, which is the worse of the two. */
function pySub(v: PyValue, key: string): PyValue {
  if (!isPyObj(v)) throw new TypeError(`'${pyTypeName(v)}' object is not subscriptable`);
  const hit = v.__pyObj.find(([k]) => k === key);
  if (hit === undefined) throw new KeyError(key);
  return hit[1];
}
/** `v.get(key, default)` — AttributeError when v is a truthy non-mapping, exactly as Python. */
function pyGetD(v: PyValue, key: string, dflt: PyValue): PyValue {
  if (!isPyObj(v)) throw new TypeError(`'${pyTypeName(v)}' object has no attribute 'get'`);
  const hit = v.__pyObj.find(([k]) => k === key);
  return hit === undefined ? dflt : hit[1];
}
/** A stand-in for Python's KeyError so the failure mode is named, not conflated with a TypeError.
 *  str(KeyError(k)) is the key's REPR, quotes included — hence the wrapping. */
class KeyError extends Error {
  constructor(key: string) {
    super(`'${key}'`);
    this.name = "KeyError";
  }
}
/** Python hash/equality for the keys of `blocks`: bools alias 1/0 and equal-valued ints/floats
 *  collapse, so a block opened at `1` is found by a delta at `1.0`. A list or dict is unhashable. */
function hashKey(v: PyValue): string {
  if (typeof v === "boolean") return "n:" + (v ? "1" : "0");
  if (isPyNum(v)) return "n:" + String(Number(v.__pyNum));
  if (typeof v === "string") return "s:" + v;
  if (v === null) return "z:";
  throw new TypeError(`unhashable type: '${pyTypeName(v)}'`);
}
/** Python truthiness — `0`, `0.0`, `""`, `{}` and `[]` are falsy. */
function truthy(v: PyValue): boolean {
  if (v === null || v === false) return false;
  if (isPyNum(v)) return Number(v.__pyNum) !== 0;
  if (typeof v === "string") return v.length > 0;
  if (Array.isArray(v)) return v.length > 0;
  if (isPyObj(v)) return v.__pyObj.length > 0;
  return true;
}
const num = (x: number): PyValue =>
  Number.isInteger(x) ? { __pyNum: String(x), isFloat: false } : { __pyNum: x.toString(), isFloat: true };
const round1 = (x: number): number => Math.round(x * 10) / 10;
const lastLine = (s: string): string => s.trim().split("\n").slice(-1)[0];
const tail = (s: string, n: number): string => s.slice(-n);

/** Python repr() of any value — the shape print()/f-string use. Strings get quotes, dicts braces. */
function pyRepr(v: PyValue): string {
  if (v === null) return "None";
  if (v === true) return "True";
  if (v === false) return "False";
  if (isPyNum(v)) return v.__pyNum;
  if (typeof v === "string") {
    const q = v.includes("'") && !v.includes('"') ? '"' : "'";
    let body = "";
    for (const ch of v) {
      if (ch === "\\") body += "\\\\";
      else if (ch === q) body += "\\" + q;
      else if (ch === "\n") body += "\\n";
      else if (ch === "\r") body += "\\r";
      else if (ch === "\t") body += "\\t";
      else body += ch;
    }
    return q + body + q;
  }
  if (Array.isArray(v)) return "[" + v.map(pyRepr).join(", ") + "]";
  return "{" + (v as { __pyObj: Mapping }).__pyObj.map(([k, x]) => `${pyRepr(k)}: ${pyRepr(x)}`).join(", ") + "}";
}
/** Python str() — identical to repr() for every value this script prints. */
const pyStr = pyRepr;

/** Python repr() of BYTES, which is what the non-200 path quotes: `b'...'`, never `'...'`. The
 *  body is sliced as BYTES to 300 (not as decoded characters), and anything outside printable
 *  ASCII becomes \xNN, exactly as repr(bytes) does. */
function pyReprBytes(buf: Uint8Array): string {
  const printable = (b: number): boolean => b >= 0x20 && b < 0x7f;
  let single = 0;
  let dbl = 0;
  for (const b of buf) {
    if (b === 0x27) single += 1;
    else if (b === 0x22) dbl += 1;
  }
  const q = single > 0 && dbl === 0 ? 0x22 : 0x27;
  let body = "";
  for (const b of buf) {
    if (b === 0x5c) body += "\\\\";
    else if (b === q) body += "\\" + String.fromCharCode(q);
    else if (b === 0x0a) body += "\\n";
    else if (b === 0x0d) body += "\\r";
    else if (b === 0x09) body += "\\t";
    else if (printable(b)) body += String.fromCharCode(b);
    else body += "\\x" + b.toString(16).padStart(2, "0");
  }
  return `b${String.fromCharCode(q)}${body}${String.fromCharCode(q)}`;
}

/** Ordinary JS -> the tagged tree, for TOML output. Bun.TOML returns plain objects whose keys are
 *  non-integer-like, so Object.keys order is the document's order. */
function fromJs(v: unknown): PyValue {
  if (v === null || v === undefined) return null;
  if (typeof v === "boolean" || typeof v === "string") return v;
  if (typeof v === "number") return num(v);
  if (Array.isArray(v)) return v.map(fromJs);
  return obj(Object.keys(v as Record<string, unknown>).map((k) => [k, fromJs((v as Record<string, unknown>)[k])]));
}

function strftimeUtc(fmt: string, d: Date): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return fmt
    .replace("%Y", String(d.getUTCFullYear())).replace("%m", p(d.getUTCMonth() + 1))
    .replace("%d", p(d.getUTCDate())).replace("%H", p(d.getUTCHours()))
    .replace("%M", p(d.getUTCMinutes())).replace("%S", p(d.getUTCSeconds()));
}
function strftimeLocal(fmt: string, d: Date): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return fmt
    .replace("%Y", String(d.getFullYear())).replace("%m", p(d.getMonth() + 1))
    .replace("%d", p(d.getDate())).replace("%H", p(d.getHours()))
    .replace("%M", p(d.getMinutes())).replace("%S", p(d.getSeconds()));
}

// ---------------------------------------------------------------------------------------------
// The two runtimes' own APIs.
// ---------------------------------------------------------------------------------------------

async function runtimeJson(base: string, path: string, body: PyValue = null, timeout = 300): Promise<PyValue> {
  const init: RequestInit = {
    headers: { "Content-Type": "application/json" },
    signal: AbortSignal.timeout(timeout * 1000),
  };
  if (body !== null) {
    init.method = "POST";
    init.body = dumps(body);
  }
  const resp = await fetch(base + path, init);
  return loads(await resp.text());
}

/** `subprocess.run([...], capture_output=True)` with the FileNotFoundError/Timeout arm the original
 *  spells as `except (FileNotFoundError, subprocess.TimeoutExpired)`. spawnSync does not throw on a
 *  missing binary — it reports it in `.error` — so both arms are checked explicitly. */
function runCapture(cmd: string, args: string[], timeoutMs?: number): { status: number | null; stdout: string; stderr: string } {
  const r = spawnSync(cmd, args, { encoding: "utf8", timeout: timeoutMs });
  if (r.error) return { status: null, stdout: "", stderr: String(r.error) };
  return { status: r.status, stdout: r.stdout ?? "", stderr: r.stderr ?? "" };
}

async function runtimeFacts(runtime: string, base: string, model: string): Promise<Mapping> {
  return runtime === "ollama" ? ollamaFacts(base, model) : lmstudioFacts(base, model);
}

async function lmstudioFacts(base: string, model: string): Promise<Mapping> {
  // /api/v0/models carries max_context_length (the model's ceiling) and, once loaded,
  // loaded_context_length (the served window). Warm-up is one tiny chat completion (JIT load).
  // The API has no version endpoint; the daemon's own status line is recorded when lms is on PATH.
  const rows: Record<string, PyValue> = {};
  for (const m of (get(await runtimeJson(base, "/api/v0/models"), "data") as PyValue[])) {
    rows[String(get(m, "id"))] = m;
  }
  if (!(model in rows)) {
    throw new Failed(`${model} is not downloaded (lms ls: ${pyRepr(Object.keys(rows).sort())})`);
  }
  const t0 = performance.now();
  await runtimeJson(base, "/v1/chat/completions", obj([
    ["model", model], ["max_tokens", num(1)], ["messages", [obj([["role", "user"], ["content", "hi"]])]],
  ]));
  const loadS = round1((performance.now() - t0) / 1000);
  const after: Record<string, PyValue> = {};
  for (const m of (get(await runtimeJson(base, "/api/v0/models"), "data") as PyValue[])) {
    after[String(get(m, "id"))] = m;
  }
  const row = after[model];
  const served = get(row, "loaded_context_length");
  if (served === null) throw new Failed("/api/v0/models reports no loaded_context_length after the warm-up");
  let version: string;
  const status = runCapture("lms", ["daemon", "status"], 30_000);
  if (status.status === null) version = "unknown (lms not on PATH)";
  else version = status.status === 0 ? lastLine(status.stdout) : "unknown";
  return [
    ["runtime", "LM Studio"], ["version", version], ["model", model], ["quantization", get(row, "quantization")],
    ["card_context_length", get(row, "max_context_length")], ["num_ctx", null],
    ["served_context_length", served], ["warm_load_s", num(loadS)], ["capabilities", get(row, "capabilities")],
  ];
}

async function ollamaFacts(base: string, model: string): Promise<Mapping> {
  // Version, the model's digest, its card context, and — after a warm-up that loads it — the window
  // Ollama actually allocated (/api/ps). Warm-up is one tiny generate; the model stays loaded for the
  // daemon boot that follows, so the boot verdict reads the served window.
  const version = get(await runtimeJson(base, "/api/version"), "version");
  const tags: Record<string, PyValue> = {};
  for (const m of (get(await runtimeJson(base, "/api/tags"), "models") as PyValue[])) {
    tags[String(get(m, "name"))] = m;
  }
  if (!(model in tags)) {
    throw new Failed(`${model} is not pulled (ollama list: ${pyRepr(Object.keys(tags).sort())})`);
  }
  const show = await runtimeJson(base, "/api/show", obj([["model", model]]));
  const info = jsMap(get(show, "model_info"));
  const cardKey = Object.keys(info).find((k) => k.endsWith(".context_length"));
  const cardValue = cardKey === undefined ? null : info[cardKey];
  // re.search(r"^\s*num_ctx\s+(\d+)", parameters, re.M) — MULTILINE, so ^ anchors at every line.
  const paramsRaw = get(show, "parameters");
  const numCtx = /^[ \t]*num_ctx[ \t]+(\d+)/m.exec(typeof paramsRaw === "string" ? paramsRaw : "");
  const t0 = performance.now();
  await runtimeJson(base, "/api/generate", obj([
    ["model", model], ["prompt", "hi"], ["stream", false], ["think", false], ["keep_alive", "15m"],
  ]));
  const loadS = round1((performance.now() - t0) / 1000);
  const ps = (get(await runtimeJson(base, "/api/ps"), "models") as PyValue[]) ?? [];
  const loaded = ps.find((m) => get(m, "name") === model);
  const served = loaded === undefined ? null : get(loaded, "context_length");
  if (served === null) throw new Failed("/api/ps reports no context_length for the loaded model");
  return [
    ["runtime", "Ollama"], ["version", version], ["model", model], ["digest", get(tags[model], "digest")],
    ["card_context_length", cardValue],
    ["num_ctx", numCtx === null ? null : num(Number(numCtx[1]))],
    ["served_context_length", served], ["warm_load_s", num(loadS)], ["capabilities", get(show, "capabilities")],
  ];
}

// ---------------------------------------------------------------------------------------------

class Daemon {
  readonly jar: string;
  readonly config: string;
  readonly home: string;
  readonly port: number;
  readonly log: string;
  readonly state: string;
  proc: ChildProcess | null = null;

  constructor(jar: string, config: string, home: string, controlPort: number) {
    this.jar = jar;
    this.config = config;
    this.home = home;
    this.port = controlPort;
    this.log = resolve(home, "daemon.log");
    const doc = Bun.TOML.parse(readFileSync(config, "utf8")) as Record<string, unknown>;
    this.state = String(get(fromJs(doc["daemon"]), "state_dir"));
  }

  env(): Record<string, string> {
    return {
      ...(process.env as Record<string, string>),
      SPLICE_CONFIG: this.config,
      OLLAMA_API_KEY: "ollama",
      LMSTUDIO_API_KEY: "lmstudio",
      HOME: this.home,
    };
  }

  async start(): Promise<void> {
    mkdirSync(this.home, { recursive: true });
    const lock = resolve(this.state, "daemon.lock");
    if (existsSync(lock)) unlinkSync(lock);
    const fd = openSync(this.log, "w");
    // start_new_session=True -> detached: the child leads its own process GROUP, which is what makes
    // stop()'s killpg reach java's whole tree rather than just the JVM.
    // -Dsplice.noSystemBrowser: the wall against opening the operator's browser (LoginIo.kt) is set
    // only on the Gradle TEST JVM, and this daemon is a different JVM that inherits none of it.
    this.proc = spawn("java", ["-Dsplice.noSystemBrowser=1", `-Duser.home=${this.home}`, "-jar", this.jar, "daemon"], {
      cwd: this.home,
      env: this.env(),
      stdio: ["ignore", fd, fd],
      detached: true,
    });
    for (let i = 0; i < 240; i++) {
      if (this.proc.exitCode !== null) {
        throw new Failed(`daemon exited ${this.proc.exitCode}:\n${tail(readFileSync(this.log, "utf8"), 2000)}`);
      }
      if (readFileSync(this.log, "utf8").includes("[daemon] up")) return;
      await Bun.sleep(500);
    }
    throw new Failed("daemon did not come up in 120s:\n" + tail(readFileSync(this.log, "utf8"), 2000));
  }

  async stop(): Promise<void> {
    if (!this.proc || this.proc.exitCode !== null) return;
    const pid = this.proc.pid;
    const killpg = (sig: NodeJS.Signals): void => {
      if (pid === undefined) return;
      try {
        process.kill(-pid, sig);
      } catch {
        /* already gone */
      }
    };
    killpg("SIGTERM");
    if (!(await waitFor(this.proc, 10_000))) {
      killpg("SIGKILL");
      await waitFor(this.proc, 5_000);
    }
    const lock = resolve(this.state, "daemon.lock");
    if (existsSync(lock)) unlinkSync(lock);
  }

  mgmtKey(): string {
    return readFileSync(resolve(this.state, "mgmt-key"), "utf8").trim();
  }

  async get(path: string): Promise<PyValue> {
    const resp = await fetch(`http://127.0.0.1:${this.port}${path}`, {
      headers: { Authorization: `Bearer ${this.mgmtKey()}` },
      signal: AbortSignal.timeout(10_000),
    });
    return loads(await resp.text());
  }
}

function waitFor(proc: ChildProcess, ms: number): Promise<boolean> {
  if (proc.exitCode !== null) return Promise.resolve(true);
  return new Promise((res) => {
    const t = setTimeout(() => res(false), ms);
    proc.once("exit", () => {
      clearTimeout(t);
      res(true);
    });
  });
}

/** POST /v1/messages streaming; returns (events, raw_tail). With stopAfterFirstDelta the socket is
 *  closed as soon as the first content delta arrives — the client-abort the head must survive, and
 *  the reason this reads with an AbortController rather than simply stopping the read loop. */
export async function sse(
  port: number,
  bearer: string,
  body: { __pyObj: Mapping },
  stopAfterFirstDelta = false,
): Promise<[[string, PyValue][], string]> {
  const controller = new AbortController();
  const resp = await fetch(`http://127.0.0.1:${port}/v1/messages`, {
    method: "POST",
    body: dumps(obj([...body.__pyObj, ["stream", true]])),
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${bearer}` },
    signal: controller.signal,
  });
  if (resp.status !== 200) {
    // resp.read()[:300] — a BYTE slice of the raw body, repr()d as bytes.
    const bytes = new Uint8Array(await resp.arrayBuffer()).subarray(0, 300);
    throw new Failed(`/v1/messages -> ${resp.status}: ${pyReprBytes(bytes)}`);
  }
  const events: [string, PyValue][] = [];
  let buf = "";
  let name: string | null = null;
  const reader = resp.body!.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: false });
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += decoder.decode(value);
    while (buf.includes("\n\n")) {
      const idx = buf.indexOf("\n\n");
      const frame = buf.slice(0, idx);
      buf = buf.slice(idx + 2);
      let data: PyValue = null;
      for (const line of frame.split(/\r?\n/)) {
        if (line.startsWith("event: ")) name = line.slice(7);
        else if (line.startsWith("data: ")) data = loads(line.slice(6));
      }
      if (name) events.push([name, data]);
      if (stopAfterFirstDelta && name === "content_block_delta") {
        controller.abort();
        return [events, buf];
      }
      name = null;
    }
  }
  return [events, buf];
}

/** `"".join(d.get("delta", {}).get("text", "") for n, d in events if n == "content_block_delta"
 *  and d and d.get("delta", {}).get("type") == "text_delta")`. Each link of that chain can raise
 *  on a malformed frame, and the original lets it — so this does too, through pyGetD. */
export function textOf(events: [string, PyValue][]): string {
  const parts: PyValue[] = [];
  for (const [n, d] of events) {
    if (n !== "content_block_delta" || !truthy(d)) continue;
    if (pyGetD(pyGetD(d, "delta", obj([])), "type", null) !== "text_delta") continue;
    parts.push(pyGetD(pyGetD(d, "delta", obj([])), "text", ""));
  }
  for (let i = 0; i < parts.length; i++) {
    if (typeof parts[i] !== "string") {
      throw new TypeError(`sequence item ${i}: expected str instance, ${pyTypeName(parts[i])} found`);
    }
  }
  return (parts as string[]).join("");
}

/** `next((d["delta"].get("stop_reason") for n, d in events if n == "message_delta" and d), None)` —
 *  note the bare `d["delta"]`: a message_delta with no delta key raises rather than yielding None. */
export function stopReason(events: [string, PyValue][]): PyValue {
  for (const [n, d] of events) {
    if (n !== "message_delta" || !truthy(d)) continue;
    return pyGetD(pySub(d, "delta"), "stop_reason", null);
  }
  return null;
}

/** Reassemble tool_use blocks from content_block_start + input_json_delta. Mirrors the original's
 *  dict surgery exactly: `_json` is popped, and `input` KEEPS ITS ORIGINAL KEY POSITION when the
 *  start frame already carried one — a re-insert would move it and change the receipt's bytes. */
export function toolUseBlocks(events: [string, PyValue][]): Mapping[] {
  const blocks = new Map<string, Mapping>();
  for (const [n, d] of events) {
    if (n === "content_block_start" && pyGetD(pySub(d, "content_block"), "type", null) === "tool_use") {
      const src = pySub(d, "content_block") as { __pyObj: Mapping };
      blocks.set(hashKey(pySub(d, "index")), [
        ...src.__pyObj.map(([k, v]) => [k, v] as [string, PyValue]),
        ["_json", ""],
      ]);
    } else if (n === "content_block_delta" && blocks.has(hashKey(pySub(d, "index")))) {
      const delta = pySub(d, "delta");
      if (pyGetD(delta, "type", null) === "input_json_delta") {
        const pairs = blocks.get(hashKey(pySub(d, "index")))!;
        const j = pairs.findIndex(([k]) => k === "_json");
        const piece = pySub(delta, "partial_json");
        if (typeof piece !== "string" || typeof pairs[j][1] !== "string") {
          throw new TypeError("can only concatenate str to str");
        }
        pairs[j][1] = pairs[j][1] + piece;
      }
    }
  }
  const out: Mapping[] = [];
  for (const pairs of blocks.values()) {
    const j = pairs.findIndex(([k]) => k === "_json");
    const raw = pairs[j][1] as string;
    pairs.splice(j, 1);
    // b["input"] = json.loads(raw) if raw.strip() else b.get("input", {})
    if (raw.trim()) {
      const value = loads(raw);
      const at = pairs.findIndex(([k]) => k === "input");
      if (at >= 0) pairs[at][1] = value;
      else pairs.push(["input", value]);
    } else {
      const at = pairs.findIndex(([k]) => k === "input");
      if (at < 0) pairs.push(["input", obj([])]);
    }
    out.push(pairs);
  }
  return out;
}

export function perfRows(path: string): PyValue[] {
  if (!existsSync(path)) return [];
  return readFileSync(path, "utf8").split("\n").filter((l) => l.trim()).map((l) => loads(l));
}

async function checkBoot(d: Daemon, good: string, bad: string[]): Promise<Mapping> {
  let heads: Record<string, PyValue> = {};
  for (let i = 0; i < 60; i++) {
    heads = {};
    for (const h of (get(await d.get("/api/heads"), "heads") as PyValue[]) ?? []) {
      heads[String(get(h, "key"))] = h;
    }
    if (good in heads && truthy(get(heads[good], "healthy"))) break;
    await Bun.sleep(1000);
  }
  if (!(good in heads)) {
    throw new Failed(`good head ${good} missing from /api/heads: ${pyRepr(Object.keys(heads).sort())}`);
  }
  if (!truthy(get(heads[good], "healthy"))) {
    throw new Failed(`good head ${good} never became healthy: ${pyStr(heads[good])}`);
  }
  const present = bad.filter((b) => b in heads);
  if (present.length > 0) throw new Failed(`bad heads served instead of refused: ${pyRepr(present)}`);
  const log = readFileSync(d.log, "utf8");
  const refusals: Mapping = [];
  for (const b of bad) {
    const line = log.split("\n").find((ln) => ln.includes(b) && ln.includes("refuses"));
    if (line === undefined) throw new Failed(`no refusal line for ${b} in daemon.log`);
    refusals.push([b, line.trim()]);
  }
  const up = log.split("\n").find((ln) => ln.includes("[daemon] up"));
  if (up === undefined) throw new Failed("no [daemon] up line in daemon.log");
  if (!up.includes("DEGRADED=") || !bad.every((b) => up.includes(b))) {
    throw new Failed(`[daemon] up line does not list the refused heads: ${up}`);
  }
  const gh = heads[good];
  return [
    ["good_head", obj(["key", "port", "healthy", "authKind"].map((k) => [k, get(gh, k)] as [string, PyValue]))],
    ["refused", obj(refusals)],
    ["up_line", up.trim()],
  ];
}

async function checkStreaming(port: number, bearer: string, model: string, head: string): Promise<PyValue> {
  // sys.executable -> process.execPath, so the probe runs on the runtime this script itself runs on.
  const proc = spawnSync(
    process.execPath,
    [STREAM_PROBE, "--head", head, "--port", String(port), "--model", model,
      "--total-ms", "180000", "--first-delta-ms", "90000"],
    { encoding: "utf8", env: { ...(process.env as Record<string, string>), SPLICE_PROBE_BEARER: bearer } },
  );
  const stdout = (proc.stdout ?? "").trim();
  const summary: PyValue = stdout ? loads(lastLine(stdout)) : obj([]);
  if (proc.status !== 0) {
    const shown = isPyObj(summary) && summary.__pyObj.length > 0 ? pyStr(summary) : tail(proc.stderr ?? "", 800);
    throw new Failed(`stream_probe failed rc=${proc.status}: ${shown}`);
  }
  return summary;
}

// A client that goes away lands as one of two perf outcomes: client_abort when the turn coroutine is
// cancelled cooperatively, error:conn-reset when the tear surfaces as a failed write first (a raw
// socket close mid-frame, which is what this harness does). Both are "the client hung up".
const GONE = ["client_abort", "error:conn-reset"];

/** Ollama's own log line for a cancelled generation, when journalctl is readable; else why not. */
function ollamaCancelled(sinceEpoch: number): string {
  const since = strftimeLocal("%Y-%m-%d %H:%M:%S", new Date((sinceEpoch - 1) * 1000));
  const proc = runCapture("journalctl", ["-u", "ollama", "--since", since, "--no-pager", "-o", "cat"]);
  if (proc.status !== 0 || !proc.stdout.trim()) return "journal unavailable";
  const line = proc.stdout.split("\n").find((ln) => ln.includes("cancel task"));
  if (line === undefined) {
    throw new Failed("Ollama's journal shows no cancelled task after the client hung up — generation kept running");
  }
  return line.trim();
}

async function checkCancellation(
  port: number, bearer: string, model: string, perf: string, runtime: string,
): Promise<Mapping> {
  const goneBefore = perfRows(perf).filter((r) => GONE.includes(String(get(r, "outcome")))).length;
  const tCancel = Date.now() / 1000;
  const [events] = await sse(port, bearer, obj([
    ["model", model], ["max_tokens", num(1024)],
    ["messages", [obj([["role", "user"], ["content", "Write a 500 word essay about rivers. No thinking, start immediately."]])]],
  ]), true);
  if (!events.some(([n]) => n === "content_block_delta")) throw new Failed("no delta before the cancel");
  const t0 = performance.now();
  const [after] = await sse(port, bearer, obj([
    ["model", model], ["max_tokens", num(64)],
    ["messages", [obj([["role", "user"], ["content", "Reply with the single word OK."]])]],
  ]));
  if (!after.some(([n]) => n === "message_stop")) {
    throw new Failed("the turn after the cancel did not finish with message_stop");
  }
  let goneRows: string[] = [];
  for (let i = 0; i < 30; i++) {
    goneRows = perfRows(perf)
      .filter((r) => GONE.includes(String(get(r, "outcome"))))
      .map((r) => String(get(r, "outcome")))
      .slice(goneBefore);
    if (goneRows.length > 0) break;
    await Bun.sleep(1000);
  }
  if (goneRows.length === 0) {
    throw new Failed("no client_abort / error:conn-reset perf row after the cancelled turn");
  }
  await Bun.sleep(2000);
  return [
    ["deltas_before_cancel", num(events.filter(([n]) => n === "content_block_delta").length)],
    ["next_turn_s", num(round1((performance.now() - t0) / 1000))],
    ["next_turn_text", textOf(after).slice(0, 80)],
    ["head_outcome", goneRows],
    ["runtime_evidence", runtime === "ollama"
      ? ollamaCancelled(tCancel)
      : "LM Studio keeps no journal; the head's client-gone row and the prompt next turn are the evidence"],
  ];
}

async function checkToolContinuity(port: number, bearer: string, model: string): Promise<Mapping> {
  const user = obj([["role", "user"], ["content", "What is the weather in Lisbon right now? You must call get_weather."]]);
  const [first] = await sse(port, bearer, obj([
    ["model", model], ["max_tokens", num(512)], ["tools", [obj(TOOL)]], ["messages", [user]],
  ]));
  const uses = toolUseBlocks(first);
  if (uses.length === 0 || get(obj(uses[0]), "name") !== "get_weather") {
    throw new Failed(
      `no get_weather tool_use in the first turn (stop=${pyRepr(stopReason(first))}, text=${pyRepr(textOf(first).slice(0, 120))})`,
    );
  }
  if (stopReason(first) !== "tool_use") {
    throw new Failed(`stop_reason ${pyRepr(stopReason(first))}, expected tool_use`);
  }
  const use = obj(uses[0]);
  const assistant = obj([
    ["role", "assistant"],
    ["content", [obj([
      ["type", "tool_use"], ["id", get(use, "id")], ["name", get(use, "name")], ["input", get(use, "input")],
    ])]],
  ]);
  const result = obj([
    ["role", "user"],
    ["content", [obj([
      ["type", "tool_result"], ["tool_use_id", get(use, "id")],
      ["content", "Lisbon: sunny, 22 degrees Celsius, light breeze."],
    ])]],
  ]);
  const [second] = await sse(port, bearer, obj([
    ["model", model], ["max_tokens", num(512)], ["tools", [obj(TOOL)]], ["messages", [user, assistant, result]],
  ]));
  const text = textOf(second);
  if (!text.includes("22")) {
    throw new Failed(
      `second turn did not use the tool result: stop=${pyRepr(stopReason(second))}, text=${pyRepr(text.slice(0, 200))}`,
    );
  }
  return [
    ["tool_use_id", get(use, "id")], ["input", get(use, "input")],
    ["first_stop_reason", stopReason(first)], ["second_stop_reason", stopReason(second)],
    ["second_text", text.slice(0, 160)],
  ];
}

/** `bad` is UNUSED HERE IN THE ORIGINAL TOO — check_doctor takes it and reads only bad_rows. Kept so
 *  the signature still matches the caller and a future diff of the two files does not read as a
 *  porting omission. */
async function checkDoctor(
  d: Daemon, good: string, bad: string[], model: string, badRows: Mapping, label: string,
): Promise<Mapping> {
  void bad;
  // Same browser wall as the daemon spawn above: a separate JVM inherits no Gradle test property.
  const proc = spawnSync("java", ["-Dsplice.noSystemBrowser=1", `-Duser.home=${d.home}`, "-jar", d.jar, "doctor", "--json"], {
    env: d.env(),
    encoding: "utf8",
    timeout: 120_000,
  });
  if (proc.status !== 0 && proc.status !== 1) {
    throw new Failed(`doctor --json rc=${proc.status}: ${tail(proc.stderr ?? "", 500)}`);
  }
  const report = loads(proc.stdout ?? "");
  // rows are keyed "<section>/<check name>"; a local check's own name starts with "local:".
  const local: Record<string, PyValue> = {};
  for (const c of (get(report, "checks") as PyValue[]) ?? []) {
    const id = String(get(c, "id") ?? "");
    if (id.includes("/local:")) local[id.split("/").slice(1).join("/")] = c;
  }
  const wantOk = [`local:${good}`, `local:${good}/${model}`];
  // `f"local:{b}/{row}"` — an f-string of a str, so NO quotes around the model id.
  const wantFail = badRows.map(([b, row]) => `local:${b}/${String(row)}`);
  for (const name of wantOk) {
    if (name in local && get(local[name], "status") === "ok") continue;
    throw new Failed(`doctor ${name}: ${pyStr(local[name] ?? null)}`);
  }
  for (const name of wantFail) {
    if (name in local && get(local[name], "status") === "fail") continue;
    throw new Failed(`doctor ${name}: ${pyStr(local[name] ?? null)}`);
  }
  // The original's second clause — `"local" not in f"local:{good}"` — is a constantly-false
  // expression, so the runtime label is the whole condition. Carried as the original behaves.
  const summary = get(local[`local:${good}`], "detail");
  if (typeof summary !== "string" || !summary.includes(label)) {
    throw new Failed(`doctor summary does not name the runtime: ${pyStr(summary)}`);
  }
  return Object.keys(local).map((name) => [
    name,
    obj([["status", get(local[name], "status")], ["detail", get(local[name], "detail")]]),
  ] as [string, PyValue]);
}

// ---------------------------------------------------------------------------------------------

export interface Args {
  jar: string; config: string; home: string; control_port: number; runtime: string;
  runtime_url: string | null; good_head: string; bad_heads: string; out: string; keep_daemon: boolean;
}

export function parseArgs(argv: string[]): Args | null {
  const out: Args = {
    jar: "", config: "", home: "", control_port: 3196, runtime: "ollama", runtime_url: null,
    good_head: "ollama", bad_heads: "ollama-unlisted,ollama-overclaim", out: "", keep_daemon: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--keep-daemon") {
      out.keep_daemon = true;
      continue;
    }
    if (argv[i + 1] === undefined) return null;
    const v = argv[++i];
    switch (a) {
      case "--jar": out.jar = v; break;
      case "--config": out.config = v; break;
      case "--home": out.home = v; break;
      case "--out": out.out = v; break;
      case "--good-head": out.good_head = v; break;
      case "--bad-heads": out.bad_heads = v; break;
      case "--runtime-url": out.runtime_url = v; break;
      case "--runtime":
        if (!(v in RUNTIME_LABEL)) return null;
        out.runtime = v;
        break;
      case "--control-port": out.control_port = Number(v); break;
      default: return null;
    }
  }
  if (!out.jar || !out.config || !out.home || !out.out) return null;
  return out;
}

export async function main(argv: string[]): Promise<number> {
  const args = parseArgs(argv);
  if (args === null) {
    process.stderr.write("e2e.ts: --jar, --config, --home and --out are required\n");
    return 2;
  }
  const runtimeUrl = args.runtime_url ?? RUNTIME_URL[args.runtime];
  const bad = args.bad_heads.split(",").filter((b) => b !== "");
  const topo = fromJs(Bun.TOML.parse(readFileSync(args.config, "utf8")));
  const providers = jsMap(get(topo, "providers"));
  // topo["providers"][head]["models"][0]
  const firstModel = (key: string): PyValue => ((jsMap(providers[key] ?? null)["models"] ?? []) as PyValue[])[0];
  const goodRow = firstModel(args.good_head);
  const goodModel = get(goodRow, "id");
  const badRows: Mapping = bad.map((b) => [b, get(firstModel(b), "id")]);

  const d = new Daemon(resolve(args.jar), resolve(args.config), resolve(args.home), args.control_port);
  const receipt: Mapping = [
    ["kind", "local-models-e2e"],
    ["at", strftimeUtc("%Y-%m-%dT%H:%M:%SZ", new Date())],
    ["jar_sha256", createHash("sha256").update(readFileSync(args.jar)).digest("hex")],
    ["vllm", obj([["tested", false], ["documented", "tools/e2e/local-models/README.md"]])],
  ];
  const label = RUNTIME_LABEL[args.runtime];
  try {
    const runtime = await runtimeFacts(args.runtime, runtimeUrl, String(goodModel));
    receipt.push(["runtime", obj(runtime)]);
    const declared = get(goodRow, "context_window");
    const served = get(obj(runtime), "served_context_length");
    if (pyNe(declared, served)) {
      throw new Failed(`config declares ${pyStr(declared)} for the good row; ${label} serves ${pyStr(served)} — align the config`);
    }
    receipt.push(["context_limit", obj([
      ["declared", declared], ["served", served], ["card", get(obj(runtime), "card_context_length")],
    ])]);
    process.stdout.write(`runtime: ${pyStr(obj(runtime))}\n`);
    await d.start();
    const boot = await checkBoot(d, args.good_head, bad);
    receipt.push(["boot", obj(boot)]);
    process.stdout.write(`boot: ${pyStr(get(obj(boot), "up_line"))}\n`);
    const port = Number((get(get(obj(boot), "good_head"), "port") as { __pyNum: string }).__pyNum);
    const bearer = d.mgmtKey();
    const modelsResp = await fetch(`http://127.0.0.1:${port}/v1/models`, {
      headers: { Authorization: `Bearer ${bearer}` },
      signal: AbortSignal.timeout(10_000),
    });
    // models["data"][0]["id"] — the first advertised model on the good head.
    const models = loads(await modelsResp.text());
    const model = String(get((get(models, "data") as PyValue[])[0], "id"));
    receipt.push(["head_model", model]);
    const streaming = await checkStreaming(port, bearer, model, args.good_head);
    receipt.push(["streaming", streaming]);
    process.stdout.write(`streaming: ${pyStr(streaming)}\n`);
    const cancellation = await checkCancellation(
      port, bearer, model, resolve(d.state, `${args.good_head}-perf.jsonl`), args.runtime,
    );
    receipt.push(["cancellation", obj(cancellation)]);
    process.stdout.write(`cancellation: ${pyStr(obj(cancellation))}\n`);
    const continuity = await checkToolContinuity(port, bearer, model);
    receipt.push(["tool_continuity", obj(continuity)]);
    process.stdout.write(`tool continuity: ${pyStr(obj(continuity))}\n`);
    const doctor = await checkDoctor(d, args.good_head, bad, String(goodModel), badRows, label);
    receipt.push(["doctor", obj(doctor)]);
    process.stdout.write(`doctor: ${dumpsIndent(obj(doctor), 1)}\n`);
  } catch (e) {
    if (!(e instanceof Failed)) throw e;
    process.stderr.write(`E2E FAILED: ${e.message}\n`);
    return 1;
  } finally {
    if (!args.keep_daemon) await d.stop();
  }
  mkdirSync(dirname(args.out), { recursive: true });
  writeFileSync(args.out, dumpsIndent(obj(receipt), 2) + "\n", "utf8");
  process.stdout.write(`receipt -> ${args.out}\n`);
  return 0;
}

if (import.meta.main) {
  process.exit(await main(process.argv.slice(2)));
}
