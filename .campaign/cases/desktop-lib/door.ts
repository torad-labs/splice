// What the desktop-door cases share: heads on stand-in providers, the settings file of the terminal's `claude` as
// `splice desktop on` writes it and as the Code tab's Claude Code reads it, requests sent through the door the way
// that Claude Code sends them in Mantle mode, and launches read the way the `splice-launch` shim reads them
// (docs/specs/desktop-door.md). The daemon, its home and the stand-in providers are the server checks' own.

import { spawnSync } from "node:child_process";
import { chmodSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { type Ran, type Splice, type Turn, json, turn, until } from "../../checks/server/lib/harness.ts";
import type { Upstream } from "../../checks/server/lib/upstream.ts";
import { must, show } from "./cases.ts";

// ---------- heads ----------

export interface Model {
  id: string;
  label?: string;
  window?: number;
}

/** A provider splice calls with an API key it holds, and its head. The first model is the head's pinned model, and
 *  `slots` are the models the head names for Claude Code's tiers (`model_slots`). */
export function keyHead(o: { key: string; command: string; port: number; url: string; models: Model[]; slots?: Record<string, string> }): string {
  const models = o.models
    .map(
      (m) => `
[[providers.${o.key}.models]]
id = "${m.id}"
label = "${m.label ?? m.id}"
context_window = ${m.window ?? 131072}
rates = { input = 2.0, cache_read = 0.5, output = 8.0 }
`,
    )
    .join("");
  const slots = o.slots
    ? `model_slots = { ${Object.entries(o.slots)
        .map(([tier, id]) => `${tier} = "${id}"`)
        .join(", ")} }\n`
    : "";
  return `
[providers.${o.key}]
dialect = "openai-chat"
base_url = "${o.url}"
auth = { kind = "api-key", env = "MOCK_UPSTREAM_KEY" }
${models}
[heads.${o.key}]
provider = "${o.key}"
port = ${o.port}
discovery_prefix = "claude-${o.key}--"
pinned_model = "${o.models[0]?.id ?? ""}"
${slots}[heads.${o.key}.claude]
command = "${o.command}"
`;
}

/** A head that forwards the caller's own Claude login (`auth = { kind = "client" }`), as the `claude` head does. */
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

/** A head signed in with a ChatGPT login, which splice keeps in the file `login`. The file is not written here: a
 *  head whose file is not there is one whose login is missing, and `signIn` is what signing it in leaves. */
export function chatgptHead(o: { key: string; command: string; port: number; backend: string; model: string; login: string }): string {
  return `
[providers.${o.key}]
dialect = "openai-responses"
base_url = "${o.backend}"
auth = { kind = "chatgpt-oauth", file = "${o.login}" }
quirks = { code_mode = false, account_id_header = true, websocket = false, zstd_request_body = false, tool_surface = { enabled = true } }

[[providers.${o.key}.models]]
id = "${o.model}"
label = "${o.model}"
context_window = 400000

[heads.${o.key}]
provider = "${o.key}"
port = ${o.port}
discovery_prefix = "claude-${o.key}--"
pinned_model = "${o.model}"
[heads.${o.key}.claude]
command = "${o.command}"
`;
}

/** The login a sign-in leaves in `file`: tokens good for an hour, for the owner only. */
export function signIn(file: string, account: string): void {
  const payload = Buffer.from(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 })).toString("base64url");
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(
    file,
    JSON.stringify({ tokens: { access_token: `e30.${payload}.synthetic-${account}`, refresh_token: "mock-refresh", account_id: account }, last_refresh: new Date().toISOString() }),
  );
  chmodSync(file, 0o600);
}

// ---------- the settings file ----------

/** The variables `splice desktop on` writes into the file's `env`. */
export const WRITTEN = [
  "CLAUDE_CODE_USE_MANTLE",
  "ANTHROPIC_BEDROCK_MANTLE_BASE_URL",
  "AWS_BEARER_TOKEN_BEDROCK",
  "CLAUDE_CODE_MAX_CONTEXT_TOKENS",
  "CLAUDE_CODE_AUTO_MODE_SERVER",
  "ANTHROPIC_MODEL",
] as const;

/** The settings file of the terminal's `claude`, which the Code tab's Claude Code reads for every local session. */
export function settingsFile(s: Splice): string {
  return join(s.home, ".claude", "settings.json");
}

/** The file's text, or null when there is no file. */
export function settingsText(s: Splice): string | null {
  return existsSync(settingsFile(s)) ? readFileSync(settingsFile(s), "utf8") : null;
}

