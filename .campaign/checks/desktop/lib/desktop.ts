// What the desktop-door checks share: heads on stand-in model servers, the door as `splice desktop on` says to set it
// in Claude Desktop's own Code environment, the terminal's settings file splice must leave alone, and requests sent the
// way the Code tab's Claude Code sends them in Mantle mode (docs/specs/desktop-door.md).

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { type Splice, type Turn, json, turn } from "../../server/lib/harness.ts";

/** One provider with two models and one head whose opus and sonnet tiers run `big` and whose haiku tier runs
 *  `small`. Its pinned model is `big`. */
export function slottedHead(o: { key: string; command: string; port: number; url: string; big: string; small: string; window?: number }): string {
  const model = (id: string) => `
[[providers.${o.key}.models]]
id = "${id}"
label = "${id}"
context_window = ${o.window ?? 131072}
rates = { input = 2.0, cache_read = 0.5, output = 8.0 }
`;
  return `
[providers.${o.key}]
dialect = "openai-chat"
base_url = "${o.url}"
auth = { kind = "api-key", env = "MOCK_UPSTREAM_KEY" }
model_slots = { opus = "${o.big}", sonnet = "${o.big}", haiku = "${o.small}" }
${model(o.big)}${model(o.small)}
[heads.${o.key}]
provider = "${o.key}"
port = ${o.port}
discovery_prefix = "claude-${o.key}--"
pinned_model = "${o.big}"
[heads.${o.key}.claude]
command = "${o.command}"
`;
}

/** A head that forwards the caller's own Claude login, as the `claude` head does. */
export function clientHead(o: { key: string; command: string; port: number; url: string }): string {
  return `
[providers.${o.key}]
dialect = "anthropic-passthrough"
base_url = "${o.url}"
auth = { kind = "client" }
extra_headers = { anthropic-version = "2023-06-01" }

[[providers.${o.key}.models]]
id = "claude-opus-5-5"
label = "claude-opus-5-5"
context_window = 200000

[heads.${o.key}]
provider = "${o.key}"
port = ${o.port}
discovery_prefix = "claude-${o.key}--"
pinned_model = "claude-opus-5-5"
[heads.${o.key}.claude]
command = "${o.command}"
`;
}

/** The settings file of the terminal's `claude`, which the Code tab's Claude Code reads for every local session. */
export function settingsFile(s: Splice): string {
  return join(s.home, ".claude", "settings.json");
}

/** What the file holds, or nothing when there is no file. */
export function readSettings(s: Splice): Record<string, unknown> {
  const f = settingsFile(s);
  return existsSync(f) ? (JSON.parse(readFileSync(f, "utf8")) as Record<string, unknown>) : {};
}

/** The person's own settings, as they were before splice touched the file. */
export function writeSettings(s: Splice, value: Record<string, unknown>): void {
  mkdirSync(dirname(settingsFile(s)), { recursive: true });
  writeFileSync(settingsFile(s), `${JSON.stringify(value, null, 2)}\n`);
}

export interface Door {
  /** The address the Code tab's Claude Code calls. */
  url: string;
  /** The desktop key. */
  key: string;
  /** The context window declared to Claude Code. */
  window: number;
  /** The model a session starts on. */
  model: string;
  /** Every variable `splice desktop on` says to set in Desktop's Code environment, as a Code tab session's shell
   *  carries them. */
  env: Record<string, string>;
}

/** The `NAME=value` lines splice prints for Desktop's Code environment. */
export function printedEnv(out: string): Record<string, string> {
  const env: Record<string, string> = {};
  for (const m of out.matchAll(/^\s+([A-Z][A-Z0-9_]*)=(\S*)\s*$/gm)) env[m[1]!] = m[2]!;
  return env;
}

const DOORS = new WeakMap<Splice, Door>();

/** The door from what `splice desktop on` or `rotate-key` printed ([out]): its variables, and the model a session starts
 *  on, which `on` names and `rotate-key` leaves as it was. */
function doorOf(s: Splice, out: string): Door {
  const env = printedEnv(out);
  const url = env.ANTHROPIC_BEDROCK_MANTLE_BASE_URL;
  const key = env.AWS_BEARER_TOKEN_BEDROCK;
  if (!url || !key) throw new Error(`splice printed no door address or no desktop key:\n${out}`);
  const model = /a session starts on (\S+)/.exec(out)?.[1] ?? DOORS.get(s)?.model ?? "";
  const d = { url, key, window: Number(env.CLAUDE_CODE_MAX_CONTEXT_TOKENS), model, env };
  DOORS.set(s, d);
  return d;
}

/** The door as `splice desktop on` or `rotate-key` last said it. */
export function door(s: Splice): Door {
  const d = DOORS.get(s);
  if (!d) throw new Error("splice desktop on has not said the door yet");
  return d;
}

/** `splice desktop on`, then the door it says to set in Desktop. */
export function turnOn(s: Splice, ...args: string[]): Door {
  const r = s.cli(["desktop", "on", ...args]);
  if (r.code !== 0) throw new Error(`splice desktop on exited ${r.code}:\n${r.out}\n${r.err}`);
  return doorOf(s, `${r.out}\n${r.err}`);
}

