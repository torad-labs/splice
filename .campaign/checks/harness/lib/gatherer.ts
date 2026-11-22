// What the Context Getter checks stand on (docs/specs/harness.md, decision 9): a stand-in for Claude Code's `claude`,
// put first on the daemon's PATH, that records how it was started and answers the way `claude -p` does; a provider
// that speaks Anthropic's Messages API and can be slow for a session; a head whose models carry labels of their own;
// a session registered the way Claude Code registers one, so splice can find its folder; and the places a turn's
// record can be read.
//
// The stand-in reads what a real Claude Code would read and nothing more: its argv, its environment, the settings
// files Claude Code layers (the config folder's settings.json, the folder's project settings, every --settings), and
// its stdin. From those it works out what a real one would run as: the model, the effort it would think at, the
// address and key it would call, the headers it would add, and whether it would emit tool-use summaries. It records
// that, makes one real request to that address, and prints a scripted stream.

import { spawnSync } from "node:child_process";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { Splice } from "../../server/lib/harness.ts";
import type { Block, Message, SeenTurn } from "./capability.ts";

type Server = ReturnType<typeof Bun.serve>;

/** How the stand-in's next runs behave. */
export interface Behaviour {
  /** `ok` ends on a result; `fail` exits 1 with no result; `empty` exits 0 with no result; `hang` never ends. */
  mode: "ok" | "fail" | "empty" | "hang";
  /** Milliseconds after its request before its first summary. */
  delayMs: number;
  /** The tool-use summaries it emits, `gapMs` apart, when Claude Code would emit them. */
  summaries: string[];
  gapMs: number;
  /** The result's text; empty takes one that names the run. */
  result: string;
  /** Whether it makes its one request. */
  request: boolean;
}

export const GATHERS: Behaviour = { mode: "ok", delayMs: 0, summaries: [], gapMs: 0, result: "", request: true };

/** One run of the stand-in, as it recorded itself. */
export interface Run {
  run: string;
  pid: number;
  argv: string[];
  cwd: string;
  /** Its process environment. */
  env: Record<string, string>;
  /** The environment a real Claude Code would run with: the process's, with every settings layer's `env` over it. */
  effectiveEnv: Record<string, string>;
  /** stdin, as far as it was handed any. */
  stdin: string;
  /** argv and stdin together: where a prompt can be. */
  input: string;
  print: boolean;
  /** The model it would name, as it would name it. */
  model: string | null;
  /** The effort it would think at; null is Claude Code's own default for the model. */
  effort: string | null;
  /** Whether it would emit tool-use summaries. */
  summaries: boolean;
  /** Its own session id, which its request names. */
  session: string;
  at: number;
  request?: { url: string; headers: Record<string, string>; status: number; error?: string; at: number };
  emitted: { text: string; at: number }[];
  end?: { code: number; at: number; why: string };
}

