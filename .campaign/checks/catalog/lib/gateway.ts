// What the head-catalog checks share: a launch of any command read the way Claude Code reads it in gateway mode (the
// base URL it is pointed at, the credential it sends, whether it asks that address for its models, and every model
// list splice hands it, in the launch's `--settings` or in the Claude folder it runs in), the gateway's model list as
// Claude Code asks for it, and a turn sent the way Claude Code sends one to a gateway.

import { spawn } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { type Splice, type Turn, turn } from "../../server/lib/harness.ts";

export interface Launch {
  command: string;
  argv: string[];
  /** What the launch sets, and what it takes out of the environment it inherits. */
  sets: Record<string, string>;
  unset: string[];
  /** The notice the shim prints before Claude Code starts, or "". */
  warning: string;
  /** The `--settings` overlays of the launch, merged in order. */
  overlay: Record<string, unknown>;
  /** The Claude folder the session runs in. */
  dir: string;
}

/** Every value `flag` is given in `argv`, as `--flag value` or `--flag=value`. */
function flagValues(argv: string[], flag: string): string[] {
  const out: string[] = [];
  argv.forEach((a, i) => {
    if (a === flag && argv[i + 1] !== undefined) out.push(argv[i + 1]!);
    else if (a.startsWith(`${flag}=`)) out.push(a.slice(flag.length + 1));
  });
  return out;
}

/** Launch `command` as the shim does (`POST /launch/<command>`). `launch` is the pid the shim runs as, which Claude
 *  Code then runs as; `inheritedConfigDir` a CLAUDE_CONFIG_DIR the person's shell already holds. */
export async function launchOf(s: Splice, command: string, o: { launch?: number; inheritedConfigDir?: string } = {}): Promise<Launch> {
  const form = new URLSearchParams({ dangerouslySkipPermissions: "false", cwd: s.home, launch: String(o.launch ?? process.pid) });
  if (o.inheritedConfigDir) form.set("inheritedConfigDir", o.inheritedConfigDir);
  const r = await s.api(`/launch/${command}`, { method: "POST", body: form.toString(), headers: { "content-type": "application/x-www-form-urlencoded" } });
  const text = await r.text();
  if (r.status !== 200) throw new Error(`launching ${command} answered ${r.status}: ${text}`);
  const recipe = JSON.parse(text) as { argv: string[]; env: Record<string, string>; unset: string[]; warning?: string };
  const overlay: Record<string, unknown> = {};
  for (const v of flagValues(recipe.argv, "--settings")) {
    Object.assign(overlay, JSON.parse(v.trimStart().startsWith("{") ? v : readFileSync(v, "utf8")) as Record<string, unknown>);
  }
  const dir = recipe.env.CLAUDE_CONFIG_DIR ?? o.inheritedConfigDir ?? join(s.home, ".claude");
  return { command, argv: recipe.argv, sets: recipe.env, unset: recipe.unset, warning: String(recipe.warning ?? ""), overlay, dir };
}

/** A JSON file, or {} when it is not there. */
export function jsonFile(file: string): Record<string, unknown> {
  return existsSync(file) ? (JSON.parse(readFileSync(file, "utf8")) as Record<string, unknown>) : {};
}

/** Claude Code's provider switches: any one of them set turns gateway discovery off. */
export const SWITCHES = [
  "CLAUDE_CODE_USE_MANTLE",
  "CLAUDE_CODE_USE_BEDROCK",
  "CLAUDE_CODE_USE_VERTEX",
  "CLAUDE_CODE_USE_FOUNDRY",
];

/** The settings keys and variables by which a list of models reaches Claude Code's `/model` from a settings file or
 *  the environment, beside or in place of what discovery finds. */
const LIST_KEYS = ["availableModels", "modelPicker", "enforceAvailableModels"];
const LIST_VARS = [
  "ANTHROPIC_CUSTOM_MODEL_OPTION",
  "ANTHROPIC_DEFAULT_OPUS_MODEL",
  "ANTHROPIC_DEFAULT_SONNET_MODEL",
  "ANTHROPIC_DEFAULT_HAIKU_MODEL",
  "ANTHROPIC_DEFAULT_FABLE_MODEL",
  "ANTHROPIC_SMALL_FAST_MODEL",
];

/** Every model list splice hands a session: in the launch's overlay, in the launch's environment, and in the settings
 *  file and `.claude.json` of a Claude folder splice set for it (not the person's own). Each named by where it is. */
