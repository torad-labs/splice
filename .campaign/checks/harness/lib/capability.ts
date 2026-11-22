// What the harness checks stand on: a provider that speaks Anthropic's Messages API and records what it is sent, a
// worker outside splice that answers the way docs/specs/harness.md (decision 4) says an HTTP worker does, the
// splice.toml text of a head and of a capability, and a session's requests sent the way Claude Code sends them.

import { spawnSync } from "node:child_process";
import type { Splice } from "../../server/lib/harness.ts";
import type { Launched } from "../../speed/lib/session.ts";
import { sessionKey } from "../../speed/lib/session.ts";

type Server = ReturnType<typeof Bun.serve>;

export interface Block {
  type: string;
  text?: string;
  cache_control?: unknown;
  [k: string]: unknown;
}

export interface Message {
  role: string;
  content: string | Block[];
}

export interface SeenTurn {
  headers: Record<string, string>;
  body: { model?: string; system?: unknown; messages: Message[]; [k: string]: unknown };
  /** The request's body as it arrived, byte for byte. */
  raw: string;
  at: number;
}

/** A provider that speaks Anthropic's Messages API: it records each request and answers `ok from <name>`. */
export class Provider {
  readonly name: string;
  readonly turns: SeenTurn[] = [];
  private server: Server | null = null;

  constructor(name: string) {
    this.name = name;
  }

  start(): this {
    this.server = Bun.serve({ port: 0, hostname: "127.0.0.1", idleTimeout: 0, fetch: (req) => this.answer(req) });
    return this;
  }

  stop(): void {
    this.server?.stop(true);
  }

  get url(): string {
    return `http://127.0.0.1:${this.server!.port}`;
  }

  reply(): string {
    return `ok from ${this.name}`;
  }

  /** The requests of session `session`, oldest first. A head that calls its provider with a key of its own sends on
   *  none of Claude Code's headers, so the session is read where the body names it: `metadata.user_id`. */
  of(session: string): SeenTurn[] {
    return this.turns.filter((t) => sessionOf(t.body) === session);
  }

  private async answer(req: Request): Promise<Response> {
    const path = new URL(req.url).pathname;
    if (!path.endsWith("/v1/messages")) return Response.json({ type: "error", error: { type: "not_found_error", message: `no route ${path}` } }, { status: 404 });
    const raw = await req.text();
    const body = JSON.parse(raw) as SeenTurn["body"];
    this.turns.push({ headers: Object.fromEntries(req.headers), body, raw, at: Date.now() });
    const usage = { input_tokens: 12, cache_creation_input_tokens: 0, cache_read_input_tokens: 0, output_tokens: 4 };
    if (body.stream !== true) {
      return Response.json({ id: "msg_check", type: "message", role: "assistant", model: body.model, content: [{ type: "text", text: this.reply() }], stop_reason: "end_turn", stop_sequence: null, usage });
    }
    const frames = [
      { type: "message_start", message: { id: "msg_check", type: "message", role: "assistant", model: body.model, content: [], stop_reason: null, stop_sequence: null, usage: { ...usage, output_tokens: 1 } } },
      { type: "content_block_start", index: 0, content_block: { type: "text", text: "" } },
      { type: "content_block_delta", index: 0, delta: { type: "text_delta", text: this.reply() } },
      { type: "content_block_stop", index: 0 },
      { type: "message_delta", delta: { stop_reason: "end_turn", stop_sequence: null }, usage: { output_tokens: 4 } },
      { type: "message_stop" },
    ];
    const text = frames.map((f) => `event: ${f.type}\ndata: ${JSON.stringify(f)}\n\n`).join("");
    return new Response(text, { headers: { "content-type": "text/event-stream", "cache-control": "no-cache" } });
  }
}

/** The session a request's body names, the way Claude Code 2.1.326 names it: `metadata.user_id` is a JSON string with
 *  `device_id`, `account_uuid` and `session_id`. */
function sessionOf(body: SeenTurn["body"]): string | null {
  const id = (body.metadata as { user_id?: unknown } | undefined)?.user_id;
  if (typeof id !== "string") return null;
  try {
    return String((JSON.parse(id) as { session_id?: unknown }).session_id ?? "") || null;
  } catch {
    return null;
  }
}

