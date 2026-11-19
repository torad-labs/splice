// A provider on Anthropic's Messages API for the desktop-full checks: it records each request byte for byte, answers
// a turn (streamed or not) and a count, and can be told to refuse the next requests with a body of its own and to add
// headers of its own (Anthropic's unified rate-limit family) to every answer.

import type { Server } from "bun";

export interface Seen {
  path: string;
  headers: Record<string, string>;
  raw: string;
  body: Record<string, unknown> | null;
}

export interface Refusal {
  status: number;
  /** The body, sent as written. */
  body: string;
  headers?: Record<string, string>;
}

export class AnthropicStub {
  readonly seen: Seen[] = [];
  /** Refusals for the next requests, first one first. */
  readonly next: Refusal[] = [];
  /** Headers added to every answer. */
  headers: Record<string, string> = {};
  /** What a count answers. */
  counted = 42;
  private server: Server<unknown> | null = null;

  constructor(readonly name: string) {}

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

  turns(): Seen[] {
    return this.seen.filter((s) => s.path.endsWith("/v1/messages"));
  }

  private async answer(req: Request): Promise<Response> {
    const path = new URL(req.url).pathname;
    const raw = req.method === "POST" ? await req.text() : "";
    let body: Record<string, unknown> | null = null;
    try {
      body = raw ? (JSON.parse(raw) as Record<string, unknown>) : null;
    } catch {}
    this.seen.push({ path, headers: Object.fromEntries(req.headers), raw, body });
    if (path.endsWith("/v1/models")) {
      return Response.json({ data: [], has_more: false }, { headers: this.headers });
    }
    const refusal = this.next.shift();
    if (refusal) {
      return new Response(refusal.body, { status: refusal.status, headers: { "content-type": "application/json", ...this.headers, ...refusal.headers } });
    }
    if (path.endsWith("/v1/messages/count_tokens")) return Response.json({ input_tokens: this.counted }, { headers: this.headers });
    if (!path.endsWith("/v1/messages")) return Response.json({ type: "error", error: { type: "not_found_error", message: `no route ${path}` } }, { status: 404 });
    const usage = {
      input_tokens: 12,
      cache_creation_input_tokens: 30,
      cache_read_input_tokens: 100,
      cache_creation: { ephemeral_5m_input_tokens: 10, ephemeral_1h_input_tokens: 20 },
      output_tokens: 4,
    };
    const model = body?.model;
    if (body?.stream !== true) {
      return Response.json(
        { id: "msg_check", type: "message", role: "assistant", model, content: [{ type: "text", text: this.reply() }], stop_reason: "end_turn", stop_sequence: null, usage },
        { headers: this.headers },
      );
    }
    const frames = [
      { type: "message_start", message: { id: "msg_check", type: "message", role: "assistant", model, content: [], stop_reason: null, stop_sequence: null, usage: { ...usage, output_tokens: 1 } } },
      { type: "content_block_start", index: 0, content_block: { type: "text", text: "" } },
      { type: "content_block_delta", index: 0, delta: { type: "text_delta", text: this.reply() } },
      { type: "content_block_stop", index: 0 },
      { type: "message_delta", delta: { stop_reason: "end_turn", stop_sequence: null }, usage: { output_tokens: 4 } },
      { type: "message_stop" },
    ];
    const text = frames.map((f) => `event: ${f.type}\ndata: ${JSON.stringify(f)}\n\n`).join("");
    return new Response(text, { headers: { "content-type": "text/event-stream", "cache-control": "no-cache", ...this.headers } });
  }
}

/** A head on an Anthropic API key of splice's own, so it is in the door. */
export function anthropicKeyHead(o: { key: string; command: string; port: number; url: string; model: string; window?: number }): string {
  return `
[providers.${o.key}]
dialect = "anthropic-passthrough"
base_url = "${o.url}"
auth = { kind = "api-key", env = "MOCK_UPSTREAM_KEY" }
extra_headers = { anthropic-version = "2023-06-01" }

[[providers.${o.key}.models]]
id = "${o.model}"
label = "${o.model}"
context_window = ${o.window ?? 200000}

[heads.${o.key}]
provider = "${o.key}"
port = ${o.port}
discovery_prefix = "claude-${o.key}--"
pinned_model = "${o.model}"
[heads.${o.key}.claude]
command = "${o.command}"
`;
}