// The stand-in. It runs under bun, from the file the helper writes. Everything it learns goes to RECORD as JSON lines.
const FAKE = String.raw`
import { appendFileSync, existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { join } from "node:path";

const RECORD = process.env.__GATHER_RECORD__ || "";
const CONTROL = process.env.__GATHER_CONTROL__ || "";
const argv = process.argv.slice(2);
const run = crypto.randomUUID();
const note = (o) => { try { appendFileSync(RECORD, JSON.stringify({ run, at: Date.now(), ...o }) + "\n"); } catch {} };

if (argv.includes("--version") || argv.includes("-v")) { console.log("2.1.326 (Claude Code)"); process.exit(0); }

const values = (flags) => {
  const out = [];
  argv.forEach((a, i) => {
    for (const f of flags) {
      if (a === f && argv[i + 1] !== undefined) out.push(argv[i + 1]);
      else if (a.startsWith(f + "=")) out.push(a.slice(f.length + 1));
    }
  });
  return out;
};
const last = (flags) => values(flags).at(-1) ?? null;

const readJson = (text) => { try { return JSON.parse(text); } catch { return null; } };
const fileJson = (p) => (existsSync(p) ? readJson(readFileSync(p, "utf8")) ?? {} : {});
const merge = (a, b) => {
  const out = { ...a };
  for (const [k, v] of Object.entries(b ?? {})) {
    out[k] = v && typeof v === "object" && !Array.isArray(v) && a[k] && typeof a[k] === "object" ? merge(a[k], v) : v;
  }
  return out;
};

// Claude Code's settings layers, lowest first: the user's (its config folder), the project's, then every --settings.
const configDir = process.env.CLAUDE_CONFIG_DIR || join(process.env.HOME || "", ".claude");
let settings = fileJson(join(configDir, "settings.json"));
settings = merge(settings, fileJson(join(process.cwd(), ".claude", "settings.json")));
settings = merge(settings, fileJson(join(process.cwd(), ".claude", "settings.local.json")));
for (const v of values(["--settings"])) {
  const one = v.trimStart().startsWith("{") ? readJson(v) : fileJson(v);
  settings = merge(settings, one ?? {});
}
const env = { ...process.env, ...(settings.env ?? {}) };

// stdin: read what is handed, without waiting on a pipe nobody closes.
let stdin = "";
if (!process.stdin.isTTY) {
  const reader = Bun.stdin.stream().getReader();
  const dec = new TextDecoder();
  let quietFor = 1500;
  for (;;) {
    const got = await Promise.race([reader.read(), Bun.sleep(quietFor).then(() => null)]);
    if (got === null || got.done) break;
    stdin += dec.decode(got.value, { stream: true });
    quietFor = 400;
  }
}
const input = argv.join("\n") + "\n" + stdin;
const print = argv.includes("-p") || argv.includes("--print");

const alias = (m) => {
  if (!m) return m;
  const tier = { haiku: "HAIKU", sonnet: "SONNET", opus: "OPUS" }[m.toLowerCase()];
  return tier ? env["ANTHROPIC_DEFAULT_" + tier + "_MODEL"] || (tier === "HAIKU" ? env.ANTHROPIC_SMALL_FAST_MODEL : null) || m : m;
};
const model = alias(last(["--model"]) ?? env.ANTHROPIC_MODEL ?? settings.model ?? null);

// The effort a real Claude Code thinks at: --effort, else its environment word ("unset" sends none), else the
// settings' word for this model, else its own default (null here).
const effortArg = last(["--effort"]);
const effortEnv = env.CLAUDE_CODE_EFFORT_LEVEL;
const ms = settings.modelSettings ?? {};
const effort = effortArg ?? (effortEnv ? (effortEnv === "unset" ? null : effortEnv) : (model && ms[model]?.effortLevel) || null);

const truthy = (v) => v !== undefined && v !== "" && v !== "0" && v.toLowerCase?.() !== "false";
const summaries = truthy(env.CLAUDE_CODE_EMIT_TOOL_USE_SUMMARIES);
const session = last(["--session-id"]) ?? crypto.randomUUID();

note({ event: "start", pid: process.pid, argv, cwd: process.cwd(), env: process.env, effectiveEnv: env, stdin, input, print, model, effort, summaries, session });

const control = (() => {
  const c = CONTROL && existsSync(CONTROL) ? readJson(readFileSync(CONTROL, "utf8")) ?? {} : {};
  const hit = (c.when ?? []).find((w) => input.includes(w.contains));
  return { mode: "ok", delayMs: 0, summaries: [], gapMs: 0, result: "", request: true, ...(c.default ?? {}), ...(hit?.behaviour ?? {}) };
})();

let ended = false;
const end = (code, why) => { if (ended) return; ended = true; note({ event: "end", code, why }); process.exit(code); };
for (const sig of ["SIGTERM", "SIGINT", "SIGHUP"]) process.on(sig, () => end(128 + { SIGHUP: 1, SIGINT: 2, SIGTERM: 15 }[sig], sig));

const format = last(["--output-format"]) ?? "text";
if (print && format === "stream-json" && !argv.includes("--verbose")) {
  console.error("Error: When using --print, --output-format=stream-json requires --verbose");
  end(1, "stream-json without --verbose");
}
const stream = format === "stream-json";
const out = (o) => { if (stream) process.stdout.write(JSON.stringify(o) + "\n"); };

out({ type: "system", subtype: "init", session_id: session, cwd: process.cwd(), model, tools: ["Read", "Grep", "Glob", "Bash"] });

// One request, the way Claude Code makes one: where its environment points it, with its key and its headers.
// A run whose own prompt came from another run's request is a nested one, and makes none.
const nested = input.includes("[gatherer ");
if (print && control.request && !nested) {
  const mantle = truthy(env.CLAUDE_CODE_USE_MANTLE);
  const base = mantle ? env.ANTHROPIC_BEDROCK_MANTLE_BASE_URL : env.ANTHROPIC_BASE_URL;
  const headers = {
    "content-type": "application/json",
    "anthropic-version": "2023-06-01",
    "user-agent": "claude-cli/2.1.326 (external, sdk-cli)",
    "x-app": "cli",
    "x-claude-code-session-id": session,
  };
  if (mantle) {
    if (env.AWS_BEARER_TOKEN_BEDROCK) headers.authorization = "Bearer " + env.AWS_BEARER_TOKEN_BEDROCK;
  } else {
    let key = "";
    if (settings.apiKeyHelper) {
      const r = spawnSync("bash", ["-c", settings.apiKeyHelper], { env, cwd: process.cwd(), encoding: "utf8" });
      key = (r.stdout ?? "").trim();
    }
    if (key) headers["x-api-key"] = key;
    else if (env.ANTHROPIC_API_KEY) headers["x-api-key"] = env.ANTHROPIC_API_KEY;
    else if (env.ANTHROPIC_AUTH_TOKEN) headers.authorization = "Bearer " + env.ANTHROPIC_AUTH_TOKEN;
  }
  for (const line of String(env.ANTHROPIC_CUSTOM_HEADERS ?? "").split("\n")) {
    const at = line.indexOf(":");
    if (at > 0) headers[line.slice(0, at).trim().toLowerCase()] = line.slice(at + 1).trim();
  }
  const url = base ? base.replace(/\/+$/, "") + (mantle ? "/v1/messages?beta=true" : "/v1/messages") : "";
  const user = JSON.stringify({ device_id: "fa4e".repeat(16), account_uuid: "", session_id: session });
  const body = {
    model: model ?? "claude-haiku-4-5",
    max_tokens: 1024,
    stream: true,
    metadata: { user_id: user },
    system: "You are Claude Code, Anthropic's official CLI for Claude.",
    tools: [{ name: "Read", description: "Read a file.", input_schema: { type: "object", properties: { file_path: { type: "string" } }, required: ["file_path"] } }],
    messages: [{ role: "user", content: [{ type: "text", text: "[gatherer " + run + "] " + input.slice(0, 4000) }] }],
  };
  let status = 0;
  let error;
  if (!url) error = "no address to call";
  else {
    try {
      const r = await fetch(url, { method: "POST", headers, body: JSON.stringify(body) });
      status = r.status;
      await r.text();
    } catch (e) {
      error = String(e);
    }
  }
  note({ event: "request", url, headers, status, error });
}

const said = async (text) => { note({ event: "summary", text }); out({ type: "tool_use_summary", summary: text, preceding_tool_use_ids: [crypto.randomUUID()], session_id: session, uuid: crypto.randomUUID() }); };

await Bun.sleep(control.delayMs);
if (control.mode === "hang") {
  if (summaries) for (const s of control.summaries) { await said(s); await Bun.sleep(control.gapMs); }
  for (;;) await Bun.sleep(60_000);
}
if (control.mode === "fail") {
  if (summaries && control.summaries[0]) await said(control.summaries[0]);
  await Bun.sleep(control.gapMs);
  console.error("Error: the run failed");
  end(1, "fail");
}
if (summaries) {
  for (const [i, s] of control.summaries.entries()) {
    await said(s);
    if (i < control.summaries.length - 1) await Bun.sleep(control.gapMs);
  }
  if (control.summaries.length) await Bun.sleep(control.gapMs);
}
if (control.mode === "empty") end(0, "empty");
const result = control.result || "What the gatherer found (run " + run + "): src/main.ts:10-42 holds the entry point; ./gradlew test runs the tests.";
if (stream) out({ type: "result", subtype: "success", is_error: false, duration_ms: 1200, num_turns: 3, result, session_id: session, total_cost_usd: 0, usage: { input_tokens: 12, output_tokens: 40 } });
else if (format === "json") process.stdout.write(JSON.stringify({ type: "result", subtype: "success", is_error: false, result, session_id: session }) + "\n");
else process.stdout.write(result + "\n");
note({ event: "result", text: result });
end(0, "result");
`;

