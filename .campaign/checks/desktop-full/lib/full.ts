// What the desktop-full checks share (docs/specs/desktop-full.md): Claude Desktop's third-party ("3P") local
// configuration as splice writes it, the credential helper run the way Desktop runs it, requests sent the way Desktop
// and its Code tab send them to a gateway, and a Claude account added to splice's claude head.

import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { type Ran, type Splice, type Turn, jar, json, turn } from "../../server/lib/harness.ts";

/** Desktop's per-user configuration library, under the home's own config folder (Electron's appData on Linux). */
export function library(s: Splice): string {
  return join(s.home, ".config", "Claude-3p", "configLibrary");
}

export interface Meta {
  appliedId: string;
  entries: { id: string; name: string; provider?: string; note?: string }[];
  [k: string]: unknown;
}

/** `_meta.json`, or null when there is none. */
export function meta(s: Splice): Meta | null {
  const f = join(library(s), "_meta.json");
  return existsSync(f) ? (JSON.parse(readFileSync(f, "utf8")) as Meta) : null;
}

/** The config `_meta.json` applies, or null when it applies none. */
export function applied(s: Splice): Record<string, unknown> | null {
  const m = meta(s);
  if (!m?.appliedId) return null;
  const f = join(library(s), `${m.appliedId}.json`);
  return existsSync(f) ? (JSON.parse(readFileSync(f, "utf8")) as Record<string, unknown>) : null;
}

/** Every file in the library, by name, with its text. */
export function libraryFiles(s: Splice): Record<string, string> {
  const dir = library(s);
  if (!existsSync(dir)) return {};
  return Object.fromEntries(readdirSync(dir).sort().map((n) => [n, readFileSync(join(dir, n), "utf8")]));
}

/** A config of someone else's, applied, as Desktop's own window or another vendor's CLI leaves it. */
export function theirConfig(s: Splice): { id: string; text: string; meta: string } {
  const id = "0b7f7f3e-2c1a-4c55-9a51-5f0d1e2a3b4c";
  const text = `${JSON.stringify({ inferenceProvider: "bedrock", inferenceBedrockRegion: "us-east-1" }, null, 2)}\n`;
  const m = `${JSON.stringify({ appliedId: id, entries: [{ id, name: "Work Bedrock" }] }, null, 2)}\n`;
  mkdirSync(library(s), { recursive: true });
  writeFileSync(join(library(s), `${id}.json`), text);
  writeFileSync(join(library(s), "_meta.json"), m);
  return { id, text, meta: m };
}

/** `splice desktop on --app`, with more arguments. Nothing is asked: no terminal is attached. */
export function appOn(s: Splice, ...args: string[]): Ran {
  return s.cli(["desktop", "on", "--app", ...args]);
}

/** `splice desktop on --app --yes`, which must take. */
export function appOnYes(s: Splice): Ran {
  const r = appOn(s, "--yes");
  if (r.code !== 0) throw new Error(`splice desktop on --app --yes exited ${r.code}:\n${r.out}\n${r.err}`);
  return r;
}

/** The base URL the applied config gives Desktop. */
export function base(s: Splice): string {
  const c = applied(s);
  const url = c?.inferenceGatewayBaseUrl;
  if (typeof url !== "string") throw new Error(`no gateway base URL is applied: ${JSON.stringify(c)}`);
  return url;
}

/** The headers the applied config has Desktop send on every request. */
export function customHeaders(s: Splice): Record<string, string> {
  return (applied(s)?.inferenceCustomHeaders ?? {}) as Record<string, string>;
}

/** The applied config's credential helper run as Desktop runs it: the path, its arguments, Desktop's own environment
 *  (not splice's: no SPLICE_* variable), no terminal. What it printed, trimmed, and how it exited. */
export function runHelper(s: Splice): { code: number; out: string; err: string } {
  const c = applied(s);
  const path = c?.inferenceCredentialHelper;
  const args = (c?.inferenceCredentialHelperArgs ?? []) as string[];
  if (typeof path !== "string") throw new Error(`no credential helper is applied: ${JSON.stringify(c)}`);
  const env = { PATH: process.env.PATH ?? "/usr/bin:/bin", HOME: s.home, CLAUDE_HELPER_CONTEXT: "inference" };
  const r = spawnSync(path, args, { env, encoding: "utf8", timeout: 60_000, stdio: ["ignore", "pipe", "pipe"] });
  return { code: r.status ?? -1, out: (r.stdout ?? "").trim(), err: r.stderr ?? "" };
}

