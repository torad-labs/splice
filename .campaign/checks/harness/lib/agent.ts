// What the agent's-call checks stand on (docs/specs/harness.md, decision 12): a provider that speaks Anthropic's
// Messages API and answers from a script (thinking blocks with their signatures, text, and tool calls, the
// capability's and the client's), refuses what the real API refuses where these checks can say why, and records every
// request byte for byte; what a client assembles from the stream it is sent; and a capability's table with `call` set
// to "agent" or "both" and the tool it offers.

import { createHash } from "node:crypto";
import type { Block, Message, SeenTurn } from "./capability.ts";

type Server = ReturnType<typeof Bun.serve>;

/** The tool the advisor capability offers the model, as its declaration names it. */
export const ADVISOR = {
  name: "ask_advisor",
  description: "Ask a second model for a review of a plan.",
  input: { type: "object", properties: { question: { type: "string" } }, required: ["question"] },
} as const;

export interface Tool {
  name: string;
  description: string;
  input: Record<string, unknown>;
}

/** A value written as a TOML inline value on one line (TOML 1.0 has no line breaks inside an inline table). */
function toml(v: unknown): string {
  if (typeof v === "string") return JSON.stringify(v);
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  if (Array.isArray(v)) return `[${v.map(toml).join(", ")}]`;
  const entries = Object.entries(v as Record<string, unknown>).map(([k, x]) => `${/^[A-Za-z0-9_-]+$/.test(k) ? k : JSON.stringify(k)} = ${toml(x)}`);
  return `{ ${entries.join(", ")} }`;
}

export interface AgentDeclared {
  heads: string[];
  url: string;
  /** "agent" or "both"; "agent" when absent. */
  call?: string;
  /** The tool it offers; none written when null. */
  tool?: Tool | null;
  /** The rule, as TOML: `{ thread = "main" }`. */
  when?: string;
  may?: string;
  message?: string;
  color?: string;
  timeout?: number;
  onFailure?: string;
  send?: string[];
  /** Lines added to the table as they are. */
  extra?: string;
}

/** A capability's table in splice.toml that the model calls through a tool, with an HTTP worker at `url`. Keys the
 *  declaration leaves out are not written, as the spec's own example leaves `place`, `may` and `send` out. */
export function agentCapability(name: string, o: AgentDeclared): string {
  const tool = o.tool === null ? null : (o.tool ?? ADVISOR);
  const lines = [
    `[capabilities.${name}]`,
    `heads = [${o.heads.map((h) => `"${h}"`).join(", ")}]`,
    `call = "${o.call ?? "agent"}"`,
    tool ? `tool = ${toml(tool)}` : "",
    o.when ? `when = ${o.when}` : "",
    o.may ? `may = "${o.may}"` : "",
    `message = "${o.message ?? "Asking the advisor"}"`,
    `color = "${o.color ?? "magenta"}"`,
    `timeout_s = ${o.timeout ?? 30}`,
    `on_failure = "${o.onFailure ?? "open"}"`,
    o.send ? `send = [${o.send.map((f) => `"${f}"`).join(", ")}]` : "",
    `worker = { kind = "http", url = "${o.url}" }`,
    o.extra ?? "",
  ];
  return `\n${lines.filter(Boolean).join("\n")}\n`;
}

/** The signature the provider gives a thinking block: it stands for that thinking and no other, as the real API's
 *  does, so a thinking block whose text or signature was changed is refused. */
export function signature(thinking: string): string {
  return createHash("sha256").update(`check-provider:${thinking}`).digest("base64").repeat(3);
}

export const thinking = (text: string): Block => ({ type: "thinking", thinking: text, signature: signature(text) });
export const text = (t: string): Block => ({ type: "text", text: t });
export const call = (id: string, name: string, input: Record<string, unknown>): Block => ({ type: "tool_use", id, name, input });
export const result = (id: string, content: string): Block => ({ type: "tool_result", tool_use_id: id, content });