/** The stand-in `claude`, with its record and what the test tells it to do. */
export class Fake {
  readonly dir: string;
  readonly bin: string;
  private readonly record: string;
  private readonly control: string;
  private readonly script: string;
  private rules: { contains: string; behaviour: Partial<Behaviour> }[] = [];
  private fallback: Partial<Behaviour> = {};

  constructor() {
    this.dir = mkdtempSync(join(tmpdir(), "splice-gatherer-"));
    this.bin = join(this.dir, "bin");
    mkdirSync(this.bin, { recursive: true });
    this.record = join(this.dir, "runs.jsonl");
    this.control = join(this.dir, "control.json");
    this.script = join(this.dir, "claude-fake.ts");
    writeFileSync(this.script, FAKE);
    writeFileSync(this.record, "");
    const claude = join(this.bin, "claude");
    // The record and control paths ride the environment under names no Claude Code reads; exec keeps one pid.
    writeFileSync(
      claude,
      `#!/bin/sh\n__GATHER_RECORD__='${this.record}' __GATHER_CONTROL__='${this.control}' exec '${process.execPath}' '${this.script}' "$@"\n`,
    );
    chmodSync(claude, 0o755);
    this.write();
    // A check whose beforeAll fails never reaches its afterAll: the folder goes when the test process does.
    process.on("exit", () => rmSync(this.dir, { recursive: true, force: true }));
  }

