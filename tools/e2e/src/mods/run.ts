import assert from "node:assert/strict";
import { chmodSync, closeSync, copyFileSync, existsSync, mkdirSync, openSync, readFileSync, symlinkSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { Database } from "bun:sqlite";
import { homeEnv, makeHome, writeText } from "../../../console-own/home.ts";
import { Daemon, providers, toml } from "../../../console-own/splice.ts";
import type { EventName, ModManifest } from "../../../../mods/contract/index.ts";
import type { Answer } from "./fake-claude.ts";

const repo = join(import.meta.dir, "../../../..");
const names = ["recorder", "hang", "throw", "crash", "flood", "orphan", "stderr-orphan", "unserved"] as const;
type Fixture = typeof names[number];
type Env = Record<string, string>;
type Session = { session_id: string; labels: Env; availability: string };
type Health = { ok: boolean; readyHeads: number; pid: number; launcher?: string };
type ProcessRow = { pid: number; parent: number; group: number; line: string; mod: boolean };
export type ClientReport = { hooks: number; answers: Answer[]; model: string };
export type Event = {
  name: EventName;
  payload: { session?: string; turn?: string; seenBefore?: boolean; labels?: Env; seq?: number; stopReason?: string | null; outcome?: string; model?: string; cwd?: string };
};
export type Mod = {
  name: string; state: string; pid?: number; failed: boolean; why?: string;
  dropped: number; droppedByEvent: Record<string, number>; droppedLogLines: number;
  droppedTransport: number; restartsInWindow: number; lastError?: string; served?: string[]; refused?: string[]; declaredNotServed?: string[];
};

export const BASIC = "Hello from the chat mock. 1, 2, 3 END";
export const PREFIX = "splice-mod-e2e-";
export const MANAGEMENT = "splice-mod-e2e-fake-management";
export const PROVIDER = "mock-chat-key";
export const LABEL_PROBE = "xai-" + "fakeLabelZ".repeat(8);
export const CWD_PROBE = "AIza" + "fakePath".repeat(4) + "fakeXYZ";
export const MODEL_PROBE = "xai-" + "fakeModelZ".repeat(8);
export const SECRET_HEADERS = {
  authorization: "Bearer splice-mod-e2e-fake-authorization",
  "x-api-key": "splice-mod-e2e-fake-api-key",
  cookie: "login=splice-mod-e2e-fake-cookie",
};

export function step(n: number, title: string): void {
  console.log(`\n${n}. ${title}`);
}

export async function until<T>(what: string, read: () => T | undefined | Promise<T | undefined>, ms = 60_000): Promise<T> {
  const end = performance.now() + ms;
  while (performance.now() < end) {
    const value = await read();
    if (value !== undefined) return value;
    await Bun.sleep(100);
  }
  throw new Error(`did not observe ${what} within ${ms / 1_000}s`);
}

function existing(path: string): string {
  return existsSync(path) ? readFileSync(path, "utf8") : "";
}

function sig(pid: number, signal: NodeJS.Signals): void {
  try { process.kill(pid, signal); } catch (e) {
    if ((e as NodeJS.ErrnoException).code !== "ESRCH") throw e;
  }
}

async function oneLine(stream: ReadableStream<Uint8Array>): Promise<string> {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let line = "";
  try {
    for (;;) {
      const next = await reader.read();
      if (next.done) return line;
      line += decoder.decode(next.value, { stream: true });
      const nl = line.indexOf("\n");
      if (nl >= 0) return line.slice(0, nl);
    }
  } finally {
    reader.releaseLock();
  }
}

export class ModRun {
  readonly home = makeHome(PREFIX);
  readonly logs = join(this.home.root, ".splice", "logs");
  readonly gate = join(this.home.root, "provider-gate");
  readonly daemon: Daemon;
  readonly env: Env;
  readonly splice = join(this.home.bin, "splice");
  private ports: number[] = [];
  private stopChild: (() => void) | undefined;
  private stopExit: Promise<number> | undefined;
  private children = new Map<number, { kill(): void; exited: Promise<number> }>();
  private closing?: Promise<void>;

  constructor(port: number) {
    this.daemon = new Daemon(this.home, port);
    // Reuse scratch paths, not the caller's open-ended environment (e.g. credential stores, Java agents, proxies).
    const shared = homeEnv(this.home);
    this.env = {
      ...Object.fromEntries(Object.entries(shared).filter(([key]) =>
        /^(HOME|LANG|LC_ALL|LC_CTYPE|LOGNAME|USER|JAVA_HOME|XDG_(CONFIG|DATA|STATE|CACHE)_HOME|SPLICE_(CONFIG|STATE_DIR|SUPERVISOR_UNIT))$/.test(key))),
      PATH: [this.home.bin, join(process.execPath, ".."), "/usr/local/bin", "/usr/bin", "/bin"].join(":"),
      TMPDIR: this.home.root,
      SPLICE_JAR: this.home.jar,
      SPLICE_JAVA: Bun.which("java") ?? "java",
      SPLICE_JVM_OPTS: "-Xmx1g -Dsplice.noSystemBrowser=1",
      SPLICE_CLI_JVM_OPTS: "-Xmx128m",
      SPLICE_BUN: process.execPath,
      SPLICE_CONTROL_PORT: String(port),
      CONSOLE_OWN_STANDIN_KEY: PROVIDER,
      NO_PROXY: "127.0.0.1,localhost",
    };
  }

  private own<T extends { pid: number; kill(): void; exited: Promise<number> }>(child: T): T {
    this.children.set(child.pid, child);
    void child.exited.then(() => this.children.delete(child.pid), () => this.children.delete(child.pid));
    return child;
  }

  async prepare(artifact: string): Promise<void> {
    const h = this.home;
    console.log(`Scratch home: ${h.root}; loopback ports only; no login or real Claude Code runs.`);
    copyFileSync(artifact, h.jar);
    const shim = join(h.bin, "splice-launch");
    copyFileSync(join(repo, "app/src/main/dist/bin/splice-launch"), shim);
    chmodSync(shim, 0o700);
    const heads = await providers();
    heads[1]!.model = MODEL_PROBE;
    this.ports = heads.map(p => p.port);
    assert.equal(new Set([this.daemon.port, ...this.ports]).size, heads.length + 1, "scratch ports must be distinct");
    for (const command of ["splice", ...heads.map(p => p.command)]) symlinkSync(shim, join(h.bin, command));
    const fake = join(h.bin, "claude");
    copyFileSync(join(import.meta.dir, "fake-claude.ts"), join(h.bin, "fake-claude.ts"));
    writeText(fake, `#!/bin/sh\nexec "${process.execPath}" "${h.bin}/fake-claude.ts" "$@"\n`);
    chmodSync(fake, 0o700);
    writeFileSync(join(h.state, "mgmt-key"), MANAGEMENT, { mode: 0o600 });
    writeFileSync(join(h.state, "terminal-key"), SECRET_HEADERS.authorization.slice("Bearer ".length), { mode: 0o600 });
    writeText(this.gate, "");
    const provider = this.own(Bun.spawn([process.execPath, join(repo, "tools/e2e/docker/mock_chat.ts"), "0", h.root + "/"], {
      cwd: h.root, env: { ...this.env, MOCK_CHAT_GATE: this.gate, MOCK_CHAT_EXTRA_MODEL: MODEL_PROBE }, stdout: "pipe", stderr: "inherit",
    }));
    const ready = await oneLine(provider.stdout);
    const { port } = JSON.parse(ready) as { port: number };
    assert.ok(port > 0, "mock_chat did not report a listening port");
    console.log(`Started mock_chat (OpenAI chat stand-in) on loopback port ${port}.`);
    writeText(h.config, toml(h, this.daemon.port, `http://127.0.0.1:${port}`, heads));
    console.log("Client: fake claude exec'd by the real launcher; sends session ID, recipe owner and custom headers, and runs matching SessionStart hooks.");
  }

  async health(previous?: number): Promise<Health> {
    return until("healthy scratch daemon ready on both heads", async () => {
      try {
        const res = await fetch(this.daemon.url + "/health", { keepalive: false });
        if (!res.ok) return;
        const current = await res.json() as Health;
        if (current.ok && current.readyHeads === this.ports.length && current.pid && current.pid !== previous) return current;
      } catch (e) {
        if (!(e instanceof TypeError)) throw e;
      }
    });
  }

  async start(supervised = false): Promise<{ pid: number; supervisor: number }> {
    const argv = supervised ? [
      "systemd-socket-activate", "--fdname=control:head-acme:head-globex",
      ...Object.keys(this.env).map(key => `--setenv=${key}`),
      ...[this.daemon.port, ...this.ports].flatMap(port => ["-l", `127.0.0.1:${port}`]),
      this.splice, "daemon",
    ] : [this.splice, "daemon"];
    console.log(`$ ${argv.join(" ")}  # scratch HOME/state/config`);
    const fd = openSync(this.home.log, "a", 0o600);
    try {
      const child = this.own(Bun.spawn(argv, { env: this.env, cwd: this.home.work, stdout: fd, stderr: fd }));
      this.stopChild = () => child.kill("SIGTERM");
      this.stopExit = child.exited;
      const health = await Promise.race([
        this.health(),
        child.exited.then(code => { throw new Error(`daemon exited ${code}`); }),
      ]);
      assert.equal(Boolean(health.launcher), supervised, "wrong daemon supervision mode");
      console.log(`GET /health: pid=${health.pid}, heads ready=${health.readyHeads}`);
      return { pid: health.pid, supervisor: child.pid };
    } finally {
      closeSync(fd);
    }
  }

  async stop(supervised = false): Promise<void> {
    assert.ok(this.stopExit && this.stopChild, "scratch daemon was never started");
    if (supervised) {
      console.log("$ kill -TERM <scratch launcher supervisor>");
      this.stopChild();
    } else {
      console.log("$ POST /api/daemon/shutdown");
      const res = await this.daemon.api("/api/daemon/shutdown", { method: "POST" });
      assert.equal(res.status, 202, await res.text());
    }
    const code = await this.stopExit;
    assert.equal(code, supervised ? 143 : 0, "scratch daemon stop exit code");
    await this.noMods();
  }

  async cli(...args: string[]): Promise<{ code: number; out: string }> {
    console.log(`$ splice ${args.join(" ")}`);
    const p = this.own(Bun.spawn([this.splice, ...args], {
      cwd: this.home.work, env: this.env, stdout: "pipe", stderr: "pipe",
    }));
    const [stdout, stderr, code] = await Promise.all([new Response(p.stdout).text(), new Response(p.stderr).text(), p.exited]);
    const out = (stdout + stderr).trim();
    if (out) console.log(out);
    return { code, out };
  }

  async command(...args: string[]): Promise<string> {
    const { code, out } = await this.cli(...args);
    assert.equal(code, 0, out);
    return out;
  }

  async install(name: string, fixture: Fixture = "recorder", extra: Partial<ModManifest> = {}): Promise<Mod> {
    const source = join(this.home.root, "fixtures", name);
    const manifest = {
      name, version: "1.0.0", description: "Scratch runner evidence.", surfaces: ["console"],
      capabilities: ["watch-sessions", "watch-turns"],
      ...extra,
    } satisfies ModManifest;
    writeText(join(source, ".splice-mod", "mod.json"), JSON.stringify(manifest));
    writeText(join(source, "hooks", "hooks.json"), JSON.stringify({ modules: ["./register.ts"] }));
    copyFileSync(join(repo, "tools/e2e/fixtures/mods", fixture + ".ts"), join(source, "hooks", "register.ts"));
    await this.command("mod", "install", source);
    return this.running(name);
  }

  private async get<T>(path: string): Promise<T> {
    const response = await this.daemon.api(path);
    assert.ok(response.ok, `GET ${path}: ${response.status}`);
    return await response.json() as T;
  }

  async mods(): Promise<Mod[]> {
    return (await this.get<{ mods: Mod[] }>("/api/mods")).mods;
  }

  async handoverDropped(): Promise<number> {
    return (await this.get<{ handoverDropped?: number }>("/api/mods")).handoverDropped ?? 0;
  }

  async sessions(): Promise<Session[]> {
    return (await this.get<{ sessions: Session[] }>("/api/sessions")).sessions;
  }

  async mod(name: string): Promise<Mod> {
    const row = (await this.mods()).find(mod => mod.name === name);
    assert.ok(row, `GET /api/mods did not list ${name}`);
    return row;
  }

  running(name: string): Promise<Mod & { pid: number }> {
    return until(`${name} running`, async () => {
      const row = await this.mod(name);
      if (row.state === "failed") throw new Error(`${name} failed: ${row.why}`);
      if (row.state === "running" && row.pid) return { ...row, pid: row.pid };
    });
  }

  client(id: string, prompts: string[], options: {
    env?: Env; privateEnv?: Env; cwd?: string; command?: string; interactive?: boolean;
    resume?: boolean; quiet?: boolean; headers?: Env; waitForHook?: boolean;
  } = {}) {
    const command = options.command ?? "claude-acme";
    const args = [join(this.home.bin, command), ...options.interactive ? [] : ["-p"],
      options.resume ? "--resume" : "--session-id", id];
    if (!options.quiet) console.log(`$ ${Object.entries(options.env ?? {}).map(([k, v]) => `${k}=${JSON.stringify(v)} `).join("")}${command} ${args.slice(1).join(" ")}  # ${prompts.length} streamed turn(s)`);
    if (options.cwd) mkdirSync(options.cwd, { recursive: true });
    const p = this.own(Bun.spawn(args, {
      cwd: options.cwd ?? this.home.work, env: { ...this.env, ...options.env, ...options.privateEnv,
        MOD_E2E_PROMPTS: JSON.stringify(prompts),
        MOD_E2E_WAIT_FOR_HOOK: options.waitForHook ? "1" : "0",
        MOD_E2E_HEADERS: JSON.stringify(options.headers ?? {}),
      },
      stdin: "pipe", stdout: "pipe", stderr: "pipe",
    }));
    const stderr = new Response(p.stderr).text();
    const report = oneLine(p.stdout).then(async line => {
      if (!line) throw new Error(`fake Claude client ${id} exited ${await p.exited}: ${await stderr}`);
      const value = JSON.parse(line) as ClientReport;
      assert.equal(value.answers.length, prompts.length);
      if (!options.interactive) {
        p.stdin.end();
        assert.equal(await p.exited, 0, await stderr);
      }
      if (!options.quiet) console.log(`  fake client pid=${p.pid}, SessionStart commands=${value.hooks}, completed=${value.answers.length}`);
      return value;
    });
    // The caller of a held turn can check the recorder before awaiting its answer.
    void report.catch(() => {});
    const release = async () => {
      p.stdin.write("turn\n");
      await p.stdin.flush();
    };
    return { pid: p.pid, report, release, close: async () => {
      if (p.exitCode === null) p.stdin.end();
      assert.equal(await p.exited, 0, await stderr);
    } };
  }

  async recordThrough(session: string, predicate: (events: Event[]) => boolean, name = "recorder"): Promise<Event[]> {
    return until(`${name} recorded ${session}`, () => {
      const events = this.events(name).filter(e => e.payload.session === session);
      if (predicate(events)) return events;
    });
  }

  data(name: string): string {
    return join(this.home.state, "mods", "data", name);
  }

  dump(name: string): { key: string; value: string }[] {
    const file = join(this.data(name), "store.sqlite");
    if (!existsSync(file)) return [];
    const db = new Database(file, { readonly: true });
    try {
      db.exec("PRAGMA busy_timeout = 2000");
      return db.query<{ key: string; value: string }, []>("SELECT * FROM values_by_key ORDER BY key").all();
    } finally {
      db.close();
    }
  }

  events(name = "recorder"): Event[] {
    return this.dump(name).filter(row => row.key.startsWith("event/")).map(row => JSON.parse(JSON.parse(row.value) as string) as Event);
  }

  processes(): ProcessRow[] {
    const ps = Bun.spawnSync(["ps", "-ww", "-eo", "pid=,ppid=,pgid=,stat=,args="]);
    assert.equal(ps.exitCode, 0, ps.stderr.toString());
    return ps.stdout.toString().split("\n").flatMap(line => {
      const match = /^\s*(\d+)\s+(\d+)\s+(\d+)\s+(\S+)\s+(.*)$/.exec(line);
      if (!match || match[4]!.startsWith("Z") || !match[5]!.includes(this.home.root + "/")) return [];
      return [{
        pid: Number(match[1]), parent: Number(match[2]), group: Number(match[3]),
        line: line.trim(), mod: match[5]!.includes(this.home.state + "/mods/host/host.ts") &&
          !match[5]!.includes(this.home.state + "/mods/host/guardian.ts"),
      }];
    });
  }

  ps(title: string, expected?: string[]): ProcessRow[] {
    console.log(`$ ps -ww -eo pid,ppid,pgid,stat,args  # ${title}, scratch processes only`);
    const rows = this.processes();
    if (rows.length) for (const { line } of rows) console.log(`  ${line}`);
    else console.log("  none left");
    if (expected) {
      const running = rows.filter(p => p.mod);
      assert.equal(running.length, expected.length, `expected only these mod processes: ${expected.join(", ")}`);
    }
    return rows;
  }

  noMods(): Promise<true> {
    return until("no scratch mod process left", () => this.processes().some(p => p.mod) ? undefined : true);
  }

  log(name: string): string {
    return existing(join(this.logs, "mods", name + ".log"));
  }

  async killedDaemon(pid: number): Promise<void> {
    console.log(`$ kill -KILL ${pid}  # scratch daemon, not its mod processes`);
    assert.ok(this.stopExit);
    this.killOwned(pid, "SIGKILL");
    assert.equal(await this.stopExit, 137, "scratch daemon SIGKILL exit status");
  }

  bootLog(): string {
    return existing(this.home.log);
  }

  killOwned(pid: number, signal: NodeJS.Signals): void {
    assert.ok(this.processes().some(row => row.pid === pid), `pid ${pid} does not belong to the scratch run`);
    sig(pid, signal);
  }

  cleanup(): Promise<void> {
    return this.closing ??= this.tearDown();
  }

  private async tearDown(): Promise<void> {
    console.log("\nTeardown: stop remaining scratch children and verify with ps.");
    let rows = this.processes();
    for (const { pid } of rows) sig(pid, "SIGTERM");
    for (const child of this.children.values()) child.kill();
    const started = performance.now();
    while ((rows = this.processes()).length && performance.now() - started < 5_000) await Bun.sleep(100);
    for (const { pid } of rows) sig(pid, "SIGKILL");
    await until("all tagged processes gone", () => this.processes().length ? undefined : true, 10_000);
    assert.equal(this.ps(PREFIX + " cleanup").length, 0);
    if (Bun.which("trash")) {
      const result = Bun.spawnSync(["trash", this.home.root]);
      assert.equal(result.exitCode, 0, result.stderr.toString());
    } else {
      console.log("  trash unavailable; scratch files left to the /tmp sweep.");
    }
  }
}
