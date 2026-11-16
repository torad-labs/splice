// What a Claude Code session launched on a head does, without Claude Code: launch the head the way the
// `splice-launch` shim does, run its UserPromptSubmit hooks on what the person types, read the skills it offers, and
// send its turns with the key its `apiKeyHelper` hands out.

import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import type { Splice } from "../../server/lib/harness.ts";

export interface Launched {
  /** The environment Claude Code runs in: the recipe's sets over this process's, its unsets removed. */
  env: Record<string, string>;
  /** The recipe's own sets, as the daemon answered them. */
  sets: Record<string, string>;
  dir: string;
  settings: Record<string, unknown>;
  cwd: string;
}

/** Launch `command` as the shim does (`POST /launch/<command>`), and read what it laid down. */
export async function launch(s: Splice, command: string): Promise<Launched> {
  const form = new URLSearchParams({ dangerouslySkipPermissions: "false", cwd: s.home, launch: String(process.pid) });
  const r = await s.api(`/launch/${command}`, { method: "POST", body: form.toString(), headers: { "content-type": "application/x-www-form-urlencoded" } });
  const text = await r.text();
  if (r.status !== 200) throw new Error(`launching ${command} answered ${r.status}: ${text}`);
  const recipe = JSON.parse(text) as { env: Record<string, string>; unset: string[] };
  const env = { ...(process.env as Record<string, string>), ...recipe.env };
  for (const u of recipe.unset) delete env[u];
  const dir = recipe.env.CLAUDE_CONFIG_DIR;
  if (!dir) throw new Error(`the launch of ${command} named no CLAUDE_CONFIG_DIR`);
  const settings = JSON.parse(readFileSync(join(dir, "settings.json"), "utf8")) as Record<string, unknown>;
  return { env, sets: recipe.env, dir, settings, cwd: s.home };
}

export interface Skill {
  meta: Record<string, string>;
  body: string;
}

/** The skill `/name` runs in this session, read from the session's own skills folder, or null when it has none. */
export function skill(l: Launched, name: string): Skill | null {
  const file = join(l.dir, "skills", name, "SKILL.md");
  if (!existsSync(file)) return null;
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

/** The text Claude Code submits when the person runs `/name args`: the skill's body with its arguments in place. */
export function expanded(sk: Skill, args: string): string {
  return sk.body.replaceAll("$ARGUMENTS", args).trim();
}

export interface Said {
  /** Whether a hook stopped the prompt before it reached the model. */
  blocked: boolean;
  /** What the person is shown when it was stopped. */
  reason: string;
  /** What a hook added to the model's context, when it let the prompt through. */
  context: string;
}

/** The person types `text` in session `session`: every UserPromptSubmit hook runs on it, as Claude Code runs them. */
export function prompt(l: Launched, session: string, text: string): Said {
  const hooks = (l.settings.hooks as Record<string, { hooks: { type: string; command: string; timeout?: number }[] }[]> | undefined)?.UserPromptSubmit ?? [];
  const input = JSON.stringify({
    session_id: session,
    transcript_path: join(l.dir, "projects", "check", `${session}.jsonl`),
    cwd: l.cwd,
    permission_mode: "default",
    hook_event_name: "UserPromptSubmit",
    prompt: text,
  });
  const said: Said = { blocked: false, reason: "", context: "" };
  for (const group of hooks) {
    for (const h of group.hooks) {
      if (h.type !== "command") continue;
      const r = spawnSync("bash", ["-c", h.command], {
        cwd: l.cwd,
        env: { ...l.env, CLAUDE_PROJECT_DIR: l.cwd },
        input,
        encoding: "utf8",
        timeout: (h.timeout ?? 60) * 1000,
      });
      if (r.status === 2) {
        said.blocked = true;
        said.reason += r.stderr;
        continue;
      }
      const out = (r.stdout ?? "").trim();
      if (!out) continue;
      try {
        const j = JSON.parse(out) as { decision?: string; reason?: string; hookSpecificOutput?: { additionalContext?: string } };
        if (j.decision === "block") {
          said.blocked = true;
          said.reason += j.reason ?? "";
        }
        said.context += j.hookSpecificOutput?.additionalContext ?? "";
      } catch {
        said.context += out;
      }
    }
  }
  return said;
}

/** The key the session's `apiKeyHelper` hands Claude Code. */
export function sessionKey(l: Launched): string {
  const helper = String(l.settings.apiKeyHelper ?? "");
  if (!helper) throw new Error("the session's settings name no apiKeyHelper");
  const r = spawnSync("bash", ["-c", helper], { cwd: l.cwd, env: l.env, encoding: "utf8" });
  const key = (r.stdout ?? "").trim();
  if (!key) throw new Error(`the apiKeyHelper gave no key: ${r.stderr}`);
  return key;
}

export interface AskOptions {
  /** A subagent's turn: Claude Code names the subagent in its own header. */
  agent?: string;
  /** The request Claude Code sends to compact the conversation. */
  compaction?: boolean;
  stream?: boolean;
}

/** One turn of session `session`, carrying `marker` so the backend's record of it can be found, sent as Claude Code
 *  sends it. Answers the status and the body read to its end. */
export async function ask(l: Launched, session: string, marker: string, o: AskOptions = {}): Promise<{ status: number; body: string }> {
  const headers: Record<string, string> = {
    "content-type": "application/json",
    "anthropic-version": "2023-06-01",
    "x-api-key": sessionKey(l),
    "x-claude-code-session-id": session,
  };
  if (o.agent) headers["x-claude-code-agent-id"] = o.agent;
  const content = o.compaction
    ? `Your task is to create a detailed summary of the conversation so far, paying close attention to the user's explicit requests and your previous actions. ${marker}`
    : `Say OK. ${marker}`;
  const body: Record<string, unknown> = {
    model: l.sets.ANTHROPIC_MODEL,
    max_tokens: 1024,
    stream: o.stream ?? true,
    messages: [
      { role: "user", content: "Earlier question." },
      { role: "assistant", content: "Earlier answer." },
      { role: "user", content },
    ],
  };
  if (o.compaction) body.system = "You are a helpful AI assistant tasked with summarizing conversations.";
  const r = await fetch(`${l.sets.ANTHROPIC_BASE_URL}/v1/messages`, { method: "POST", headers, body: JSON.stringify(body) });
  return { status: r.status, body: await r.text() };
}