/** A tool call id as the API mints them. */
export function toolId(): string {
  const abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  let s = "toolu_01";
  for (let i = 0; i < 22; i++) s += abc[Math.floor(Math.random() * abc.length)];
  return s;
}

/** One message the scripted model answers with. */
export interface Reply {
  blocks: Block[];
  stop: "end_turn" | "tool_use";
}

/** The model's message that calls the advisor: `before` first, then the call. */
export function asking(id: string, question: string, before: Block[]): Reply {
  return { blocks: [...before, call(id, ADVISOR.name, { question })], stop: "tool_use" };
}

export const answer = (...blocks: Block[]): Reply => ({ blocks, stop: "end_turn" });

interface Script {
  marker: string;
  replies: Reply[];
}

/** A provider that speaks Anthropic's Messages API. A request whose bytes hold a planned marker is answered with that
 *  plan's next reply, in order; any other request, and one whose plan is spent, is answered `ok from <name>`. A
 *  request the real API would refuse for its shape is refused with 400 and recorded in `refused`. */
export class AgentProvider {
  readonly name: string;
  readonly turns: SeenTurn[] = [];
  readonly refused: { turn: SeenTurn; why: string }[] = [];
  private scripts: Script[] = [];
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

  /** Answer the requests that carry `marker` with `replies`, one each, in order. */
  plan(marker: string, replies: Reply[]): void {
    this.scripts.push({ marker, replies: [...replies] });
  }

  /** The requests it accepted whose bytes hold `marker`, oldest first. */
  with(marker: string): SeenTurn[] {
    return this.turns.filter((t) => t.raw.includes(marker));
  }

  /** The requests it accepted of session `session`, read where the body names it: `metadata.user_id`. */
  of(session: string): SeenTurn[] {
    return this.turns.filter((t) => sessionOf(t.body) === session);
  }

  private next(raw: string): Reply {
    for (const s of this.scripts) {
      if (raw.includes(s.marker) && s.replies.length) return s.replies.shift()!;
    }
    return answer(text(this.reply()));
  }

  private async answer(req: Request): Promise<Response> {
    const path = new URL(req.url).pathname;
    if (!path.endsWith("/v1/messages")) return Response.json({ type: "error", error: { type: "not_found_error", message: `no route ${path}` } }, { status: 404 });
    const raw = await req.text();
    const body = JSON.parse(raw) as SeenTurn["body"];
    const seen: SeenTurn = { headers: Object.fromEntries(req.headers), body, raw, at: Date.now() };
    const why = refusal(body);
    if (why) {
      this.refused.push({ turn: seen, why });
      return Response.json({ type: "error", error: { type: "invalid_request_error", message: why } }, { status: 400 });
    }
    this.turns.push(seen);
    const r = this.next(raw);
    const usage = { input_tokens: 12, cache_creation_input_tokens: 0, cache_read_input_tokens: 0, output_tokens: 4 };
    const id = `msg_${crypto.randomUUID().replaceAll("-", "").slice(0, 24)}`;
    if (body.stream !== true) {
      return Response.json({ id, type: "message", role: "assistant", model: body.model, content: r.blocks, stop_reason: r.stop, stop_sequence: null, usage });
    }
    const frames: Record<string, unknown>[] = [
      { type: "message_start", message: { id, type: "message", role: "assistant", model: body.model, content: [], stop_reason: null, stop_sequence: null, usage: { ...usage, output_tokens: 1 } } },
      { type: "ping" },
    ];
    r.blocks.forEach((b, index) => frames.push(...blockFrames(b, index)));
    frames.push({ type: "message_delta", delta: { stop_reason: r.stop, stop_sequence: null }, usage: { output_tokens: 4 } }, { type: "message_stop" });
    const sse = frames.map((f) => `event: ${f.type}\ndata: ${JSON.stringify(f)}\n\n`).join("");
    return new Response(sse, { headers: { "content-type": "text/event-stream", "cache-control": "no-cache" } });
  }
}