  /** The PATH a daemon runs with to start this stand-in as `claude`. */
  get path(): string {
    return `${this.bin}:${process.env.PATH ?? "/usr/bin:/bin"}`;
  }

  /** How every run behaves that no `when` names. */
  set(b: Partial<Behaviour>): void {
    this.fallback = b;
    this.write();
  }

  /** How a run behaves whose input holds `contains`. */
  when(contains: string, b: Partial<Behaviour>): void {
    this.rules.push({ contains, behaviour: b });
    this.write();
  }

  reset(): void {
    this.rules = [];
    this.fallback = {};
    this.write();
  }

  private write(): void {
    writeFileSync(this.control, JSON.stringify({ default: this.fallback, when: this.rules }));
  }

  /** Every run, as recorded so far. */
  runs(): Run[] {
    const by = new Map<string, Run>();
    for (const line of readFileSync(this.record, "utf8").split("\n")) {
      if (!line.trim()) continue;
      const e = JSON.parse(line) as Record<string, unknown> & { run: string; event: string; at: number };
      if (e.event === "start") by.set(e.run, { ...(e as unknown as Run), emitted: [] });
      const r = by.get(e.run);
      if (!r) continue;
      if (e.event === "request") r.request = e as unknown as Run["request"];
      if (e.event === "summary") r.emitted.push({ text: String(e.text), at: e.at });
      if (e.event === "end") r.end = { code: Number(e.code), at: e.at, why: String(e.why) };
    }
    return [...by.values()];
  }

  /** The print-mode runs started for a session whose first message holds `typed`. */
  of(typed: string): Run[] {
    return this.runs().filter((r) => r.print && r.input.includes(typed));
  }

