// What the reply-stream checks share: a model server on Anthropic's Messages API that streams a reply slowly (thinking
// with a signature, text, and a tool call whose input carries a marker), a turn sent through the door the way Claude
// Code sends it and read as it arrives, and a follower of `GET /api/sessions/{id}/live` that keeps every event and
// comment it was sent.

import { type Splice, until } from "../../server/lib/harness.ts";

export { anthropicKeyHead } from "../../desktop-full/lib/anthropic.ts";

// ---------------------------------------------------------------------------------------------------------------------
// The model server

export interface SlowSeen {
  path: string;
  headers: Record<string, string>;
  body: Record<string, unknown> | null;
}

/** A provider on Anthropic's Messages API that streams each reply one piece at a time, `gapMs` apart. A request that
 *  asks for thinking gets a thinking block first, closed by a signature; every request gets a text block; a request
 *  that names tools ends in a call of its first tool, whose input holds [toolMarker], and stops for `tool_use`. A
 *  request with no tools stops for `end_turn`. A request with `stream: false` gets the same blocks as one JSON
 *  message. */
export class SlowAnthropic {
  readonly seen: SlowSeen[] = [];
  /** A value no follower may ever be sent: it is the thinking block's signature. */
  readonly signature = `SIGNATURE-${crypto.randomUUID()}`;
  /** A value no follower may ever be sent: it is inside the tool call's input. */
  readonly toolMarker = `TOOL-INPUT-${crypto.randomUUID()}`;
  gapMs: number;
  thoughts: string[];
  words: string[];
  private server: ReturnType<typeof Bun.serve> | null = null;
  private n = 0;