/** A block as the API streams it: its start, its deltas, its stop. */
function blockFrames(b: Block, index: number): Record<string, unknown>[] {
  const start = (content_block: unknown) => ({ type: "content_block_start", index, content_block });
  const delta = (d: unknown) => ({ type: "content_block_delta", index, delta: d });
  const stop = { type: "content_block_stop", index };
  if (b.type === "thinking") {
    return [start({ type: "thinking", thinking: "", signature: "" }), delta({ type: "thinking_delta", thinking: b.thinking }), delta({ type: "signature_delta", signature: b.signature }), stop];
  }
  if (b.type === "text") return [start({ type: "text", text: "" }), delta({ type: "text_delta", text: b.text }), stop];
  if (b.type === "tool_use") {
    const json = JSON.stringify(b.input);
    const half = Math.floor(json.length / 2);
    return [
      start({ type: "tool_use", id: b.id, name: b.name, input: {} }),
      delta({ type: "input_json_delta", partial_json: json.slice(0, half) }),
      delta({ type: "input_json_delta", partial_json: json.slice(half) }),
      stop,
    ];
  }
  return [start(b), stop];
}

/** Why the real Messages API would refuse this request's shape, or null. Only rules its own errors state:
 *  - a conversation that ends on an assistant message ("This model does not support assistant message prefill");
 *  - tool_use or tool_result blocks in a request that defines no tools;
 *  - two tools of one name, or two tool calls of one id;
 *  - a tool_use with no tool_result for it in the next message, or a tool_result with no tool_use for it in the
 *    previous one;
 *  - a user message whose tool_result blocks do not come first;
 *  - a thinking block whose signature is not the one the provider gave that thinking. */
function refusal(body: SeenTurn["body"]): string | null {
  const messages = body.messages ?? [];
  const tools = (body.tools as { name?: string }[] | undefined) ?? [];
  if (!messages.length) return "messages: at least one message is required";
  if (messages.at(-1)!.role !== "user") return "This model does not support assistant message prefill. The conversation must end with a user message.";
  const names = tools.map((t) => t.name);
  if (new Set(names).size !== names.length) return "tools: Tool names must be unique.";
  const content = (m: Message | undefined): Block[] => (!m ? [] : typeof m.content === "string" ? [{ type: "text", text: m.content }] : m.content);
  const all = messages.flatMap((m) => content(m));
  if (!tools.length && all.some((b) => b.type === "tool_use" || b.type === "tool_result")) return "Requests which include `tool_use` or `tool_result` blocks must define tools.";
  const ids = all.filter((b) => b.type === "tool_use").map((b) => String(b.id));
  if (new Set(ids).size !== ids.length) return "messages: `tool_use` ids must be unique";
  for (let i = 0; i < messages.length; i++) {
    const m = messages[i]!;
    const here = content(m);
    if (m.role === "assistant") {
      const forged = here.findIndex((b) => b.type === "thinking" && b.signature !== signature(String(b.thinking ?? "")));
      if (forged >= 0) return `messages.${i}.content.${forged}: Invalid \`signature\` in \`thinking\` block`;
      const calls = here.filter((b) => b.type === "tool_use").map((b) => String(b.id));
      if (!calls.length) continue;
      const answered = new Set(content(messages[i + 1]).filter((b) => b.type === "tool_result").map((b) => String(b.tool_use_id)));
      const missing = calls.filter((id) => !answered.has(id));
      if (messages[i + 1]?.role !== "user" || missing.length) {
        return `messages.${i}: \`tool_use\` ids were found without \`tool_result\` blocks immediately after: ${missing.join(", ")}. Each \`tool_use\` block must have a corresponding \`tool_result\` block in the next message.`;
      }
    } else {
      const results = here.filter((b) => b.type === "tool_result");
      if (!results.length) continue;
      const firstOther = here.findIndex((b) => b.type !== "tool_result");
      if (firstOther >= 0 && here.slice(firstOther).some((b) => b.type === "tool_result")) return `messages.${i}: \`tool_result\` blocks must come first in a user message's content`;
      const made = new Set(content(messages[i - 1]).filter((b) => b.type === "tool_use").map((b) => String(b.id)));
      const stray = results.map((b) => String(b.tool_use_id)).filter((id) => !made.has(id));
      if (messages[i - 1]?.role !== "assistant" || stray.length) {
        return `messages.${i}: unexpected \`tool_use_id\` found in \`tool_result\` blocks: ${stray.join(", ")}. Each \`tool_result\` block must have a corresponding \`tool_use\` block in the previous message.`;
      }
    }
  }
  return null;
}