  dispose(): void {
    for (const r of this.runs()) if (alive(r)) kill(r.pid);
    rmSync(this.dir, { recursive: true, force: true });
  }
}

/** Whether run `r`'s process is still running: its pid is there, is not a zombie, and is still the stand-in. */
export function alive(r: Run): boolean {
  try {
    const stat = readFileSync(`/proc/${r.pid}/stat`, "utf8");
    const state = stat.slice(stat.lastIndexOf(")") + 2, stat.lastIndexOf(")") + 3);
    if (state === "Z" || state === "X") return false;
    return readFileSync(`/proc/${r.pid}/cmdline`, "latin1").includes("claude-fake.ts");
  } catch {
    return false;
  }
}

function kill(pid: number): void {
  try {
    process.kill(pid, "SIGKILL");
  } catch {}
}

/** A provider that speaks Anthropic's Messages API: it records each request and answers `ok from <name>`, after
 *  `slow(body)` milliseconds. */
export class Backend {
  readonly name: string;
  readonly turns: SeenTurn[] = [];
  slow: (body: SeenTurn["body"]) => number = () => 0;
  private server: Server | null = null;

  constructor(name: string) {
    this.name = name;
  }

  start(): this {
    this.server = Bun.serve({ port: 0, hostname: "127.0.0.1", idleTimeout: 0, fetch: (req) => this.answer(req) });
    return this;
  }

  stop(): void {
    this.server?.stop(true);
  }

  get url(): string {
    return `http://127.0.0.1:${this.server!.port}`;
  }

  reply(): string {
    return `ok from ${this.name}`;
  }

  /** The requests of session `session`, oldest first, read where the body names it: `metadata.user_id`. */
  of(session: string): SeenTurn[] {
    return this.turns.filter((t) => sessionOf(t.body) === session);
  }

  private async answer(req: Request): Promise<Response> {
    const path = new URL(req.url).pathname;
    if (!path.endsWith("/v1/messages")) return Response.json({ type: "error", error: { type: "not_found_error", message: `no route ${path}` } }, { status: 404 });
    const raw = await req.text();
    const body = JSON.parse(raw) as SeenTurn["body"];
    this.turns.push({ headers: Object.fromEntries(req.headers), body, raw, at: Date.now() });
    const wait = this.slow(body);
    if (wait) await Bun.sleep(wait);
    const usage = { input_tokens: 12, cache_creation_input_tokens: 0, cache_read_input_tokens: 0, output_tokens: 4 };
    if (body.stream !== true) {
      return Response.json({ id: "msg_check", type: "message", role: "assistant", model: body.model, content: [{ type: "text", text: this.reply() }], stop_reason: "end_turn", stop_sequence: null, usage });
    }
    const frames = [
      { type: "message_start", message: { id: "msg_check", type: "message", role: "assistant", model: body.model, content: [], stop_reason: null, stop_sequence: null, usage: { ...usage, output_tokens: 1 } } },
      { type: "content_block_start", index: 0, content_block: { type: "text", text: "" } },
      { type: "content_block_delta", index: 0, delta: { type: "text_delta", text: this.reply() } },
      { type: "content_block_stop", index: 0 },
      { type: "message_delta", delta: { stop_reason: "end_turn", stop_sequence: null }, usage: { output_tokens: 4 } },
      { type: "message_stop" },
    ];
    const text = frames.map((f) => `event: ${f.type}\ndata: ${JSON.stringify(f)}\n\n`).join("");
    return new Response(text, { headers: { "content-type": "text/event-stream", "cache-control": "no-cache" } });
  }
}

/** The session a request's body names in `metadata.user_id`, the way Claude Code 2.1.326 names it. */
export function sessionOf(body: SeenTurn["body"]): string | null {
  const id = (body.metadata as { user_id?: unknown } | undefined)?.user_id;
  if (typeof id !== "string") return null;
  try {
    return String((JSON.parse(id) as { session_id?: unknown }).session_id ?? "") || null;
  } catch {
    return null;
  }
}

