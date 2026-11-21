// A `claude` session on its head, as Claude Code runs one with the person's own Claude login: the head as a person
// on a Claude plan has it, what the session's `/model` lists after Claude Code's own models, and a turn sent to the
// head's address with that login. (docs/specs/door-default.md, decision 9.)

import type { Session } from "./claude.ts";

/** The head keyed `claude` as a person on a Claude plan has it: it forwards the caller's own login to Anthropic,
 *  pins no model and lists none, so Claude Code's own lineup and the person's own default are what a session has. */
export function claudeHead(o: { port: number; url: string }): string {
  return `
[providers.anthropic]
dialect = "anthropic-passthrough"
base_url = "${o.url}"
auth = { kind = "client" }
extra_headers = { anthropic-version = "2023-06-01" }

[heads.claude]
provider = "anthropic"
port = ${o.port}
discovery_prefix = "claude--"
[heads.claude.claude]
command = "claude"
`;
}

/** The login the caller's Claude Code holds, as it sends it. No provider but Anthropic may ever see it. */
export const CALLER_TOKEN = "sk-ant-oat01-the-callers-own-claude-login";

export interface PickerRow {
  model: string;
  label?: string;
  description?: string;
}

/** The rows the launch adds to `/model` after Claude Code's own, or null when it adds none. A launch whose picker
 *  takes the place of Claude Code's own models is not this lane's. */
export function added(x: Session): PickerRow[] | null {
  const p = x.overlay.modelPicker as { replaceBuiltInOptions?: boolean; options?: PickerRow[] } | undefined;
  if (!p?.options?.length) return null;
  if (p.replaceBuiltInOptions === true) throw new Error("the launch's picker replaces Claude Code's own models");
  return p.options;
}

export interface Answer {
  status: number;
  body: string;
  /** The answer's text: a stream's text deltas, or a JSON answer's text blocks. */
  text: string;
  /** The error envelope's message, when the answer is one. */
  error: string | null;
  /** The input the answer reports to Claude Code, which it sets against its window: input tokens and both cache
   *  counts, from a JSON answer's `usage` or the largest a stream's events report. */
  reported: number;
}

/** The headers Claude Code sends a head that forwards its own login: the login as a Bearer, its own marks, the
 *  session's ID, and every header the launch asks it to add. */
function headers(x: Session, session: string): Record<string, string> {
  const h: Record<string, string> = {
    "content-type": "application/json",
    "anthropic-version": "2023-06-01",
    "anthropic-beta": "oauth-2025-04-20",
    authorization: `Bearer ${CALLER_TOKEN}`,
    "user-agent": "claude-cli/2.1.326 (external, cli)",
    "x-app": "cli",
    "x-claude-code-session-id": session,
  };
  for (const line of (x.env.ANTHROPIC_CUSTOM_HEADERS ?? "").split("\n")) {
    const at = line.indexOf(":");
    if (at > 0) h[line.slice(0, at).trim().toLowerCase()] = line.slice(at + 1).trim();
  }
  return h;
}

/** Where the launch sends the session's requests. */
function base(x: Session): string {
  const url = x.sets.ANTHROPIC_BASE_URL;
  if (!url) throw new Error("the launch names no ANTHROPIC_BASE_URL: this session is not on a head");
  return url;
}

function sum(u: unknown): number {
  const v = (u ?? {}) as Record<string, unknown>;
  return Number(v.input_tokens ?? 0) + Number(v.cache_creation_input_tokens ?? 0) + Number(v.cache_read_input_tokens ?? 0);
}

export interface TurnOptions {
  session?: string;
  /** The conversation so far, ending in the user's message. Left out, it is one message carrying `say`. */
  messages?: unknown[];
  say?: string;
  stream?: boolean;
}

/** One turn of the session naming `model`, sent as Claude Code sends it on its head. */
export async function turn(x: Session, model: string, o: TurnOptions = {}): Promise<Answer> {
  const body = {
    model,
    max_tokens: 1024,
    stream: o.stream ?? true,
    tools: [{ name: "Read", description: "Read a file.", input_schema: { type: "object", properties: { file_path: { type: "string" } } } }],
    messages: o.messages ?? [{ role: "user", content: o.say ?? "Say OK." }],
  };
  const r = await fetch(`${base(x)}/v1/messages?beta=true`, { method: "POST", headers: headers(x, o.session ?? crypto.randomUUID()), body: JSON.stringify(body) });
  const raw = await r.text();
  let text = "";
  let error: string | null = null;
  let reported = 0;
  const read = (j: Record<string, unknown>) => {
    const d = j as { type?: string; delta?: { type?: string; text?: string }; message?: { usage?: unknown }; usage?: unknown; content?: { type: string; text?: string }[]; error?: { message?: string } };
    if (d.type === "content_block_delta" && d.delta?.type === "text_delta") text += d.delta.text ?? "";
    if (d.type === "message") text += (d.content ?? []).filter((b) => b.type === "text").map((b) => b.text ?? "").join("");
    if (d.type === "error") error = String(d.error?.message ?? "");
    reported = Math.max(reported, sum(d.message?.usage), sum(d.usage));
  };
  if ((r.headers.get("content-type") ?? "").includes("event-stream")) {
    for (const block of raw.split(/\r?\n\r?\n/)) {
      const data = block.split(/\r?\n/).filter((l) => l.startsWith("data:")).map((l) => l.slice(5).trim()).join("\n");
      if (!data) continue;
      try {
        read(JSON.parse(data) as Record<string, unknown>);
      } catch {
        // a frame that is no JSON carries nothing this reads
      }
    }
  } else {
    try {
      read(JSON.parse(raw) as Record<string, unknown>);
    } catch {
      // not JSON: the caller reads the status and the body
    }
  }
  return { status: r.status, body: raw, text, error, reported };
}

/** A `count_tokens` request of the session naming `model`, as `/context` sends it. */
export async function count(x: Session, model: string, content: string): Promise<{ status: number; input_tokens: number }> {
  const r = await fetch(`${base(x)}/v1/messages/count_tokens?beta=true`, {
    method: "POST",
    headers: headers(x, crypto.randomUUID()),
    body: JSON.stringify({ model, messages: [{ role: "user", content }] }),
  });
  const j = (await r.json().catch(() => null)) as { input_tokens?: number } | null;
  return { status: r.status, input_tokens: Number(j?.input_tokens ?? Number.NaN) };
}

/** Whether any header of what a provider saw carries the caller's Claude login. */
export function carriesLogin(seen: { headers: Record<string, string> }[]): boolean {
  return seen.some((s) => Object.values(s.headers).some((v) => v.includes(CALLER_TOKEN)));
}