  constructor(
    readonly name: string,
    o: { gapMs?: number; thoughts?: number; words?: number } = {},
  ) {
    this.gapMs = o.gapMs ?? 60;
    this.thoughts = Array.from({ length: o.thoughts ?? 6 }, (_, i) => `${i ? " " : ""}thought-${i + 1}`);
    this.words = Array.from({ length: o.words ?? 12 }, (_, i) => `${i ? " " : ""}${name}-word-${i + 1}`);
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

  /** The whole text a reply carries. */
  text(): string {
    return this.words.join("");
  }

  /** The whole thinking a reply carries, when it carries thinking. */
  thinking(): string {
    return this.thoughts.join("");
  }

  turns(): SlowSeen[] {
    return this.seen.filter((s) => s.path.endsWith("/v1/messages"));
  }

  private async answer(req: Request): Promise<Response> {
    const path = new URL(req.url).pathname;
    const raw = req.method === "POST" ? await req.text() : "";
    let body: Record<string, unknown> | null = null;
    try {
      body = raw ? (JSON.parse(raw) as Record<string, unknown>) : null;
    } catch {}
    this.seen.push({ path, headers: Object.fromEntries(req.headers), body });
    if (path.endsWith("/v1/models")) return Response.json({ data: [], has_more: false });
    if (path.endsWith("/v1/messages/count_tokens")) return Response.json({ input_tokens: 42 });
    if (!path.endsWith("/v1/messages")) {
      return Response.json({ type: "error", error: { type: "not_found_error", message: `no route ${path}` } }, { status: 404 });
    }
    const id = `msg_${this.name}_${++this.n}_${crypto.randomUUID().slice(0, 8)}`;
    const toolId = `toolu_${this.name}_${this.n}_${crypto.randomUUID().slice(0, 8)}`;
    const model = body?.model;
    const thinks = body?.thinking !== undefined && body?.thinking !== null;
    const tools = Array.isArray(body?.tools) ? (body!.tools as { name?: string }[]) : [];
    const tool = tools.length ? { id: toolId, name: String(tools[0]!.name ?? "Read"), input: { file_path: `/notes/${this.toolMarker}.md` } } : null;
    const stop = tool ? "tool_use" : "end_turn";
    const usage = { input_tokens: 12, cache_creation_input_tokens: 0, cache_read_input_tokens: 0, output_tokens: 1 };
    if (body?.stream !== true) {
      const content: Record<string, unknown>[] = [];
      if (thinks) content.push({ type: "thinking", thinking: this.thinking(), signature: this.signature });
      content.push({ type: "text", text: this.text() });
      if (tool) content.push({ type: "tool_use", ...tool });
      return Response.json({ id, type: "message", role: "assistant", model, content, stop_reason: stop, stop_sequence: null, usage: { ...usage, output_tokens: 40 } });
    }
    const frames: Record<string, unknown>[][] = [];
    frames.push([{ type: "message_start", message: { id, type: "message", role: "assistant", model, content: [], stop_reason: null, stop_sequence: null, usage } }]);
    let index = 0;
    if (thinks) {
      const i = index++;
      frames.push([{ type: "content_block_start", index: i, content_block: { type: "thinking", thinking: "" } }]);
      for (const t of this.thoughts) frames.push([{ type: "content_block_delta", index: i, delta: { type: "thinking_delta", thinking: t } }]);
      frames.push([
        { type: "content_block_delta", index: i, delta: { type: "signature_delta", signature: this.signature } },
        { type: "content_block_stop", index: i },
      ]);
    }
    {
      const i = index++;
      frames.push([{ type: "content_block_start", index: i, content_block: { type: "text", text: "" } }]);
      for (const w of this.words) frames.push([{ type: "content_block_delta", index: i, delta: { type: "text_delta", text: w } }]);
      frames.push([{ type: "content_block_stop", index: i }]);
    }
    if (tool) {
      const i = index++;
      const input = JSON.stringify(tool.input);
      const half = Math.floor(input.length / 2);
      frames.push([{ type: "content_block_start", index: i, content_block: { type: "tool_use", id: tool.id, name: tool.name, input: {} } }]);
      frames.push([{ type: "content_block_delta", index: i, delta: { type: "input_json_delta", partial_json: input.slice(0, half) } }]);
      frames.push([{ type: "content_block_delta", index: i, delta: { type: "input_json_delta", partial_json: input.slice(half) } }]);
      frames.push([{ type: "content_block_stop", index: i }]);
    }
    frames.push([
      { type: "message_delta", delta: { stop_reason: stop, stop_sequence: null }, usage: { output_tokens: 40 } },
      { type: "message_stop" },
    ]);
    const enc = new TextEncoder();
    const gap = this.gapMs;
    const stream = new ReadableStream<Uint8Array>({
      async start(controller) {
        try {
          for (let k = 0; k < frames.length; k++) {
            if (k > 0) await Bun.sleep(gap);
            controller.enqueue(enc.encode(frames[k]!.map((f) => `event: ${f.type}\ndata: ${JSON.stringify(f)}\n\n`).join("")));
          }
          controller.close();
        } catch {}
      },
    });
    return new Response(stream, { headers: { "content-type": "text/event-stream", "cache-control": "no-cache" } });
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Server-sent events

export interface Sse {
  event: string;
  data: Record<string, unknown> | null;
}

/** Reads a body of server-sent events as it arrives: each event to [onEvent], each comment line to [onComment]. */
async function readSse(body: ReadableStream<Uint8Array>, onEvent: (e: Sse) => void, onComment: (c: string) => void, onRaw: (t: string) => void): Promise<void> {
  const reader = body.getReader();
  const dec = new TextDecoder();
  let buf = "";
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    const piece = dec.decode(value, { stream: true });
    onRaw(piece);
    buf += piece;
    for (;;) {
      const m = /\r?\n\r?\n/.exec(buf);
      if (!m) break;
      const block = buf.slice(0, m.index);
      buf = buf.slice(m.index + m[0].length);
      let event = "";
      const data: string[] = [];
      for (const line of block.split(/\r?\n/)) {
        if (line.startsWith(":")) onComment(line.slice(1).trim());
        else if (line.startsWith("event:")) event = line.slice(6).trim();
        else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
      }
      if (!event && !data.length) continue;
      let parsed: Record<string, unknown> | null = null;
      try {
        parsed = JSON.parse(data.join("\n")) as Record<string, unknown>;
      } catch {}
      onEvent({ event: event || String(parsed?.type ?? ""), data: parsed });
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// A turn as Claude Code sends it through the door

export const CLAUDE_CODE = { "user-agent": "claude-cli/2.1.325 (external, cli)", "x-app": "cli" };

export const READ_TOOL = {
  name: "Read",
  description: "Read a file from the local filesystem.",
  input_schema: { type: "object", properties: { file_path: { type: "string" } }, required: ["file_path"] },
};

/** The words Claude Code's summarizer request carries, which mark a turn as a compaction. */
export const COMPACT_SYSTEM = "You are a helpful AI assistant tasked with summarizing conversations.";
export const COMPACT_ASK =
  "Your task is to create a detailed summary of the conversation so far, paying close attention to the user's explicit requests and your previous actions.";

export interface Ask {
  model: string;
  session: string;
  /** Claude Code's subagent id, sent as `x-claude-code-agent-id`. */
  agent?: string;
  stream?: boolean;
  /** Ask for thinking. */
  thinking?: boolean;
  /** Offer the Read tool. */
  tools?: boolean;
  system?: string;
  content?: string;
}

/** One content block as the client received it. */
export interface Block {
  index: number;
  type: string;
  text: string;
  thinking: string;
  id?: string;
  name?: string;
  signature: string;
  input: string;
}

/** A turn sent through the door, read as it arrives. */
export class ClientTurn {
  status = 0;
  contentType = "";
  readonly events: Sse[] = [];
  raw = "";
  /** The JSON answer, for a turn that was not streamed. */
  json: Record<string, unknown> | null = null;
  readonly done: Promise<void>;
  private readonly ac = new AbortController();

  private constructor(url: string, key: string, a: Ask) {
    const headers: Record<string, string> = {
      ...CLAUDE_CODE,
      "content-type": "application/json",
      "anthropic-version": "2023-06-01",
      authorization: `Bearer ${key}`,
      "x-claude-code-session-id": a.session,
    };
    if (a.agent) headers["x-claude-code-agent-id"] = a.agent;
    const body: Record<string, unknown> = {
      model: a.model,
      max_tokens: 4096,
      stream: a.stream ?? true,
      messages: [{ role: "user", content: a.content ?? "Read the notes file and tell me what it says." }],
    };
    if (a.system) body.system = a.system;
    if (a.thinking) body.thinking = { type: "enabled", budget_tokens: 2048 };
    if (a.tools) body.tools = [READ_TOOL];
    this.done = (async () => {
      try {
        const res = await fetch(`${url}/v1/messages?beta=true`, { method: "POST", headers, body: JSON.stringify(body), signal: this.ac.signal });
        this.status = res.status;
        this.contentType = res.headers.get("content-type") ?? "";
        if (!res.body) return;
        if (this.contentType.includes("event-stream")) {
          await readSse(res.body, (e) => this.events.push(e), () => {}, (t) => (this.raw += t));
        } else {
          this.raw = await res.text();
          try {
            this.json = JSON.parse(this.raw) as Record<string, unknown>;
          } catch {}
        }
      } catch (e) {
        if (!this.ac.signal.aborted) throw e;
      }
    })();
  }

  /** Send [a] through the door at [url] with the door key [key]; the turn is read in the background. */
  static send(url: string, key: string, a: Ask): ClientTurn {
    return new ClientTurn(url, key, a);
  }

  /** Hang up, as Claude Code does when it gives up on a turn. */
  abort(): void {
    this.ac.abort();
  }

  /** The id of the message the client was sent: its message_start's id, or a JSON answer's id. */
  message(): string | undefined {
    if (this.json) return this.json.id as string | undefined;
    const start = this.events.find((e) => e.event === "message_start");
    return (start?.data?.message as { id?: string } | undefined)?.id;
  }

  /** The stop reason the client was sent. */
  stopReason(): string | null {
    if (this.json) return (this.json.stop_reason as string | null) ?? null;
    let stop: string | null = null;
    for (const e of this.events) {
      const d = e.data?.delta as { stop_reason?: string | null } | undefined;
      if (e.event === "message_delta" && d && "stop_reason" in d) stop = d.stop_reason ?? null;
    }
    return stop;
  }

  /** Every content block the client received, by index, in the order each started. */
  blocks(): Block[] {
    const out = new Map<number, Block>();
    const get = (i: number, type = ""): Block => {
      let b = out.get(i);
      if (!b) {
        b = { index: i, type, text: "", thinking: "", signature: "", input: "" };
        out.set(i, b);
      }
      return b;
    };
    if (this.json) {
      (this.json.content as Record<string, unknown>[] | undefined)?.forEach((c, i) => {
        const b = get(i, String(c.type));
        b.text = String(c.text ?? "");
        b.thinking = String(c.thinking ?? "");
        b.signature = String(c.signature ?? "");
        if (c.id) b.id = String(c.id);
        if (c.name) b.name = String(c.name);
        if (c.input) b.input = JSON.stringify(c.input);
      });
      return [...out.values()];
    }
    for (const e of this.events) {
      const d = e.data as Record<string, unknown> | null;
      if (!d || typeof d.index !== "number") continue;
      if (e.event === "content_block_start") {
        const c = d.content_block as Record<string, unknown>;
        const b = get(d.index, String(c.type));
        b.type = String(c.type);
        b.text += String(c.text ?? "");
        b.thinking += String(c.thinking ?? "");
        if (c.id) b.id = String(c.id);
        if (c.name) b.name = String(c.name);
      } else if (e.event === "content_block_delta") {
        const delta = d.delta as Record<string, unknown>;
        const b = get(d.index);
        if (delta.type === "text_delta") b.text += String(delta.text ?? "");
        else if (delta.type === "thinking_delta") b.thinking += String(delta.thinking ?? "");
        else if (delta.type === "signature_delta") b.signature += String(delta.signature ?? "");
        else if (delta.type === "input_json_delta") b.input += String(delta.partial_json ?? "");
      }
    }
    return [...out.values()];
  }

  /** The text deltas the client has received so far, in order. */
  textDeltas(): string[] {
    return this.events
      .filter((e) => e.event === "content_block_delta" && (e.data?.delta as { type?: string } | undefined)?.type === "text_delta")
      .map((e) => String((e.data!.delta as { text?: string }).text ?? ""));
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// A follower of the live route

export class Follower {
  status = 0;
  contentType = "";
  readonly events: Sse[] = [];
  readonly comments: string[] = [];
  raw = "";
  ended = false;
  private readonly ac = new AbortController();
  private reading: Promise<void> = Promise.resolve();

  private constructor() {}

  /** Opens `GET /api/sessions/{session}/live` on [s]'s control plane with [key] as the bearer (the management key when
   *  not given; none at all when null). Returns once the response's head has arrived; its body is read in the
   *  background. */
  static async open(s: Splice, session: string, key: string | null = s.mgmtKey()): Promise<Follower> {
    const f = new Follower();
    const headers: Record<string, string> = { accept: "text/event-stream" };
    if (key !== null) headers.authorization = `Bearer ${key}`;
    const res = await fetch(`http://127.0.0.1:${s.controlPort}/api/sessions/${encodeURIComponent(session)}/live`, { headers, signal: f.ac.signal });
    f.status = res.status;
    f.contentType = res.headers.get("content-type") ?? "";
    if (res.status !== 200 || !f.contentType.includes("event-stream") || !res.body) {
      f.raw = await res.text().catch(() => "");
      f.ended = true;
      return f;
    }
    const body = res.body;
    f.reading = (async () => {
      try {
        await readSse(body, (e) => f.events.push(e), (c) => f.comments.push(c), (t) => (f.raw += t));
      } catch {}
      f.ended = true;
    })();
    return f;
  }

  close(): void {
    this.ac.abort();
  }

  async closed(): Promise<void> {
    this.close();
    await this.reading;
  }

  /** Wait until [what] holds of this follower. */
  async until(what: string, f: (me: Follower) => boolean, ms = 30_000): Promise<void> {
    await until(`${what} (events so far: ${JSON.stringify(this.events).slice(0, 2000)})`, () => f(this), ms);
  }

  /** The first `hello`, once it has come. */
  async hello(): Promise<Record<string, unknown>> {
    await this.until("hello", (me) => me.events.some((e) => e.event === "hello"), 15_000);
    return this.events.find((e) => e.event === "hello")!.data!;
  }

  /** The `turn` event whose message is [message], once it has come. */
  async turnOf(message: string, ms = 30_000): Promise<Record<string, unknown>> {
    await this.until(`the turn of ${message}`, (me) => me.turnEvent(message) !== undefined, ms);
    return this.turnEvent(message)!;
  }

  turnEvent(message: string): Record<string, unknown> | undefined {
    return this.events.find((e) => e.event === "turn" && e.data?.message === message)?.data ?? undefined;
  }

  /** Every event naming [turn], in the order they came. */
  of(turn: string): Sse[] {
    return this.events.filter((e) => e.data?.turn === turn);
  }

  /** The turn's `end`, once it has come. */
  async endOf(turn: string, ms = 60_000): Promise<Record<string, unknown>> {
    await this.until(`the end of turn ${turn}`, (me) => me.of(turn).some((e) => e.event === "end"), ms);
    return this.of(turn).find((e) => e.event === "end")!.data!;
  }

  /** The reply of [turn] as this follower was sent it: per block index, its text, its thinking and its tool start, in
   *  the order each block was first named. */
  blocks(turn: string): { index: number; text: string; thinking: string; tool?: { id: string; name: string } }[] {
    const out = new Map<number, { index: number; text: string; thinking: string; tool?: { id: string; name: string } }>();
    for (const e of this.of(turn)) {
      const d = e.data!;
      if (typeof d.index !== "number") continue;
      let b = out.get(d.index);
      if (!b) {
        b = { index: d.index, text: "", thinking: "" };
        out.set(d.index, b);
      }
      if (e.event === "text") b.text += String(d.delta ?? "");
      else if (e.event === "thinking") b.thinking += String(d.delta ?? "");
      else if (e.event === "tool") b.tool = { id: String(d.id), name: String(d.name) };
    }
    return [...out.values()];
  }
}

/** What the client received, in the shape a follower's [Follower.blocks] has, for comparing the two. Blocks that
 *  carried nothing a follower is sent (an empty block, a block of another kind) are left out. */
export function received(t: ClientTurn): { index: number; text: string; thinking: string; tool?: { id: string; name: string } }[] {
  return t
    .blocks()
    .filter((b) => b.text || b.thinking || b.type.endsWith("tool_use"))
    .map((b) => ({
      index: b.index,
      text: b.text,
      thinking: b.thinking,
      ...(b.type.endsWith("tool_use") ? { tool: { id: b.id ?? "", name: b.name ?? "" } } : {}),
    }));
}