/** `splice desktop credential` with a terminal attached (a pty from `script`), as a person typing it would run it. */
export function credentialOnTerminal(s: Splice): { code: number; out: string } {
  const env = { ...s.env(), SPLICE_JAR: jar() };
  const cmd = `java -Duser.home='${s.home}' -jar '${jar()}' desktop credential`;
  const r = spawnSync("script", ["-qec", cmd, "/dev/null"], { env, encoding: "utf8", timeout: 120_000 });
  return { code: r.status ?? -1, out: `${r.stdout ?? ""}${r.stderr ?? ""}` };
}

const CODE_TAB = { "user-agent": "claude-cli/2.1.326 (external, claude-desktop)", "x-app": "cli" };

export interface GatewayTurn {
  key: string;
  /** How the credential is sent: Desktop's default for a gateway is bearer. */
  scheme?: "bearer" | "x-api-key";
  model: string;
  stream?: boolean;
  session?: string;
  content?: string;
  headers?: Record<string, string>;
}

/** One turn as the Code tab's Claude Code sends it to a gateway in 3P mode: `{base}/v1/messages?beta=true`, the
 *  credential in the scheme's header, the config's custom headers, the session's ID. */
export function gatewayTurn(s: Splice, o: GatewayTurn): Promise<Turn> {
  const headers: Record<string, string> = { ...CODE_TAB, ...customHeaders(s), ...o.headers };
  if (o.session) headers["x-claude-code-session-id"] = o.session;
  return turn(`${base(s)}/v1/messages?beta=true`, {
    key: o.key,
    bearer: (o.scheme ?? "bearer") === "bearer",
    model: o.model,
    stream: o.stream,
    content: o.content,
    headers,
  });
}

export interface ModelRow {
  id: string;
  display_name?: string;
  description?: string;
  supports_1m?: boolean;
  max_input_tokens?: number;
  anthropic_family_tier?: string;
  is_family_default?: boolean;
  [k: string]: unknown;
}

export interface ModelsPage {
  status: number;
  data: ModelRow[];
  has_more?: boolean;
  first_id?: string | null;
  last_id?: string | null;
  error?: { type?: string; message?: string };
}

/** One page of `GET {url}/v1/models`, as Desktop's discovery asks for it. */
export async function modelsPage(url: string, o: { key?: string; scheme?: "bearer" | "x-api-key"; limit?: number; after?: string; headers?: Record<string, string> } = {}): Promise<ModelsPage> {
  const headers: Record<string, string> = { "anthropic-version": "2023-06-01", ...o.headers };
  if (o.key !== undefined) {
    if ((o.scheme ?? "bearer") === "bearer") headers.authorization = `Bearer ${o.key}`;
    else headers["x-api-key"] = o.key;
  }
  const q = new URLSearchParams({ limit: String(o.limit ?? 1000) });
  if (o.after) q.set("after_id", o.after);
  const r = await fetch(`${url.replace(/\/+$/, "")}/v1/models?${q}`, { headers, redirect: "manual" });
  const j = (await r.json().catch(() => ({}))) as Partial<ModelsPage> & { error?: ModelsPage["error"] };
  return { status: r.status, data: j.data ?? [], has_more: j.has_more, first_id: j.first_id, last_id: j.last_id, error: j.error };
}

/** Every row discovery reads, page by page, the way Desktop pages (has_more and last_id). */
export async function allModels(url: string, key: string, limit = 1000): Promise<ModelRow[]> {
  const rows: ModelRow[] = [];
  let after: string | undefined;
  for (let i = 0; i < 50; i++) {
    const p = await modelsPage(url, { key, limit, after });
    if (p.status !== 200) throw new Error(`GET /v1/models answered ${p.status}: ${JSON.stringify(p.error)}`);
    rows.push(...p.data);
    if (p.has_more !== true || typeof p.last_id !== "string") return rows;
    after = p.last_id;
  }
  throw new Error("GET /v1/models never stopped paging");
}

/** The id discovery gives the row of [selector] (df-models): `claude-splice-` and 12 decimal digits of the selector's
 *  SHA-256, its first 8 bytes read as one big-endian unsigned number, modulo 10^12, zero-padded. */
export function rowId(selector: string): string {
  const sum = new Bun.CryptoHasher("sha256").update(selector).digest();
  const n = new DataView(sum.buffer, sum.byteOffset, 8).getBigUint64(0, false) % 1_000_000_000_000n;
  return `claude-splice-${n.toString().padStart(12, "0")}`;
}