function sessionOf(body: SeenTurn["body"]): string | null {
  const id = (body.metadata as { user_id?: unknown } | undefined)?.user_id;
  if (typeof id !== "string") return null;
  try {
    return String((JSON.parse(id) as { session_id?: unknown }).session_id ?? "") || null;
  } catch {
    return null;
  }
}

/** One event of a stream the client was sent. */
export interface Event {
  type: string;
  data: Record<string, unknown>;
}

/** What a client assembles from the stream it was sent. */
export interface Assembled {
  events: Event[];
  /** The content blocks, as Claude Code keeps them: deltas applied, a tool call's input parsed. */
  blocks: Block[];
  /** The index each content_block_start named, in the order they came. */
  indices: number[];
  /** Each message_delta's stop_reason. */
  stops: (string | null)[];
  count: (type: string) => number;
}

/** The stream `body` as a client reads it. */
export function assemble(body: string): Assembled {
  const events: Event[] = [];
  for (const chunk of body.split(/\r?\n\r?\n/)) {
    const data = chunk
      .split(/\r?\n/)
      .filter((l) => l.startsWith("data:"))
      .map((l) => l.slice(5).trim())
      .join("\n");
    if (!data.startsWith("{")) continue;
    try {
      const d = JSON.parse(data) as Record<string, unknown>;
      events.push({ type: String(d.type ?? ""), data: d });
    } catch {}
  }
  const blocks: Block[] = [];
  const partial: Record<number, string> = {};
  const indices: number[] = [];
  const stops: (string | null)[] = [];
  for (const e of events) {
    const index = Number(e.data.index);
    if (e.type === "content_block_start") {
      indices.push(index);
      blocks[index] = { ...(e.data.content_block as Block) };
    } else if (e.type === "content_block_delta") {
      const b = blocks[index];
      const d = e.data.delta as Record<string, string>;
      if (!b) continue;
      if (d.type === "text_delta") b.text = (b.text ?? "") + d.text;
      else if (d.type === "thinking_delta") b.thinking = String(b.thinking ?? "") + d.thinking;
      else if (d.type === "signature_delta") b.signature = String(b.signature ?? "") + d.signature;
      else if (d.type === "input_json_delta") partial[index] = (partial[index] ?? "") + d.partial_json;
    } else if (e.type === "content_block_stop") {
      const b = blocks[index];
      if (b?.type === "tool_use" && partial[index]) b.input = JSON.parse(partial[index]!);
    } else if (e.type === "message_delta") {
      stops.push(((e.data.delta as { stop_reason?: string | null } | undefined)?.stop_reason ?? null) as string | null);
    }
  }
  return { events, blocks, indices, stops, count: (type) => events.filter((e) => e.type === type).length };
}

/** `x` with every `cache_control` mark taken out: Claude Code moves its marks from turn to turn, and a check of what
 *  a conversation holds reads past them. */
export function unmarkedAll<T>(x: T): T {
  if (Array.isArray(x)) return x.map(unmarkedAll) as T;
  if (x && typeof x === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(x)) if (k !== "cache_control") out[k] = unmarkedAll(v);
    return out as T;
  }
  return x;
}

/** The text a tool_result block carries, whether its content is a string or text blocks. */
export function resultText(b: Block | undefined): string {
  const c = b?.content as string | Block[] | undefined;
  if (typeof c === "string") return c;
  return (c ?? []).map((x) => x.text ?? "").join("");
}
