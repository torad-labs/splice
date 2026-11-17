// What the door-default checks share (docs/specs/door-default.md): the lane `claude` is on as `splice claude` says
// it, a launch of `claude` read the way Claude Code reads it (its environment, the settings laid over the person's
// own for this run, the plugin folders it is handed), the slash commands that launch offers, and a desk whose
// browser records what it is asked to open.

import { chmodSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { type Recipe, doorTurn, recipe } from "../../desktop/lib/desktop.ts";
import { type Splice, type Turn, jar, json } from "../../server/lib/harness.ts";
import type { Launched, Skill } from "../../speed/lib/session.ts";

/** The variables that put Claude Code on the door. */
export const DOOR_VARS = ["CLAUDE_CODE_USE_MANTLE", "ANTHROPIC_BEDROCK_MANTLE_BASE_URL", "AWS_BEARER_TOKEN_BEDROCK"];

/** What would send a session somewhere other than the door. */
export const ELSEWHERE = [
  "ANTHROPIC_BASE_URL",
  "ANTHROPIC_API_KEY",
  "ANTHROPIC_AUTH_TOKEN",
  "CLAUDE_CODE_USE_BEDROCK",
  "CLAUDE_CODE_USE_VERTEX",
  "CLAUDE_CODE_USE_FOUNDRY",
];

export interface Row {
  model: string;
  label: string;
  head: string;
  command: string;
}

export interface Lane {
  lane: "door" | "head";
  door?: string;
  default_model?: string;
  models?: Row[];
  left_out?: { head: string; command: string; reason: string }[];
}

/** What `splice claude --json` says. */
export function lane(s: Splice): Lane {
  const r = s.cli(["claude", "--json"]);
  if (r.code !== 0) throw new Error(`splice claude --json exited ${r.code}:\n${r.out}\n${r.err}`);
  return json<Lane>(r);
}

/** `splice claude door`, which must take. */
export function toDoor(s: Splice, ...args: string[]): string {
  const r = s.cli(["claude", "door", ...args]);
  if (r.code !== 0) throw new Error(`splice claude door exited ${r.code}:\n${r.out}\n${r.err}`);
  return `${r.out}\n${r.err}`;
}

/** Every value `flag` is given in `argv`, as `--flag value` or `--flag=value`. */
function flagValues(argv: string[], flags: string[]): string[] {
  const out: string[] = [];
  argv.forEach((a, i) => {
    for (const f of flags) {
      if (a === f && argv[i + 1] !== undefined) out.push(argv[i + 1]!);
      else if (a.startsWith(`${f}=`)) out.push(a.slice(f.length + 1));
    }
  });
  return out;
}

type Hooks = Record<string, { matcher?: string; hooks: { type: string; command: string; timeout?: number }[] }[]>;

/** `from`'s hooks added to `into`'s, event by event, as Claude Code runs every source's hooks. */
function addHooks(into: Hooks, from: unknown, root?: string): void {
  for (const [event, groups] of Object.entries((from ?? {}) as Hooks)) {
    const mine = (into[event] ??= []);
    for (const g of groups) {
      mine.push({ ...g, hooks: g.hooks.map((h) => ({ ...h, command: root ? h.command.replaceAll("${CLAUDE_PLUGIN_ROOT}", root) : h.command })) });
    }
  }
}

export interface Session extends Launched {
  recipe: Recipe;
  /** The door as the launch names it: where Claude Code sends its turns, and the key it sends. */
  url: string;
  key: string;
  /** The settings the launch lays over the person's own for this run (`--settings`), merged in order. */
  overlay: Record<string, unknown>;
  /** The plugin folders the launch hands Claude Code. */
  plugins: string[];
}

/** Launch `command` as the shim does, and read what Claude Code would be started with. */
export async function session(s: Splice, command = "claude"): Promise<Session> {
  const r = await recipe(s, command);
  const overlay: Record<string, unknown> = {};
  const hooks: Hooks = {};
  for (const v of flagValues(r.argv, ["--settings"])) {
    const one = JSON.parse(v.trimStart().startsWith("{") ? v : readFileSync(v, "utf8")) as Record<string, unknown>;
    addHooks(hooks, one.hooks);
    Object.assign(overlay, one);
  }
  const dir = r.env.CLAUDE_CONFIG_DIR ?? join(s.home, ".claude");
  const own = join(dir, "settings.json");
  if (r.env.CLAUDE_CONFIG_DIR && existsSync(own)) addHooks(hooks, (JSON.parse(readFileSync(own, "utf8")) as { hooks?: unknown }).hooks);
  const plugins = flagValues(r.argv, ["--plugin-dir", "--plugin-dir-no-mcp"]);
  for (const p of plugins) {
    const file = join(p, "hooks", "hooks.json");
    if (existsSync(file)) addHooks(hooks, (JSON.parse(readFileSync(file, "utf8")) as { hooks?: unknown }).hooks, p);
  }
  const env = { ...s.env(), ...r.env };
  for (const u of r.unset) delete env[u];
  return {
    recipe: r,
    url: r.env.ANTHROPIC_BEDROCK_MANTLE_BASE_URL ?? "",
    key: r.env.AWS_BEARER_TOKEN_BEDROCK ?? "",
    overlay,
    plugins,
    env,
    sets: r.env,
    dir,
    settings: { ...overlay, hooks },
    cwd: s.home,
  };
}

/** The rows the session's `/model` lists in place of Claude Code's own, or null when the launch leaves the picker
 *  to Claude Code. */
export function picker(x: Session): { model: string; label?: string; description?: string }[] | null {
  const p = x.overlay.modelPicker as { replaceBuiltInOptions?: boolean; options?: { model: string; label?: string; description?: string }[] } | undefined;
  if (!p?.options?.length) return null;
  if (p.replaceBuiltInOptions !== true) throw new Error("the launch's picker keeps Claude Code's own models beside its rows");
  return p.options;
}

function read(file: string): Skill {
  const text = readFileSync(file, "utf8");
  const m = /^---\n([\s\S]*?)\n---\n?([\s\S]*)$/.exec(text);
  if (!m) return { meta: {}, body: text };
  const meta: Record<string, string> = {};
  for (const line of (m[1] ?? "").split("\n")) {
    const i = line.indexOf(":");
    if (i > 0) meta[line.slice(0, i).trim()] = line.slice(i + 1).trim().replace(/^["']|["']$/g, "");
  }
  return { meta, body: m[2] ?? "" };
}

/** The command `/name` runs in this session: from a plugin folder the launch hands Claude Code, or from the
 *  session's own folder. Null when the session has none. */
export function slash(x: Session, name: string): Skill | null {
  for (const base of [...x.plugins, x.dir]) {
    for (const file of [join(base, "commands", `${name}.md`), join(base, "skills", name, "SKILL.md")]) {
      if (existsSync(file)) return read(file);
    }
  }
  return null;
}

/** Every file under `dir`, by its path from it; none when there is no such folder. */
export function under(dir: string): string[] {
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

export interface Desk {
  /** What to put first on a session's PATH: `splice`, and the desktop's opener. */
  bin: string;
  /** What the browser was asked to open, in order. */
  opened(): string[];
}

/** A desk for `s`: `splice` on the PATH as an install puts it, and a browser that records what it is asked to open
 *  in place of opening it. */
export function desk(s: Splice): Desk {
  const bin = join(s.home, "desk-bin");
  const log = join(s.home, "desk-opened.txt");
  mkdirSync(bin, { recursive: true });
  const put = (name: string, body: string) => {
    writeFileSync(join(bin, name), `#!/bin/sh\n${body}\n`);
    chmodSync(join(bin, name), 0o755);
  };
  put("splice", `exec java -Xmx1g -Duser.home='${s.home}' -jar '${jar()}' "$@"`);
  for (const opener of ["xdg-open", "open"]) put(opener, `printf '%s\\n' "$1" >> '${log}'`);
  return { bin, opened: () => (existsSync(log) ? readFileSync(log, "utf8").split("\n").filter(Boolean) : []) };
}

/** A turn through the door with the launch's own address and key, as the session's Claude Code sends it. */
export function ask(x: Session, o: { model?: string; session?: string; content?: string } = {}): Promise<Turn> {
  return doorTurn(x.url, { key: x.key, model: o.model, session: o.session ?? crypto.randomUUID(), content: o.content });
}
