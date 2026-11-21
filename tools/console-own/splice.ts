// The Splice a console-own home runs: its made-up configuration (two providers on the local stand-in upstream, one
// model each, made-up prices, a made-up key), the daemon built from this checkout, and calls to its control plane with
// its own management key.
import { spawn, spawnSync } from "node:child_process";
import { copyFileSync, existsSync, openSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { homeEnv, LIVE_CONTROL_PORT, writeText, type Home } from "./home.ts";

/** The stand-in's own key (tools/e2e/docker/mock_chat.ts takes only this one), given to the daemon in its env. */
const STANDIN_KEY = "mock-chat-key";
const KEY_ENV = "CONSOLE_OWN_STANDIN_KEY";

export interface Provider {
  key: string;
  command: string;
  model: string;
  label: string;
  port: number;
  rates: { input: number; cache_read: number; output: number };
}

export async function freePort(): Promise<number> {
  for (;;) {
    const s = Bun.listen({ hostname: "127.0.0.1", port: 0, socket: { data() {} } });
    const p = s.port;
    s.stop(true);
    if (p !== LIVE_CONTROL_PORT) return p;
  }
}

/** Two made-up providers, each with its own command and model, on the stand-in at [standin]. */
export async function providers(): Promise<Provider[]> {
  return [
    { key: "acme", command: "claude-acme", model: "mock-chat", label: "Acme Chat", port: await freePort(), rates: { input: 2, cache_read: 0.5, output: 8 } },
    { key: "globex", command: "claude-globex", model: "mock-chat-2", label: "Globex Chat 2", port: await freePort(), rates: { input: 0.6, cache_read: 0.15, output: 2.4 } },
  ];
}

export function toml(h: Home, controlPort: number, standin: string, list: Provider[]): string {
  const heads = list.map((p) => `
[providers.${p.key}]
dialect = "openai-chat"
base_url = "${standin}/v1"
auth = { kind = "api-key", env = "${KEY_ENV}" }

[[providers.${p.key}.models]]
id = "${p.model}"
label = "${p.label}"
context_window = 131072
rates = { input = ${p.rates.input.toFixed(2)}, cache_read = ${p.rates.cache_read.toFixed(2)}, output = ${p.rates.output.toFixed(2)} }

[heads.${p.key}]
provider = "${p.key}"
port = ${p.port}
discovery_prefix = "claude-${p.key}--"
pinned_model = "${p.model}"
[heads.${p.key}.claude]
command = "${p.command}"
`).join("");
  return `[daemon]\ncontrol_port = ${controlPort}\nstate_dir = "${h.state}"\n${heads}`;
}

/** The stand-in upstream: tools/e2e's OpenAI-compatible chat mock, on a port of its own. Answers its address. */
export async function standIn(h: Home): Promise<string> {
  const out = join(h.root, "standin.out");
  const fd = openSync(out, "w");
  const p = spawn("bun", [join(import.meta.dir, "..", "e2e", "docker", "mock_chat.ts"), "0"], { cwd: h.root, env: homeEnv(h), stdio: ["ignore", fd, fd], detached: true });
  p.unref();
  // a minute for the stand-in to say its port: the Mac it runs on carries other campaigns' checks at once
  for (let i = 0; i < 600; i++) {
    const m = /"port":\s*(\d+)/.exec(existsSync(out) ? readFileSync(out, "utf8") : "");
    if (m) return `http://127.0.0.1:${m[1]}`;
    await Bun.sleep(100);
  }
  throw new Error(`the stand-in upstream did not start: ${readFileSync(out, "utf8")}`);
}

export class Daemon {
  constructor(readonly h: Home, readonly port: number) {}

  get url(): string {
    return `http://127.0.0.1:${this.port}`;
  }

  env(): Record<string, string> {
    return homeEnv(this.h, { [KEY_ENV]: STANDIN_KEY });
  }

  private java(args: string[]): string[] {
    return ["-Xmx1g", "-Dsplice.noSystemBrowser=1", `-Duser.home=${this.h.root}`, "-jar", this.h.jar, ...args];
  }

  /** Starts the daemon from [jar] (copied into the home, so a rebuild never pulls it from under it). */
  async start(jar: string, toml: string): Promise<void> {
    copyFileSync(jar, this.h.jar);
    writeText(this.h.config, toml);
    const fd = openSync(this.h.log, "a");
    const p = spawn("java", this.java(["daemon"]), { cwd: this.h.root, env: this.env(), stdio: ["ignore", fd, fd], detached: true });
    p.unref();
    for (let i = 0; i < 720; i++) {
      if (p.exitCode !== null) throw new Error(`the daemon exited ${p.exitCode}:\n${this.logTail()}`);
      if (readFileSync(this.h.log, "utf8").includes("[daemon] up")) return;
      await Bun.sleep(500);
    }
    throw new Error(`the daemon did not come up in 360 s:\n${this.logTail()}`);
  }

  logTail(): string {
    return existsSync(this.h.log) ? readFileSync(this.h.log, "utf8").slice(-3000) : "(no log)";
  }

  /** This home's management key: made up by this daemon when it first started, good for this home alone. */
  key(): string {
    const f = join(this.h.state, "mgmt-key");
    if (!existsSync(f) || statSync(f).size === 0) throw new Error("the daemon wrote no management key");
    return readFileSync(f, "utf8").trim();
  }

  api(path: string, init: RequestInit = {}): Promise<Response> {
    const headers = new Headers(init.headers);
    headers.set("authorization", `Bearer ${this.key()}`);
    if (init.body && !headers.has("content-type")) headers.set("content-type", "application/json");
    return fetch(`${this.url}${path}`, { ...init, headers });
  }

  /** `splice <args>` against this daemon, from this home. */
  cli(args: string[]): { code: number; out: string } {
    const r = spawnSync("java", this.java(args), { cwd: this.h.root, env: this.env(), encoding: "utf8", timeout: 300_000 });
    return { code: r.status ?? -1, out: `${r.stdout ?? ""}${r.stderr ?? ""}` };
  }
}