export function listsOf(x: Launch, s: Splice): string[] {
  const found: string[] = [];
  for (const k of LIST_KEYS) if (k in x.overlay) found.push(`--settings ${k}`);
  for (const v of LIST_VARS) if (x.sets[v] !== undefined) found.push(`env ${v}`);
  const env = (x.overlay.env ?? {}) as Record<string, unknown>;
  // #791: a door launch's overlay says each of these empty, which clears a list a settings file lays and lays none
  for (const v of LIST_VARS) if (v in env && env[v] !== "") found.push(`--settings env.${v}`);
  if (x.dir !== join(s.home, ".claude")) {
    const settings = jsonFile(join(x.dir, "settings.json"));
    for (const k of LIST_KEYS) if (k in settings) found.push(`${x.dir}/settings.json ${k}`);
    const fileEnv = (settings.env ?? {}) as Record<string, unknown>;
    for (const v of LIST_VARS) if (v in fileEnv) found.push(`${x.dir}/settings.json env.${v}`);
    if ("additionalModelOptionsCache" in jsonFile(join(x.dir, ".claude.json"))) found.push(`${x.dir}/.claude.json additionalModelOptionsCache`);
  }
  return found;
}

/** Where a gateway-mode session is pointed and the credential it sends: null when the launch is not in gateway mode
 *  (a base URL, a bearer, discovery on, and no provider switch). */
export function gatewayOf(x: Launch): { base: string; key: string } | null {
  const base = x.sets.ANTHROPIC_BASE_URL;
  const key = x.sets.ANTHROPIC_AUTH_TOKEN;
  if (!base || !key || x.sets.CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY !== "1") return null;
  if (SWITCHES.some((v) => x.sets[v] !== undefined && x.sets[v] !== "" && x.sets[v] !== "0")) return null;
  return { base, key };
}

/** The gateway of a launch that must be in gateway mode. */
export function mustGateway(x: Launch): { base: string; key: string } {
  const g = gatewayOf(x);
  if (!g) throw new Error(`the launch of ${x.command} is not in gateway mode: ${JSON.stringify(x.sets)}`);
  return g;
}

export interface ModelRow {
  id: string;
  display_name?: string;
  description?: string;
}

export interface Listed {
  status: number;
  ms: number;
  redirected: boolean;
  rows: ModelRow[];
  body: string;
}

/** `GET <base>/v1/models?limit=1000` as Claude Code's discovery asks it: the key as a bearer, no redirect followed. */
export async function modelsOf(base: string, key: string): Promise<Listed> {
  const t0 = performance.now();
  const r = await fetch(`${base}/v1/models?limit=1000`, {
    headers: { authorization: `Bearer ${key}`, "anthropic-version": "2023-06-01" },
    redirect: "manual",
  });
  const body = await r.text();
  const ms = performance.now() - t0;
  let rows: ModelRow[] = [];
  try {
    rows = ((JSON.parse(body) as { data?: ModelRow[] }).data ?? []) as ModelRow[];
  } catch {}
  return { status: r.status, ms, redirected: r.status >= 300 && r.status < 400, rows, body };
}

/** What Claude Code keeps of a discovery answer: ids that contain `claude` or `anthropic`, any case. */
export function kept(rows: ModelRow[]): ModelRow[] {
  return rows.filter((r) => /claude|anthropic/i.test(r.id));
}

/** One turn of a gateway-mode session naming `model`, as Claude Code sends it: to `/v1/messages?beta=true`, the key
 *  as a bearer, the session's id, and a subagent's id when a subagent sends it. */
export function gatewayTurn(g: { base: string; key: string }, model: string, o: { session?: string; agent?: string; content?: string; stream?: boolean } = {}): Promise<Turn> {
  const headers: Record<string, string> = {
    "user-agent": "claude-cli/2.1.326 (external, cli)",
    "x-app": "cli",
    "x-claude-code-session-id": o.session ?? crypto.randomUUID(),
  };
  if (o.agent) headers["x-claude-code-agent-id"] = o.agent;
  return turn(`${g.base}/v1/messages?beta=true`, { key: g.key, bearer: true, model, stream: o.stream, content: o.content, headers });
}

/** Whether any header or body a provider saw carries `secret`. */
export function carries(seen: { headers: Record<string, string>; body?: unknown }[], secret: string): boolean {
  return seen.some((x) => Object.values(x.headers).some((v) => String(v).includes(secret)) || JSON.stringify(x.body ?? "").includes(secret));
}

/** A process that stands for a running Claude Code session: alive until stopped. */
export function stand(): { pid: number; stop: () => void } {
  const child = spawn("sleep", ["600"], { stdio: "ignore", detached: true });
  return { pid: child.pid!, stop: () => child.kill("SIGKILL") };
}

/** Session `session` registered as Claude Code registers a running one, by the pid it runs as, in the Claude folder
 *  `dir`'s `sessions/` (every head's folder links to the person's own). */
export function register(dir: string, pid: number, session: string, cwd: string): void {
  mkdirSync(join(dir, "sessions"), { recursive: true });
  writeFileSync(join(dir, "sessions", `${pid}.json`), JSON.stringify({ pid, sessionId: session, cwd, startedAt: Date.now(), kind: "interactive", entrypoint: "cli" }));
}