/** What the file holds, or nothing when there is no file. */
export function settings(s: Splice): Record<string, unknown> {
  const text = settingsText(s);
  if (text === null) return {};
  const value: unknown = JSON.parse(text);
  must(typeof value === "object" && value !== null && !Array.isArray(value), `the settings file holds ${show(value)}, not an object`);
  return value as Record<string, unknown>;
}

/** The person's own settings, as they are before splice touches the file. */
export function writeSettings(s: Splice, value: Record<string, unknown>): void {
  mkdirSync(dirname(settingsFile(s)), { recursive: true });
  writeFileSync(settingsFile(s), `${JSON.stringify(value, null, 2)}\n`);
}

/** The file's `env` block, which Claude Code applies to every local session. */
export function fileEnv(s: Splice): Record<string, string> {
  return (settings(s).env ?? {}) as Record<string, string>;
}

export interface Row {
  model: string;
  label?: string;
  description?: string;
}

/** The rows of a `modelPicker`: its list of entries that each name a `model`, under whatever key Claude Code reads
 *  them from. */
export function rowsOf(picker: unknown): Row[] {
  if (typeof picker !== "object" || picker === null) return [];
  for (const v of Object.values(picker)) {
    if (Array.isArray(v) && v.length > 0 && v.every((r) => typeof r === "object" && r !== null && typeof (r as Row).model === "string")) return v as Row[];
  }
  return [];
}

/** The picker's rows, as the file holds them. */
export function rows(s: Splice): Row[] {
  return rowsOf(settings(s).modelPicker);
}

// ---------- splice desktop ----------

export interface Door {
  /** The address the Code tab's Claude Code calls. */
  url: string;
  /** The desktop key. */
  key: string;
  /** The context window the file declares to Claude Code. */
  window: number;
  /** The model a session starts on. */
  model: string;
}

/** The door, as the file gives it to the Code tab's Claude Code. */
export function doorOf(s: Splice): Door {
  const env = fileEnv(s);
  const url = env.ANTHROPIC_BEDROCK_MANTLE_BASE_URL;
  const key = env.AWS_BEARER_TOKEN_BEDROCK;
  must(Boolean(url) && Boolean(key), `the settings file names no door address or no desktop key: its env is ${show(env)}`);
  return { url: String(url), key: String(key), window: Number(env.CLAUDE_CODE_MAX_CONTEXT_TOKENS), model: env.ANTHROPIC_MODEL ?? "" };
}

/** What a command printed, on one line. */
export function printed(r: Ran): string {
  return `${r.out}\n${r.err}`.replace(/\s+/g, " ").trim();
}

/** `splice desktop <args>`, which must end well. */
export function desktop(s: Splice, ...args: string[]): Ran {
  const r = s.cli(["desktop", ...args]);
  must(r.code === 0, `splice desktop ${args.join(" ")} exited ${r.code}: ${printed(r).slice(0, 160)}`);
  return r;
}

/** `splice desktop on <args>`, then the door it wrote. */
export function desktopOn(s: Splice, ...args: string[]): Door {
  desktop(s, "on", ...args);
  return doorOf(s);
}

export interface Desk {
  on: boolean;
  door: string;
  default_model: string;
  models: { model: string; label: string; head: string; command: string }[];
  left_out: { head: string; command: string; reason: string }[];
  last_turn: { at: unknown; session_id: string; head: string; model_asked: string; model: string } | null;
}

/** What `splice desktop --json` says. */
export function desk(s: Splice): Desk {
  return json<Desk>(desktop(s, "--json"));
}

// ---------- requests through the door ----------

const CLAUDE_CODE = { "user-agent": "claude-cli/2.1.325 (external, cli)", "x-app": "cli" };

export interface Ask {
  model: string;
  session?: string;
  stream?: boolean;
  /** The key to present as `Authorization: Bearer`: the door's own when left out, none when null. */
  key?: string | null;
  /** Whether the route carries `?beta=true`, as a turn's does. */
  beta?: boolean;
  content?: string;
}

function presented(d: { key: string }, key: string | null | undefined): string | undefined {
  return key === undefined ? d.key : (key ?? undefined);
}

/** One turn through the door, as the Code tab's Claude Code 2.1.325 sends it in Mantle mode: the Messages route
 *  under the door's address, the key as a Bearer and never as `x-api-key`, and the session's ID in Claude Code's
 *  header. */
export function ask(d: { url: string; key: string }, o: Ask): Promise<Turn> {
  const headers: Record<string, string> = { ...CLAUDE_CODE };
  if (o.session) headers["x-claude-code-session-id"] = o.session;
  return turn(`${d.url}/v1/messages${o.beta === false ? "" : "?beta=true"}`, {
    key: presented(d, o.key),
    bearer: true,
    model: o.model,
    stream: o.stream,
    content: o.content,
    headers,
  });
}

export interface Counted {
  status: number;
  input_tokens: number;
  body: string;
}