export interface Model {
  id: string;
  label: string;
}

/** A head on a provider that speaks Anthropic's Messages API, called with the key in `keyEnv` (MOCK_UPSTREAM_KEY by
 *  default). Its opus and sonnet tiers run `large`, its haiku tier (its fast model) runs `small`, and `more` are
 *  further models of its provider. `extra` is more of the head's own lines. */
export function labelledHead(o: { key: string; command: string; port: number; url: string; large: Model; small: Model; more?: Model[]; keyEnv?: string; extra?: string }): string {
  const model = (m: Model) => `
[[providers.${o.key}.models]]
id = "${m.id}"
label = "${m.label}"
context_window = 200000
rates = { input = 2.0, cache_read = 0.5, output = 8.0 }
`;
  return `
[providers.${o.key}]
dialect = "anthropic-passthrough"
base_url = "${o.url}"
auth = { kind = "api-key", env = "${o.keyEnv ?? "MOCK_UPSTREAM_KEY"}" }
extra_headers = { anthropic-version = "2023-06-01" }
model_slots = { opus = "${o.large.id}", sonnet = "${o.large.id}", haiku = "${o.small.id}" }
${[o.large, o.small, ...(o.more ?? [])].map(model).join("")}
[heads.${o.key}]
provider = "${o.key}"
port = ${o.port}
discovery_prefix = "claude-${o.key}--"
pinned_model = "${o.large.id}"
${o.extra ?? ""}
[heads.${o.key}.claude]
command = "${o.command}"
`;
}

/** The Context Getter switched on for `heads`, with its own defaults and `extra` lines over them. */
export function contextGetter(heads: string[], extra = "", worker = ""): string {
  return `
[capabilities.context-getter]
heads = [${heads.map((h) => `"${h}"`).join(", ")}]
worker = { kind = "builtin", name = "context-getter"${worker} }
${extra}
`;
}

/** A project folder of its own, with a file in it, for one session. */
export function folder(s: Splice, name: string): string {
  const dir = join(s.home, "work", name);
  mkdirSync(join(dir, "src"), { recursive: true });
  writeFileSync(join(dir, "src", "main.ts"), "export function main(): void {}\n");
  return dir;
}

/** Session `session` registered under the Claude config folder `dir` the way Claude Code registers a running one,
 *  in `cwd`: its live record in `sessions/`, and its transcript under `projects/`, which holds the message typed. */
export function register(dir: string, session: string, cwd: string, typed = ""): void {
  mkdirSync(join(dir, "sessions"), { recursive: true });
  writeFileSync(join(dir, "sessions", `${process.pid}-${session.slice(0, 8)}.json`), JSON.stringify({ pid: process.pid, sessionId: session, cwd, startedAt: Date.now() }));
  const project = join(dir, "projects", cwd.replace(/[^a-zA-Z0-9]/g, "-"));
  mkdirSync(project, { recursive: true });
  const row = { type: "user", sessionId: session, cwd, uuid: crypto.randomUUID(), timestamp: new Date().toISOString(), message: { role: "user", content: typed } };
  writeFileSync(join(project, `${session}.jsonl`), `${JSON.stringify(row)}\n`);
}

/** Whether a line splice wrote (its log since `from`, a file under its state folder, or a record of the session's turns
 *  on head `key`) names session `session`, the capability `name`, and matches `reason`. */
