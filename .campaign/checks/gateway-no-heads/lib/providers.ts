// What the gateway-no-heads checks share: providers declared in splice.toml that no head names, each an
// OpenAI-compatible mock on a key of its own; the Splice gateway's list as `splice claude --json` gives it; a turn and a
// count sent through the gateway the way Claude Code sends them; the usage an answer reports; the ports the daemon
// listens on; and the fix session run on a terminal, the way a person meets it. The repository is public: every
// provider and model here is made up.

import { spawnSync } from "node:child_process";
import { readFileSync, readdirSync, readlinkSync } from "node:fs";
import { type Session, lane } from "../../door/lib/claude.ts";
import { doorRaw } from "../../custom/lib/team.ts";
import { type Splice, jar } from "../../server/lib/harness.ts";

export const PEBBLE = "Pebble-1-Small-Q4_K_M";
export const SLATE = "Slate-2-Large";

export interface Declared {
  key: string;
  url: string;
  model: string;
  /** The row's own context_window. */
  window: number;
  /** The variable its API key is read from. */
  env: string;
  /** More lines of the provider's own table, before its quirks and rows. */
  more?: string;
  /** Its `[providers.<key>.quirks]` lines. */
  quirks?: string;
}

/** A provider and its one model, on an API key, with no head: nothing under `[heads.*]` names it. */
export function provider(o: Declared): string {
  return `
[providers.${o.key}]
dialect = "openai-chat"
base_url = "${o.url}"
auth = { kind = "api-key", env = "${o.env}" }
${o.more ?? ""}
${o.quirks ? `[providers.${o.key}.quirks]\n${o.quirks}\n` : ""}
[[providers.${o.key}.models]]
id = "${o.model}"
label = "${o.model}"
context_window = ${o.window}
`;
}

/** The selector the gateway lists a provider's model under. */
export function selector(provider: string, model: string): string {
  return `claude-${provider}--${model}`;
}

export interface GatewayRow {
  model: string;
  label: string;
  provider?: string;
  head: string;
  command: string;
}

export interface LeftOutRow {
  provider?: string;
  head: string;
  command: string;
  reason: string;
}

/** The gateway's list as `splice claude --json` gives it, in its order. */
export function listed(s: Splice): GatewayRow[] {
  return (lane(s).models ?? []) as GatewayRow[];
}

/** What `splice claude --json` says is left out of the gateway, and why. */
export function leftOut(s: Splice): LeftOutRow[] {
  return (lane(s).left_out ?? []) as LeftOutRow[];
}

/** One turn through the gateway naming `model`, as Claude Code sends it, not streamed unless asked; `effort` is the
 *  session's reasoning effort, carried in `output_config` as Claude Code carries it. */
export function send(
  x: Session,
  model: string,
  o: { effort?: string; content?: string; session?: string; stream?: boolean } = {},
): Promise<{ status: number; headers: Headers; body: string }> {
  const body: Record<string, unknown> = {
    model,
    max_tokens: 1024,
    stream: o.stream ?? false,
    messages: [{ role: "user", content: o.content ?? `Say hello. ${crypto.randomUUID()}` }],
  };
  if (o.effort) body.output_config = { effort: o.effort };
  return doorRaw(x, body, { session: o.session });
}

/** The input a non-streamed answer reports, as Claude Code adds it up to decide when to compact: its input, cache-read
 *  and cache-creation tokens together. NaN when the answer carries no usage. */
export function inputOf(answer: { body: string }): number {
  try {
    const u = (JSON.parse(answer.body) as { usage?: Record<string, number> }).usage ?? {};
    return Number(u.input_tokens ?? Number.NaN) + Number(u.cache_read_input_tokens ?? 0) + Number(u.cache_creation_input_tokens ?? 0);
  } catch {
    return Number.NaN;
  }
}

/** The error message of an answer, streamed or not, or "" when it is none. */
export function messageOf(answer: { body: string }): string {
  for (const chunk of answer.body.split(/\n/)) {
    const line = chunk.replace(/^data:\s*/, "").trim();
    if (!line.startsWith("{")) continue;
    try {
      const j = JSON.parse(line) as { type?: string; error?: { message?: string } };
      if (j.type === "error" && j.error) return String(j.error.message ?? "");
    } catch {}
  }
  return "";
}

/** splice.toml as the daemon reads it now, parsed. */
export function tomlOf(s: Splice): Record<string, any> {
  return Bun.TOML.parse(readFileSync(s.config, "utf8")) as Record<string, any>;
}

/** The TCP ports the daemon of `s` listens on, read from Linux's /proc: every process whose environment names this
 *  home's state directory, its sockets, and those of them in LISTEN. Sorted, each once. */
export function listening(s: Splice): number[] {
  const pids = readdirSync("/proc").filter((d) => {
    if (!/^\d+$/.test(d) || Number(d) === process.pid) return false;
    try {
      return readFileSync(`/proc/${d}/environ`, "latin1").split("\0").includes(`SPLICE_STATE_DIR=${s.state}`);
    } catch {
      return false;
    }
  });
  const inodes = new Set<string>();
  for (const pid of pids) {
    let fds: string[] = [];
    try {
      fds = readdirSync(`/proc/${pid}/fd`);
    } catch {
      continue;
    }
    for (const fd of fds) {
      try {
        const m = readlinkSync(`/proc/${pid}/fd/${fd}`).match(/^socket:\[(\d+)\]$/);
        if (m) inodes.add(m[1]!);
      } catch {}
    }
  }
  const ports = new Set<number>();
  for (const table of ["/proc/net/tcp", "/proc/net/tcp6"]) {
    for (const line of readFileSync(table, "utf8").split("\n").slice(1)) {
      const c = line.trim().split(/\s+/);
      if (c.length < 10 || c[3] !== "0A" || !inodes.has(c[9]!)) continue;
      ports.add(parseInt(c[1]!.split(":").pop()!, 16));
    }
  }
  return [...ports].sort((a, b) => a - b);
}

/** `splice <args>` on a terminal, as a person runs it: the fix session is offered only there. `answers` are typed one
 *  per line, in order. */
export function onTerminal(s: Splice, args: string[], answers: string[]): { code: number; out: string } {
  const java = ["java", "-Xmx1g", "-Dsplice.noSystemBrowser=1", `-Duser.home=${s.home}`, "-jar", jar(), ...args];
  const command = java.map((a) => `'${a.replaceAll("'", "'\\''")}'`).join(" ");
  const r = spawnSync("script", ["-qec", command, "/dev/null"], {
    cwd: s.home,
    env: { ...s.env(), TERM: "dumb" },
    input: answers.map((a) => `${a}\n`).join(""),
    encoding: "utf8",
    timeout: 180_000,
  });
  return { code: r.status ?? -1, out: `${r.stdout ?? ""}${r.stderr ?? ""}` };
}