/** `splice desktop rotate-key` while Desktop is on, then the door with the new key it says to set. */
export function rotateKey(s: Splice): Door {
  const r = s.cli(["desktop", "rotate-key"]);
  if (r.code !== 0) throw new Error(`splice desktop rotate-key exited ${r.code}:\n${r.out}\n${r.err}`);
  return doorOf(s, `${r.out}\n${r.err}`);
}

export interface Desk {
  on: boolean;
  door: string;
  default_model: string;
  models: { model: string; label: string; head: string; command: string }[];
  left_out: { head: string; command: string; reason: string }[];
  last_turn: { at: string; session_id: string; head: string; model_asked: string; model: string } | null;
}

/** What `splice desktop --json` says. */
export function desktop(s: Splice): Desk {
  const r = s.cli(["desktop", "--json"]);
  if (r.code !== 0) throw new Error(`splice desktop --json exited ${r.code}:\n${r.out}\n${r.err}`);
  return json<Desk>(r);
}

const CLAUDE_CODE = { "user-agent": "claude-cli/2.1.325 (external, cli)", "x-app": "cli" };

export interface DoorTurn {
  /** The key, sent as `Authorization: Bearer`. Left out, the request carries none. */
  key?: string;
  model?: string;
  stream?: boolean;
  session?: string;
  content?: string;
}

/** One turn through the door, as the Code tab's Claude Code 2.1.325 sends it in Mantle mode: the Messages route under
 *  the door's address with `?beta=true`, the key as a Bearer and never as `x-api-key`, and the session's ID in Claude
 *  Code's header. With no model named it asks for Anthropic's Opus in the Mantle spelling, as a session with no pick
 *  does. */
export function doorTurn(url: string, o: DoorTurn = {}): Promise<Turn> {
  const headers: Record<string, string> = { ...CLAUDE_CODE };
  if (o.session) headers["x-claude-code-session-id"] = o.session;
  return turn(`${url}/v1/messages?beta=true`, {
    key: o.key,
    bearer: true,
    model: o.model ?? "anthropic.claude-opus-5-5",
    stream: o.stream,
    content: o.content,
    headers,
  });
}

/** The input a turn's answer reports to Claude Code: its input tokens and both cache counts, which are what Claude
 *  Code sets against its window. Read from a JSON answer's `usage`, or from the largest a stream's events report. */
export function reported(t: Turn): number {
  const sum = (u: unknown): number => {
    const v = (u ?? {}) as Record<string, unknown>;
    return Number(v.input_tokens ?? 0) + Number(v.cache_creation_input_tokens ?? 0) + Number(v.cache_read_input_tokens ?? 0);
  };
  if (!t.events.length) return sum((JSON.parse(t.body) as { usage?: unknown }).usage);
  return Math.max(0, ...t.events.map((e) => Math.max(sum((e.data?.message as { usage?: unknown } | undefined)?.usage), sum(e.data?.usage))));
}

/** A `count_tokens` request through the door, as `/context` sends it. */
export async function countTokens(url: string, o: { key?: string; model: string; content: string; session?: string }): Promise<{ status: number; input_tokens: number }> {
  const headers: Record<string, string> = { "content-type": "application/json", "anthropic-version": "2023-06-01", ...CLAUDE_CODE };
  if (o.key !== undefined) headers.authorization = `Bearer ${o.key}`;
  if (o.session) headers["x-claude-code-session-id"] = o.session;
  const r = await fetch(`${url}/v1/messages/count_tokens?beta=true`, {
    method: "POST",
    headers,
    body: JSON.stringify({ model: o.model, messages: [{ role: "user", content: o.content }] }),
  });
  const j = (await r.json().catch(() => null)) as { input_tokens?: number } | null;
  return { status: r.status, input_tokens: Number(j?.input_tokens ?? Number.NaN) };
}

export interface Recipe {
  argv: string[];
  env: Record<string, string>;
  unset: string[];
}

/** The recipe a launch of `command` answers the `splice-launch` shim. */
export async function recipe(s: Splice, command: string): Promise<Recipe> {
  const form = new URLSearchParams({ dangerouslySkipPermissions: "false", cwd: s.home, launch: String(process.pid) });
  const r = await s.api(`/launch/${command}`, { method: "POST", body: form.toString(), headers: { "content-type": "application/x-www-form-urlencoded" } });
  const text = await r.text();
  if (r.status !== 200) throw new Error(`launching ${command} answered ${r.status}: ${text}`);
  return JSON.parse(text) as Recipe;
}

export interface PerfRow {
  session_id: string;
  model: string;
  door?: string | null;
  [k: string]: unknown;
}

/** The rows of `session` on head `key`, oldest first, as the route answers them. */
export async function rows(s: Splice, key: string, session: string): Promise<PerfRow[]> {
  const j = (await (await s.api(`/api/perf/turns?head=${key}`)).json()) as { heads: { key: string; rows: PerfRow[] }[] };
  return (j.heads.find((h) => h.key === key)?.rows ?? []).filter((r) => r.session_id === session);
}
