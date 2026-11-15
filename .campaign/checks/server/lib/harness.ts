// The server-mode checks' harness: it builds splice from this checkout, boots a real daemon in a home of its own
// (its own config, state, ports and tmux socket, so the machine's own splice is never touched), runs the CLI against
// it, and sends turns the way Claude Code does. Every check boots what it needs and stops it when it ends.

import { spawn, spawnSync, type ChildProcess } from "node:child_process";
import { chmodSync, copyFileSync, existsSync, mkdirSync, mkdtempSync, openSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";

export const ROOT = spawnSync("git", ["rev-parse", "--show-toplevel"], { cwd: import.meta.dir, encoding: "utf8" }).stdout.trim();
const JAR = join(ROOT, "app/build/libs/app-all.jar");
let built = false;

/** The fat jar built from this checkout. A build that fails fails the check that asked for it. */
export function jar(): string {
  if (!built && process.env.SPLICE_CHECK_NO_BUILD !== "1") {
    const r = spawnSync("./gradlew", [":app:shadowJar", "--quiet"], { cwd: ROOT, encoding: "utf8", timeout: 1_800_000 });
    if (r.status !== 0) throw new Error(`the build failed:\n${`${r.stdout}${r.stderr}`.slice(-4000)}`);
  }
  built = true;
  if (!existsSync(JAR)) throw new Error(`no jar at ${JAR}`);
  return JAR;
}

export async function freePort(): Promise<number> {
  const s = Bun.listen({ hostname: "127.0.0.1", port: 0, socket: { data() {} } });
  const p = s.port;
  s.stop(true);
  return p;
}

export interface Ran {
  code: number;
  out: string;
  err: string;
}

/** One provider and one head on an OpenAI-compatible upstream, served under `command`. */
export function mockHead(o: { key: string; command: string; port: number; url: string; model: string; window?: number }): string {
  return `
[providers.${o.key}]
dialect = "openai-chat"
base_url = "${o.url}"
auth = { kind = "api-key", env = "MOCK_UPSTREAM_KEY" }

[[providers.${o.key}.models]]
id = "${o.model}"
label = "${o.model}"
context_window = ${o.window ?? 131072}
rates = { input = 2.0, cache_read = 0.5, output = 8.0 }

[heads.${o.key}]
provider = "${o.key}"
port = ${o.port}
discovery_prefix = "claude-${o.key}--"
pinned_model = "${o.model}"
[heads.${o.key}.claude]
command = "${o.command}"
`;
}

const FOREIGN = /^(SPLICE_|CLAUDEX_|ANTHROPIC_|CLAUDE_|OTEL_|LISTEN_|TMUX|XDG_|BROWSER$)/;

// Daemons outlive a test process that is killed or times out before its afterAll runs, and keep writing into a
// home nobody will remove. Whatever is still live when this process exits is killed and removed here.
const LIVE = new Set<Splice>();
const reapAll = () => {
  for (const s of LIVE) s.reapNow();
  LIVE.clear();
};
process.on("exit", reapAll);
for (const sig of ["SIGINT", "SIGTERM", "SIGHUP"] as const) {
  process.on(sig, () => {
    reapAll();
    process.exit(128 + ({ SIGHUP: 1, SIGINT: 2, SIGTERM: 15 } as const)[sig]);
  });
}

export class Splice {
  readonly home: string;
  readonly state: string;
  readonly config: string;
  readonly log: string;
  readonly controlPort: number;
  private readonly extraEnv: Record<string, string>;
  private proc: ChildProcess | null = null;

  private constructor(home: string, controlPort: number, extraEnv: Record<string, string>) {
    this.home = home;
    this.state = join(home, ".splice", "state");
    this.config = join(home, ".config", "splice", "splice.toml");
    this.log = join(home, "daemon.log");
    this.controlPort = controlPort;
    this.extraEnv = extraEnv;
  }

  /** A daemon home with this config (no [daemon] table: the harness writes that one). Not started. */
  static async create(toml: string, env: Record<string, string> = {}): Promise<Splice> {
    const home = mkdtempSync(join(tmpdir(), "splice-server-check-"));
    const s = new Splice(home, await freePort(), env);
    LIVE.add(s);
    mkdirSync(s.state, { recursive: true });
    mkdirSync(join(home, ".config", "splice"), { recursive: true });
    mkdirSync(join(home, "tmux"), { recursive: true });
    // The launch shim, where install.sh puts it: `splice remote add` links a command to it.
    const shim = join(home, ".local", "share", "splice", "splice-launch");
    mkdirSync(dirname(shim), { recursive: true });
    copyFileSync(join(ROOT, "app/src/main/dist/bin/splice-launch"), shim);
    chmodSync(shim, 0o755);
    s.writeConfig(toml);
    return s;
  }

  /** Create and start. */
  static async boot(toml: string, env: Record<string, string> = {}): Promise<Splice> {
    const s = await Splice.create(toml, env);
    try {
      await s.start();
    } catch (e) {
      await s.dispose();
      throw e;
    }
    return s;
  }

  writeConfig(toml: string): void {
    writeFileSync(this.config, `[daemon]\ncontrol_port = ${this.controlPort}\nstate_dir = "${this.state}"\n\n${toml}\n`);
  }

  env(): Record<string, string> {
    const base = Object.fromEntries(Object.entries(process.env).filter(([k, v]) => v !== undefined && !FOREIGN.test(k))) as Record<string, string>;
    return {
      ...base,
      HOME: this.home,
      SPLICE_CONFIG: this.config,
      SPLICE_STATE_DIR: this.state,
      XDG_CONFIG_HOME: join(this.home, ".config"),
      XDG_DATA_HOME: join(this.home, ".local", "share"),
      XDG_STATE_HOME: join(this.home, ".local", "state"),
      XDG_CACHE_HOME: join(this.home, ".cache"),
      TMUX_TMPDIR: join(this.home, "tmux"),
      MOCK_UPSTREAM_KEY: "mock-upstream-key",
      ...this.extraEnv,
    };
  }

  private java(args: string[]): string[] {
    return ["-Xmx1g", "-Dsplice.noSystemBrowser=1", `-Duser.home=${this.home}`, "-jar", jar(), ...args];
  }

  async start(): Promise<void> {
    rmSync(join(this.state, "daemon.lock"), { force: true });
    const fd = openSync(this.log, "a");
    const from = existsSync(this.log) ? statSync(this.log).size : 0;
    this.proc = spawn("java", this.java(["daemon"]), { cwd: this.home, env: this.env(), stdio: ["ignore", fd, fd], detached: true });
    for (let i = 0; i < 360; i++) {
      if (this.proc.exitCode !== null) throw new Error(`the daemon exited ${this.proc.exitCode}:\n${this.logTail()}`);
      if (readFileSync(this.log, "utf8").slice(from).includes("[daemon] up")) return;
      await Bun.sleep(500);
    }
    throw new Error(`the daemon did not come up in 180 s:\n${this.logTail()}`);
  }

  async stop(): Promise<void> {
    const p = this.proc;
    this.proc = null;
    if (p && p.exitCode === null && p.pid !== undefined) {
      const group = (sig: NodeJS.Signals) => {
        try {
          process.kill(-p.pid!, sig);
        } catch {}
      };
      group("SIGTERM");
      for (let i = 0; i < 40 && p.exitCode === null; i++) await Bun.sleep(250);
      if (p.exitCode === null) group("SIGKILL");
    }
    // A command that restarts the daemon (`splice remote add`, `splice restart`) leaves one this harness did not
    // start: every process whose environment names this home's state is this home's, and is stopped too.
    for (const sig of ["SIGTERM", "SIGKILL"] as const) {
      this.signalAll(sig);
      for (let i = 0; i < 20 && this.pids().length; i++) await Bun.sleep(250);
    }
  }

  /** Every process whose environment names this home's state directory, this test process aside. */
  private pids(): number[] {
    return readdirSync("/proc")
      .filter((d) => {
        if (!/^\d+$/.test(d) || Number(d) === process.pid) return false;
        try {
          return readFileSync(`/proc/${d}/environ`, "latin1").split("\0").includes(`SPLICE_STATE_DIR=${this.state}`);
        } catch {
          return false;
        }
      })
      .map(Number);
  }

  private signalAll(sig: NodeJS.Signals): void {
    for (const pid of this.pids()) {
      try {
        process.kill(pid, sig);
      } catch {}
    }
  }

  /** At once and without waiting, for a test process that is exiting: kill what runs here and remove the home. */
  reapNow(): void {
    this.signalAll("SIGKILL");
    if (process.env.SPLICE_CHECK_KEEP !== "1") rmSync(this.home, { recursive: true, force: true });
  }

  async restart(): Promise<void> {
    await this.stop();
    await this.start();
  }

  /** Stop and remove the home, unless SPLICE_CHECK_KEEP=1. */
  async dispose(): Promise<void> {
    LIVE.delete(this);
    await this.stop();
    if (process.env.SPLICE_CHECK_KEEP === "1") console.error(`kept ${this.home}`);
    else rmSync(this.home, { recursive: true, force: true });
  }

  logTail(n = 4000): string {
    return existsSync(this.log) ? readFileSync(this.log, "utf8").slice(-n) : "(no log)";
  }

  /** `splice <args>` against this daemon. */
  cli(args: string[], input?: string, env: Record<string, string> = {}): Ran {
    const r = spawnSync("java", this.java(args), { cwd: this.home, env: { ...this.env(), ...env }, encoding: "utf8", input, timeout: 120_000 });
    return { code: r.status ?? -1, out: r.stdout ?? "", err: r.stderr ?? "" };
  }

  /** `splice <args>` left running, for commands that wait (a sign-in). */
  spawnCli(args: string[], env: Record<string, string> = {}): ChildProcess {
    return spawn("java", this.java(args), { cwd: this.home, env: { ...this.env(), ...env }, stdio: ["pipe", "pipe", "pipe"] });
  }

  mgmtKey(): string {
    return readFileSync(join(this.state, "mgmt-key"), "utf8").trim();
  }

  /** The control plane, with the management key. */
  api(path: string, init: RequestInit = {}): Promise<Response> {
    const headers = new Headers(init.headers);
    headers.set("authorization", `Bearer ${this.mgmtKey()}`);
    if (init.body && !headers.has("content-type")) headers.set("content-type", "application/json");
    return fetch(`http://127.0.0.1:${this.controlPort}${path}`, { ...init, headers });
  }

  /** Every file in this daemon's home, its log included, with its bytes, for checks that nothing holds a secret. */
  files(): { path: string; bytes: Buffer }[] {
    const out: { path: string; bytes: Buffer }[] = [];
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
        else if (st.isFile()) out.push({ path: p, bytes: readFileSync(p) });
      }
    };
    walk(this.home);
    return out;
  }
}