/** A `count_tokens` request through the door, as `/context` sends it. */
export async function count(d: { url: string; key: string }, o: Ask): Promise<Counted> {
  const headers: Record<string, string> = { "content-type": "application/json", "anthropic-version": "2023-06-01", ...CLAUDE_CODE };
  const key = presented(d, o.key);
  if (key !== undefined) headers.authorization = `Bearer ${key}`;
  if (o.session) headers["x-claude-code-session-id"] = o.session;
  return counted(`${d.url}/v1/messages/count_tokens${o.beta === false ? "" : "?beta=true"}`, headers, o);
}

/** The same request on a head's own route, with the key a session launched on the head presents. */
export function countOnHead(base: string, key: string, o: { model: string; content?: string }): Promise<Counted> {
  return counted(`${base}/v1/messages/count_tokens?beta=true`, { "content-type": "application/json", "anthropic-version": "2023-06-01", "x-api-key": key }, o);
}

async function counted(url: string, headers: Record<string, string>, o: { model: string; content?: string }): Promise<Counted> {
  const r = await fetch(url, { method: "POST", headers, body: JSON.stringify({ model: o.model, messages: [{ role: "user", content: o.content ?? "Say hello." }] }) });
  const body = await r.text();
  let tokens = Number.NaN;
  try {
    tokens = Number((JSON.parse(body) as { input_tokens?: unknown }).input_tokens);
  } catch {}
  return { status: r.status, input_tokens: tokens, body };
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

/** What an answer was, for a failure to show. */
export function answer(t: Turn): string {
  return `${t.status} ${t.body.replace(/\s+/g, " ").slice(0, 200)}`;
}

/** The requests a provider was sent that ask it for work: a turn in any dialect. The daemon's own looks at a
 *  provider (its health, its version, its model list), which go on all the time, are not among them. */
export function work(up: Upstream): number {
  return up.seen.filter((r) => r.body !== null || /\/(completions|messages|responses)$/.test(r.path)).length;
}

/** Fails unless one turn through the door that names `asked` is answered by `up`, which was asked once, for
 *  `model`. */
export async function runs(d: Door, up: Upstream, asked: string, model: string, session: string = crypto.randomUUID()): Promise<void> {
  const before = up.completions().length;
  const t = await ask(d, { model: asked, session });
  must(t.status === 200, `a turn naming ${asked} was answered ${answer(t)}`);
  must(t.reply === up.reply(), `a turn naming ${asked} was answered "${t.reply}", not by ${up.name}`);
  const sent = up.completions().slice(before);
  must(sent.length === 1, `a turn naming ${asked} sent ${up.name} ${sent.length} requests, not one`);
  must(sent[0]?.body?.model === model, `a turn naming ${asked} ran ${show(sent[0]?.body?.model)} on ${up.name}, not ${model}`);
}

/** Fails unless `t` is a refusal in Anthropic's error shape with this status and type. */
export function refused(t: Turn, status: number, type: string): void {
  must(t.status === status, `it was answered ${answer(t)}, not ${status}`);
  must(t.error !== null, `the ${status} is not in Anthropic's error shape: ${t.body.replace(/\s+/g, " ").slice(0, 200)}`);
  must(t.error.type === type, `the error is ${show(t.error.type)}, not ${type}`);
}

// ---------- recorded turns ----------

export interface PerfRow {
  session_id: string;
  model: string;
  door?: string | null;
  [k: string]: unknown;
}

/** The rows of `session` on head `key`, as `/api/perf/turns` answers them. */
export async function headRows(s: Splice, key: string, session: string): Promise<PerfRow[]> {
  const r = await s.api(`/api/perf/turns?head=${key}`);
  must(r.status === 200, `/api/perf/turns?head=${key} answered ${r.status}`);
  const j = (await r.json()) as { heads?: { key: string; rows?: PerfRow[] }[] };
  return (j.heads?.find((h) => h.key === key)?.rows ?? []).filter((row) => row.session_id === session);
}

/** The one row `session` has on head `key`, once it is recorded. */
export async function headRow(s: Splice, key: string, session: string): Promise<PerfRow> {
  let found: PerfRow[] = [];
  await until(`a row of the session on ${key}`, async () => (found = await headRows(s, key, session)).length > 0);
  must(found.length === 1, `the session has ${found.length} rows on ${key}, not one`);
  return found[0] as PerfRow;
}

// ---------- launches ----------

export interface Recipe {
  argv: string[];
  env: Record<string, string>;
  unset: string[];
  warning?: string | null;
}

/** The recipe a launch of `command` answers the `splice-launch` shim. */
export async function recipe(s: Splice, command: string): Promise<Recipe> {
  const form = new URLSearchParams({ dangerouslySkipPermissions: "false", cwd: s.home, launch: String(process.pid) });
  const r = await s.api(`/launch/${command}`, { method: "POST", body: form.toString(), headers: { "content-type": "application/x-www-form-urlencoded" } });
  const text = await r.text();
  must(r.status === 200, `launching ${command} answered ${r.status}: ${text.slice(0, 300)}`);
  const got = JSON.parse(text) as Partial<Recipe>;
  return { argv: got.argv ?? [], env: got.env ?? {}, unset: got.unset ?? [], warning: got.warning ?? null };
}

/** The settings a launch hands Claude Code on its command line (`--settings`, as JSON or as a file of it), which
 *  outrank the settings file. Null when the launch hands none. */
export function overlay(r: Recipe): Record<string, unknown> | null {
  const at = r.argv.indexOf("--settings");
  const given = at >= 0 ? r.argv[at + 1] : undefined;
  if (given === undefined) return null;
  return JSON.parse(given.trimStart().startsWith("{") ? given : readFileSync(given, "utf8")) as Record<string, unknown>;
}

export interface Launched {
  recipe: Recipe;
  /** The environment Claude Code runs in: the shell's, the recipe's sets over it, its unsets removed. */
  env: Record<string, string>;
  /** The config folder the launch names, or null when it runs over the `claude` command's own. */
  dir: string | null;
  /** The settings the launch gives Claude Code: its config folder's file, or its `--settings` overlay. */
  settings: Record<string, unknown>;
  home: string;
}

/** Launches `command` as the shim does from a shell whose environment also holds `shell`. */
export async function launch(s: Splice, command: string, shell: Record<string, string> = {}): Promise<Launched> {
  const r = await recipe(s, command);
  const env: Record<string, string> = { ...s.env(), ...shell, ...r.env };
  for (const name of r.unset) delete env[name];
  const dir = r.env.CLAUDE_CONFIG_DIR ?? null;
  const own = dir === null ? null : join(dir, "settings.json");
  const held = own !== null && existsSync(own) ? (JSON.parse(readFileSync(own, "utf8")) as Record<string, unknown>) : (overlay(r) ?? {});
  return { recipe: r, env, dir, settings: held, home: s.home };
}

/** The turn key: what the session's `apiKeyHelper` hands Claude Code. */
export function turnKey(l: Launched): string {
  const helper = String(l.settings.apiKeyHelper ?? "");
  must(helper !== "", "the launch's settings name no apiKeyHelper");
  const r = spawnSync("bash", ["-c", helper], { cwd: l.home, env: l.env, encoding: "utf8" });
  const key = (r.stdout ?? "").trim();
  must(key !== "", `the apiKeyHelper gave no key: ${r.stderr}`);
  return key;
}

/** The head's own address, as its launch gives it to Claude Code. */
export function headUrl(l: Launched): string {
  const url = l.recipe.env.ANTHROPIC_BASE_URL;
  must(Boolean(url), "the launch names no ANTHROPIC_BASE_URL");
  return String(url);
}

/** One turn of `session` on a head's own route, as a session launched on the head sends it. */
export function askHead(l: Launched, o: { model: string; session: string; stream?: boolean; content?: string }): Promise<Turn> {
  return turn(`${headUrl(l)}/v1/messages?beta=true`, { key: turnKey(l), model: o.model, stream: o.stream, content: o.content, headers: { ...CLAUDE_CODE, "x-claude-code-session-id": o.session } });
}

/** The models a head's own `/v1/models` lists. */
export async function listed(l: Launched): Promise<{ id: string; display_name?: string }[]> {
  const r = await fetch(`${headUrl(l)}/v1/models`, { headers: { "x-api-key": turnKey(l), "anthropic-version": "2023-06-01" } });
  must(r.status === 200, `the head's /v1/models answered ${r.status}`);
  return ((await r.json()) as { data?: { id: string; display_name?: string }[] }).data ?? [];
}

// ---------- files ----------

/** Every file under `dir`, by its path from it. Nothing when there is no such folder. */
export function filesUnder(dir: string): string[] {
  const out: string[] = [];
  const walk = (at: string, prefix: string) => {
    for (const e of readdirSync(at, { withFileTypes: true })) {
      if (e.isDirectory()) walk(join(at, e.name), `${prefix}${e.name}/`);
      else out.push(`${prefix}${e.name}`);
    }
  };
  if (existsSync(dir)) walk(dir, "");
  return out.sort();
}

/** The files under `dir` that hold `text`, by their paths from it. */
export function holders(dir: string, text: string): string[] {
  return filesUnder(dir).filter((f) => {
    try {
      return readFileSync(join(dir, f)).includes(text);
    } catch {
      return false;
    }
  });
}