/** How the worker answers its next requests. */
export interface Work {
  /** Milliseconds before the worker answers at all. */
  waitMs: number;
  /** The progress messages it sends first, `gapMs` apart. */
  progress: string[];
  gapMs: number;
  /** What it ends on: the text to place, a refusal's reason, a line that is none of the three, or nothing at all. */
  end: { text: string } | { refuse: string } | { line: string } | "nothing";
  /** Answer with this status and no lines instead. */
  status: number | null;
}

export interface Asked {
  headers: Record<string, string>;
  body: { capability?: string; session?: string; turn?: string; fields?: Record<string, unknown>; [k: string]: unknown };
  at: number;
}

/** A worker outside splice: an HTTP endpoint that records what it is posted and answers in JSON lines. `text` is
 *  what a result carries when `work.end` names none of its own; it is given the request, so a result can say whom it
 *  is for. */
export class Worker {
  readonly asked: Asked[] = [];
  /** The sessions whose request the caller dropped before the worker had answered it. */
  readonly dropped: string[] = [];
  work: Work = { waitMs: 0, progress: [], gapMs: 0, end: { text: "" }, status: null };
  text: (a: Asked) => string = (a) => `What the worker found for ${a.body.session ?? "nobody"}.\nsrc/main.ts:10-42 holds the entry point.`;
  private server: Server | null = null;

  start(): this {
    this.server = Bun.serve({ port: 0, hostname: "127.0.0.1", idleTimeout: 0, fetch: (req) => this.answer(req) });
    return this;
  }

  stop(): void {
    this.server?.stop(true);
  }

  get url(): string {
    return `http://127.0.0.1:${this.server!.port}/work`;
  }

  /** The requests made for session `session`. */
  of(session: string): Asked[] {
    return this.asked.filter((a) => a.body.session === session);
  }

  private async answer(req: Request): Promise<Response> {
    const asked: Asked = { headers: Object.fromEntries(req.headers), body: (await req.json()) as Asked["body"], at: Date.now() };
    this.asked.push(asked);
    const w = { ...this.work };
    let answered = false;
    req.signal.addEventListener("abort", () => {
      if (!answered) this.dropped.push(String(asked.body.session ?? ""));
    });
    if (w.waitMs) await Bun.sleep(w.waitMs);
    answered = true;
    if (w.status !== null) return new Response("no", { status: w.status });
    const text = this.text(asked);
    const enc = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      async start(c) {
        for (const message of w.progress) {
          c.enqueue(enc.encode(`${JSON.stringify({ type: "progress", message })}\n`));
          if (w.gapMs) await Bun.sleep(w.gapMs);
        }
        if (w.end !== "nothing") {
          if ("line" in w.end) c.enqueue(enc.encode(`${w.end.line}\n`));
          else if ("refuse" in w.end) c.enqueue(enc.encode(`${JSON.stringify({ type: "refusal", reason: w.end.refuse })}\n`));
          else c.enqueue(enc.encode(`${JSON.stringify({ type: "result", text: w.end.text || text })}\n`));
        }
        c.close();
      },
    });
    return new Response(stream, { headers: { "content-type": "application/x-ndjson" } });
  }
}

/** A head on a provider that speaks Anthropic's Messages API, called with an API key splice holds. */
export function head(o: { key: string; command: string; port: number; url: string; model?: string; small?: string }): string {
  const large = o.model ?? `${o.key}-large`;
  const model = (id: string) => `
[[providers.${o.key}.models]]
id = "${id}"
label = "${id}"
context_window = 200000
rates = { input = 2.0, cache_read = 0.5, output = 8.0 }
`;
  return `
[providers.${o.key}]
dialect = "anthropic-passthrough"
base_url = "${o.url}"
auth = { kind = "api-key", env = "MOCK_UPSTREAM_KEY" }
extra_headers = { anthropic-version = "2023-06-01" }
${o.small ? `model_slots = { opus = "${large}", sonnet = "${large}", haiku = "${o.small}" }` : ""}
${model(large)}${o.small ? model(o.small) : ""}
[heads.${o.key}]
provider = "${o.key}"
port = ${o.port}
discovery_prefix = "claude-${o.key}--"
pinned_model = "${large}"
[heads.${o.key}.claude]
command = "${o.command}"
`;
}

/** The environment a daemon with such heads needs: the provider's key. */
export const KEYS = { MOCK_UPSTREAM_KEY: "sk-check-upstream", WORKER_KEY: "wk-check-worker-key" };