export async function recorded(s: Splice, o: { key: string; session: string; name?: string; reason: RegExp; from?: number }): Promise<string | null> {
  const name = o.name ?? "context-getter";
  const lines: string[] = [];
  if (existsSync(s.log)) lines.push(...readFileSync(s.log, "utf8").slice(o.from ?? 0).split("\n"));
  const walk = (d: string) => {
    for (const n of readdirSync(d)) {
      const p = join(d, n);
      let st;
      try {
        st = statSync(p);
      } catch {
        continue;
      }
      if (st.isDirectory()) walk(p);
      else if (st.isFile() && st.size < 4_000_000) {
        const text = readFileSync(p, "latin1");
        if (text.includes(o.session)) lines.push(...text.split("\n"));
      }
    }
  };
  if (existsSync(s.state)) walk(s.state);
  try {
    const j = (await (await s.api(`/api/perf/turns?head=${o.key}`)).json()) as { heads: { key: string; rows: Record<string, unknown>[] }[] };
    for (const r of j.heads.find((h) => h.key === o.key)?.rows ?? []) lines.push(JSON.stringify(r));
  } catch {}
  return lines.find((l) => l.includes(o.session) && l.includes(name) && o.reason.test(l)) ?? null;
}

/** The text of a message's blocks, joined. */
export function textOf(blocks: Block[]): string {
  return blocks.map((b) => b.text ?? "").join("\n");
}

/** The pid of the daemon serving `s`: the java process whose environment names its state folder. */
export function daemonPid(s: Splice): number | null {
  for (const d of readdirSync("/proc")) {
    if (!/^\d+$/.test(d)) continue;
    try {
      const env = readFileSync(`/proc/${d}/environ`, "latin1").split("\0");
      if (!env.includes(`SPLICE_STATE_DIR=${s.state}`)) continue;
      const cmd = readFileSync(`/proc/${d}/cmdline`, "latin1").split("\0");
      if (cmd.some((a) => a.endsWith(".jar")) && cmd.includes("daemon")) return Number(d);
    } catch {}
  }
  return null;
}

/** Whether process `pid` is running (not gone, not a zombie). */
export function running(pid: number): boolean {
  try {
    const stat = readFileSync(`/proc/${pid}/stat`, "utf8");
    const state = stat.slice(stat.lastIndexOf(")") + 2, stat.lastIndexOf(")") + 3);
    return state !== "Z" && state !== "X";
  } catch {
    return false;
  }
}

/** The person's own Claude settings, in the folder Claude Code reads them from, written before a launch reads them. */
export function personSettings(s: Splice, value: Record<string, unknown>): void {
  mkdirSync(join(s.home, ".claude"), { recursive: true });
  writeFileSync(join(s.home, ".claude", "settings.json"), `${JSON.stringify(value, null, 2)}\n`);
}

/** The headers a run sent whose value names `session`. */
export function naming(r: Run, session: string): [string, string][] {
  return Object.entries(r.request?.headers ?? {}).filter(([, v]) => v.includes(session));
}

/** `messages` sent as Claude Code sends a turn on a head, with headers of the caller's own beside them. */
export async function sendWith(l: { sets: Record<string, string>; settings: Record<string, unknown>; cwd: string; env: Record<string, string> }, session: string, messages: Message[], extra: Record<string, string>): Promise<{ status: number; body: string }> {
  const helper = String(l.settings.apiKeyHelper ?? "");
  const key = spawnSync("bash", ["-c", helper], { cwd: l.cwd, env: l.env, encoding: "utf8" }).stdout.trim();
  const headers: Record<string, string> = {
    "content-type": "application/json",
    "anthropic-version": "2023-06-01",
    "user-agent": "claude-cli/2.1.326 (external, cli)",
    "x-app": "cli",
    "x-api-key": key,
    "x-claude-code-session-id": session,
    ...extra,
  };
  const user = JSON.stringify({ device_id: "c0ffee".repeat(11).slice(0, 64), account_uuid: "", session_id: session });
  const tools = [{ name: "Read", description: "Read a file.", input_schema: { type: "object", properties: { file_path: { type: "string" } }, required: ["file_path"] } }];
  const r = await fetch(`${l.sets.ANTHROPIC_BASE_URL}/v1/messages`, {
    method: "POST",
    headers,
    body: JSON.stringify({ model: l.sets.ANTHROPIC_MODEL, max_tokens: 1024, stream: true, metadata: { user_id: user }, tools, messages }),
  });
  return { status: r.status, body: await r.text() };
}