export interface SseEvent {
  event: string;
  data: Record<string, unknown> | null;
}

export interface Turn {
  status: number;
  headers: Headers;
  body: string;
  events: SseEvent[];
  /** The reply's text, from text deltas or a JSON reply's text blocks. */
  reply: string;
  /** The error envelope's type and message, when the answer is one. */
  error: { type: string; message: string } | null;
  /** Milliseconds from sending to the first byte of the body (or the headers, for an empty body). */
  firstByteMs: number;
  /** The longest wait between two reads of the body, in milliseconds. */
  longestGapMs: number;
  /** Milliseconds from sending to the end of the body. */
  totalMs: number;
  /** Each read of the body: when it arrived (ms from sending) and its text. */
  arrivals: { ms: number; text: string }[];
}

export interface TurnOptions {
  key?: string;
  /** Send the key as `Authorization: Bearer` instead of `x-api-key`. */
  bearer?: boolean;
  stream?: boolean;
  model?: string;
  content?: string;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  insecure?: boolean;
}

function sse(body: string): SseEvent[] {
  const out: SseEvent[] = [];
  for (const block of body.split(/\r?\n\r?\n/)) {
    let event = "";
    const data: string[] = [];
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith("event:")) event = line.slice(6).trim();
      else if (line.startsWith("data:")) data.push(line.slice(5).trim());
    }
    if (!event && !data.length) continue;
    let parsed: Record<string, unknown> | null = null;
    try {
      parsed = JSON.parse(data.join("\n")) as Record<string, unknown>;
    } catch {}
    out.push({ event: event || String(parsed?.type ?? ""), data: parsed });
  }
  return out;
}