export interface Declared {
  heads: string[];
  url: string;
  /** The rule, as TOML: `{ mode = "any" }`. */
  when?: string;
  place?: string;
  may?: string;
  message?: string;
  color?: string;
  timeout?: number;
  onFailure?: string;
  send?: string[];
  authEnv?: string;
  /** Lines added to the table as they are. */
  extra?: string;
}

/** A capability's table in splice.toml, with an HTTP worker at `url`. */
export function capability(name: string, o: Declared): string {
  const worker = `{ kind = "http", url = "${o.url}"${o.authEnv ? `, auth_env = "${o.authEnv}"` : ""} }`;
  return `
[capabilities.${name}]
heads = [${o.heads.map((h) => `"${h}"`).join(", ")}]
call = "rule"
${o.when ? `when = ${o.when}` : ""}
place = "${o.place ?? "first-user-message"}"
may = "${o.may ?? "add"}"
message = "${o.message ?? "Reading the request"}"
color = "${o.color ?? "cyan"}"
timeout_s = ${o.timeout ?? 30}
on_failure = "${o.onFailure ?? "open"}"
send = [${(o.send ?? ["first_user_message"]).map((f) => `"${f}"`).join(", ")}]
worker = ${worker}
${o.extra ?? ""}
`;
}

/** Claude Code's user agent in a terminal session, and in print mode. */
export const INTERACTIVE = "claude-cli/2.1.326 (external, cli)";
export const PRINT = "claude-cli/2.1.326 (external, sdk-cli)";

/** What a person's first message looks like on the wire: Claude Code's own reminder blocks, then what they typed, with
 *  a cache mark on the last block. */
export function opening(typed: string): Block[] {
  return [
    { type: "text", text: "<system-reminder>\nThe date is 2026-11-17.\n</system-reminder>" },
    { type: "text", text: typed, cache_control: { type: "ephemeral", ttl: "1h" } },
  ];
}

/** `blocks` as Claude Code sends them on a later turn, when its cache marks have moved off them. */
export function unmarked(blocks: Block[]): Block[] {
  return blocks.map(({ cache_control: _, ...rest }) => rest);
}

const DEVICE = "c0ffee".repeat(11).slice(0, 64);

const TOOLS = [{ name: "Read", description: "Read a file.", input_schema: { type: "object", properties: { file_path: { type: "string" } }, required: ["file_path"] } }];

export interface Sent {
  /** When the request was sent, on the wall clock. */
  sentAt: number;
  status: number;
  /** Milliseconds from sending to the response's headers. */
  openedMs: number;
  /** Each read of the body: when it arrived (ms from sending) and its text. */
  arrivals: { ms: number; text: string }[];
  body: string;
  /** The stream's error, when the turn ended on one. */
  error: { type: string; message: string } | null;
  totalMs: number;
}

export interface SendOptions {
  /** A subagent's request names its agent. */
  agent?: string;
  userAgent?: string;
  /** Side requests carry no tools. */
  tools?: boolean;
  stream?: boolean;
  system?: string;
  signal?: AbortSignal;
  model?: string;
}

/** One request of session `session` with `messages` as its history, sent as Claude Code sends a turn, read to its end. */
export async function send(l: Launched, session: string, messages: Message[], o: SendOptions = {}): Promise<Sent> {
  const headers: Record<string, string> = {
    "content-type": "application/json",
    "anthropic-version": "2023-06-01",
    "user-agent": o.userAgent ?? INTERACTIVE,
    "x-app": "cli",
    "x-api-key": sessionKey(l),
    "x-claude-code-session-id": session,
  };
  if (o.agent) headers["x-claude-code-agent-id"] = o.agent;
  const user = JSON.stringify({ device_id: DEVICE, account_uuid: "", session_id: session });
  const body: Record<string, unknown> = { model: o.model ?? l.sets.ANTHROPIC_MODEL, max_tokens: 1024, stream: o.stream ?? true, metadata: { user_id: user }, messages };
  if (o.tools ?? true) body.tools = TOOLS;
  if (o.system) body.system = o.system;
  const sentAt = Date.now();
  const started = performance.now();
  const r = await fetch(`${l.sets.ANTHROPIC_BASE_URL}/v1/messages`, { method: "POST", headers, body: JSON.stringify(body), signal: o.signal });
  const openedMs = performance.now() - started;
  const arrivals: Sent["arrivals"] = [];
  const dec = new TextDecoder();
  const reader = r.body!.getReader();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    arrivals.push({ ms: performance.now() - started, text: dec.decode(value, { stream: true }) });
  }
  const text = arrivals.map((a) => a.text).join("");
  return { sentAt, status: r.status, openedMs, arrivals, body: text, error: errorOf(text), totalMs: performance.now() - started };
}