// Desktop's own rule for which rows a gateway lists (index.chunk-DOKiptke.js, Desktop 5.11492.5): an ID that reads as
// Claude. The code also reads a tier it knows, but the live run on the real Desktop (Nov 19, sandbox) saw every row
// whose id names another vendor dropped whatever its tier, so a row is kept here only when its id reads as Claude.
const TIERS = ["sonnet", "opus", "haiku", "fable", "mythos"];
const OTHER_VENDORS =
  /ark-code|astron|command-r|deepseek|doubao|gemini|gemma|glm|gpt|grok|hermes|hy3|kimi|lfm|\bling\b|llama|longcat|mimo|minimax|mistral|mixtral|moonshot|nemotron|openai|phi-|qianfan|qwen|tc-code|\bunic\b|yi-|stepfun|step-3|seed-|bytedance|hunyuan|granite|amazon\.nova|nova-|devstral|ministral|ernie|codex|arcee|trinity|abab|phi\d|\bk2\.|\bm2\.|jamba|arctic|solar|mercury|zamba|kat-coder|\bds-|dpsk/;

/** Whether Desktop's discovery keeps [row]. */
export function desktopKeeps(row: ModelRow): boolean {
  const id = row.id.toLowerCase();
  const readsAsClaude = !OTHER_VENDORS.test(id) && ["claude", ...TIERS, "anthropic"].some((w) => id.includes(w));
  return readsAsClaude;
}

/** A Claude account added to head `head` of splice, as a sign-in on the accounts page lands it: one owner-only folder
 *  in Claude Code's own file format. Written before the daemon starts. */
export function addClaudeAccount(s: Splice, head: string, label: string, token: string): void {
  const dir = join(s.state, "claude-accounts", head, label);
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  const far = Date.now() + 365 * 24 * 3600 * 1000;
  writeFileSync(
    join(dir, ".credentials.json"),
    JSON.stringify({ claudeAiOauth: { accessToken: token, refreshToken: `${token}-refresh`, expiresAt: far, scopes: ["user:inference", "user:profile"] } }),
    { mode: 0o600 },
  );
  writeFileSync(join(dir, ".claude.json"), JSON.stringify({ oauthAccount: { accountUuid: crypto.randomUUID(), emailAddress: `${label}@example.com` } }), { mode: 0o600 });
  writeFileSync(join(dir, ".splice-account.json"), JSON.stringify({ added_at: Date.now() }), { mode: 0o600 });
}

/** The head keyed `claude` on Anthropic's Messages API, forwarding the caller's login as the `claude` head does, with
 *  Claude's models declared and its tiers named. */
export function claudeHeadToml(o: { port: number; url: string }): string {
  return `
[providers.anthropic]
dialect = "anthropic-passthrough"
base_url = "${o.url}"
auth = { kind = "client" }
extra_headers = { anthropic-version = "2023-06-01" }

[[providers.anthropic.models]]
id = "claude-opus-5-5"
label = "Claude Opus 5.5"
context_window = 200000

[[providers.anthropic.models]]
id = "claude-haiku-5-5"
label = "Claude Haiku 5.5"
context_window = 200000

[heads.claude]
provider = "anthropic"
port = ${o.port}
discovery_prefix = "claude--"
pinned_model = "claude-opus-5-5"
model_slots = { opus = "claude-opus-5-5", sonnet = "claude-opus-5-5", haiku = "claude-haiku-5-5" }
[heads.claude.claude]
command = "claude"
`;
}

export interface FullDesk {
  on: boolean;
  mode?: string;
  relaunched?: boolean | null;
  picker_current?: boolean | null;
  door: string;
  default_model: string | null;
  models: { model: string; label: string; head: string; command: string }[];
  picker?: ModelRow[];
  left_out: { head: string; command: string; reason: string }[];
  last_turn: Record<string, unknown> | null;
  last_turns?: Record<string, Record<string, unknown> | null>;
  features?: { name: string; mode: string; state: string; reason?: string }[];
  [k: string]: unknown;
}

/** What `splice desktop --json` says. */
export function deskJson(s: Splice): FullDesk {
  const r = s.cli(["desktop", "--json"]);
  if (r.code !== 0) throw new Error(`splice desktop --json exited ${r.code}:\n${r.out}\n${r.err}`);
  return json<FullDesk>(r);
}