/** One Anthropic Messages turn, the way Claude Code sends it, read to its end. */
export async function turn(url: string, o: TurnOptions = {}): Promise<Turn> {
  const headers: Record<string, string> = { "content-type": "application/json", "anthropic-version": "2023-06-01", ...o.headers };
  if (o.key !== undefined) {
    if (o.bearer) headers.authorization = `Bearer ${o.key}`;
    else headers["x-api-key"] = o.key;
  }
  const body = JSON.stringify({
    model: o.model ?? "mock-model",
    max_tokens: 256,
    stream: o.stream ?? true,
    messages: [{ role: "user", content: o.content ?? "Say hello." }],
  });
  const t0 = performance.now();
  const res = await fetch(url, { method: "POST", headers, body, signal: o.signal, ...(o.insecure ? { tls: { rejectUnauthorized: false } } : {}) } as RequestInit);
  let firstByteMs = performance.now() - t0;
  let longestGapMs = 0;
  let text = "";
  const arrivals: { ms: number; text: string }[] = [];
  if (res.body) {
    const reader = res.body.getReader();
    const dec = new TextDecoder();
    let last = performance.now();
    let first = true;
    for (;;) {
      const { done, value } = await reader.read();
      const now = performance.now();
      if (done) break;
      if (first) {
        firstByteMs = now - t0;
        first = false;
      } else longestGapMs = Math.max(longestGapMs, now - last);
      last = now;
      const piece = dec.decode(value, { stream: true });
      arrivals.push({ ms: now - t0, text: piece });
      text += piece;
    }
  }
  const totalMs = performance.now() - t0;
  const events = (res.headers.get("content-type") ?? "").includes("event-stream") ? sse(text) : [];
  let reply = "";
  let error: Turn["error"] = null;
  if (events.length) {
    for (const e of events) {
      const d = e.data as { type?: string; delta?: { type?: string; text?: string }; error?: { type?: string; message?: string } } | null;
      if (d?.type === "content_block_delta" && d.delta?.type === "text_delta") reply += d.delta.text ?? "";
      if (d?.type === "error" && d.error) error = { type: String(d.error.type), message: String(d.error.message) };
    }
  } else {
    try {
      const j = JSON.parse(text) as { type?: string; content?: { type: string; text?: string }[]; error?: { type?: string; message?: string } };
      if (j.type === "error" && j.error) error = { type: String(j.error.type), message: String(j.error.message) };
      for (const c of j.content ?? []) if (c.type === "text") reply += c.text ?? "";
    } catch {}
  }
  return { status: res.status, headers: res.headers, body: text, events, reply, error, firstByteMs, longestGapMs, totalMs, arrivals };
}

/** Wait until `f` is true, polling, or fail with `what`. */
export async function until(what: string, f: () => boolean | Promise<boolean>, ms = 15_000): Promise<void> {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (await f()) return;
    await Bun.sleep(200);
  }
  throw new Error(`timed out waiting for ${what}`);
}

/** Parse the last JSON object a command printed on stdout. */
export function json<T = Record<string, unknown>>(r: Ran): T {
  const s = r.out.trim();
  const start = s.indexOf("{");
  if (start < 0) throw new Error(`no JSON in the output (exit ${r.code}):\n${r.out}\n${r.err}`);
  return JSON.parse(s.slice(start)) as T;
}