function errorOf(body: string): Sent["error"] {
  for (const block of body.split(/\r?\n\r?\n/)) {
    const data = block.split(/\r?\n/).find((line) => line.startsWith("data:"));
    const text = data ? data.slice(5).trim() : block.trim();
    if (!text.startsWith("{")) continue;
    try {
      const parsed = JSON.parse(text) as { type?: string; error?: { type?: string; message?: string } };
      if (parsed.type === "error" && parsed.error) return { type: String(parsed.error.type ?? ""), message: String(parsed.error.message ?? "") };
    } catch {}
  }
  return null;
}

/** The first turn of a new session on `l`: what the person typed, as Claude Code sends it. */
export async function first(l: Launched, typed: string, o: SendOptions = {}): Promise<{ session: string; mine: Block[]; sent: Sent }> {
  const session = crypto.randomUUID();
  const mine = opening(typed);
  return { session, mine, sent: await send(l, session, [{ role: "user", content: mine }], o) };
}

/** The session's history on its second turn: its first message with Claude Code's cache marks moved off it, the
 *  answer, and what the person typed next. */
export function second(mine: Block[], next = "And after that?"): Message[] {
  return [
    { role: "user", content: unmarked(mine) },
    { role: "assistant", content: [{ type: "text", text: "Done." }] },
    { role: "user", content: [{ type: "text", text: next, cache_control: { type: "ephemeral", ttl: "1h" } }] },
  ];
}

/** What `provider`, behind a head `l` no capability is switched on for, is sent for `messages`: the request as it is
 *  today. */
export async function today(l: Launched, provider: Provider, messages: Message[], o: SendOptions = {}): Promise<Message[]> {
  const session = crypto.randomUUID();
  await send(l, session, messages, o);
  return provider.of(session).at(-1)!.body.messages;
}

/** A worker that answers at once with its own text. */
export const IDLE: Work = { waitMs: 0, progress: [], gapMs: 0, end: { text: "" }, status: null };

/** The records of session `session`'s turns on head `key`, oldest first, as the perf route answers them. `n` is how
 *  many to wait for: a turn's record is written once the turn has ended. */
export async function rows(s: Splice, key: string, session: string, n = 1): Promise<Record<string, unknown>[]> {
  let mine: Record<string, unknown>[] = [];
  for (let i = 0; i < 100; i++) {
    const j = (await (await s.api(`/api/perf/turns?head=${key}`)).json()) as { heads: { key: string; rows: Record<string, unknown>[] }[] };
    mine = (j.heads.find((h) => h.key === key)?.rows ?? []).filter((r) => r.session_id === session);
    if (mine.length >= n) return mine;
    await Bun.sleep(200);
  }
  throw new Error(`${mine.length} of ${n} records for session ${session} on ${key}`);
}

/** A message's content as blocks, whichever way it was written. */
export function blocks(m: Message | undefined): Block[] {
  if (!m) return [];
  return typeof m.content === "string" ? [{ type: "text", text: m.content }] : m.content;
}

/** The session's status line as Claude Code draws it: its own command, run with a tick on its input, colours taken
 *  out. */
export function statusLine(l: Launched, session: string): string {
  const command = String((l.settings.statusLine as { command?: string } | undefined)?.command ?? "");
  if (!command) throw new Error("the session's settings name no status line command");
  const tick = JSON.stringify({ session_id: session, model: { id: l.sets.ANTHROPIC_MODEL, display_name: l.sets.ANTHROPIC_MODEL }, workspace: { current_dir: l.cwd }, cwd: l.cwd });
  const r = spawnSync("bash", ["-c", command], { input: tick, env: l.env, cwd: l.cwd, encoding: "utf8" });
  // biome-ignore lint/suspicious/noControlCharactersInRegex: the escape that starts a terminal colour
  return `${r.stdout ?? ""}`.replace(/\u001b\[[0-9;]*m/g, "");
}
