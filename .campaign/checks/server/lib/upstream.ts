// A self-hosted model server for the server-mode checks: OpenAI-compatible like llama-server, with knobs for
// what a team's box does under load. It serves /health, /v1/models and /v1/chat/completions (streamed and not),
// records every request it was sent, and says its own name in every reply so a check can tell which upstream
// answered.

export interface Behaviour {
  /** Milliseconds before the first byte of a completion, response headers included (a long prefill). */
  prefillMs: number;
  /** Words in the reply, sent one per chunk. */
  words: number;
  /** Milliseconds between chunks after the first. */
  gapMs: number;
  /** After this many words, wait stallMs before the next one. */
  stallAfter: number | null;
  stallMs: number;
  /** After this many words, tear the connection without a finish (a body that breaks mid-stream). */
  breakAfter: number | null;
  /** End a streamed reply cleanly but without `data: [DONE]`. */
  noDone: boolean;
  /** Answer every completion with this status and an error body instead. */
  status: number | null;
  /** The usage the reply reports. */
  usage: { prompt: number; cached: number; completion: number };
  /** llama-server's `timings` on the final chunk. */
  timings: { prompt_n: number; cache_n: number; predicted_n: number; prompt_ms: number; predicted_ms: number } | null;
  /** /health answers 200 when true, 503 when false. */
  healthy: boolean;
  /** Reply with these words instead of the upstream's name and numbered words. */
  say: string | null;
}

export interface Seen {
  path: string;
  headers: Record<string, string>;
  body: Record<string, unknown> | null;
  at: number;
}

const DEFAULTS: Behaviour = {
  prefillMs: 0,
  words: 5,
  gapMs: 20,
  stallAfter: null,
  stallMs: 0,
  breakAfter: null,
  noDone: false,
  status: null,
  usage: { prompt: 1200, cached: 200, completion: 40 },
  timings: { prompt_n: 1000, cache_n: 200, predicted_n: 40, prompt_ms: 500, predicted_ms: 800 },
  healthy: true,
  say: null,
};

export class Upstream {
  readonly name: string;
  readonly model: string;
  behaviour: Behaviour;
  readonly seen: Seen[] = [];
  /** Completions being answered right now. */
  inflight = 0;
  private server: ReturnType<typeof Bun.serve> | null = null;

  constructor(name: string, model: string, behaviour: Partial<Behaviour> = {}) {
    this.name = name;
    this.model = model;
    this.behaviour = { ...DEFAULTS, ...behaviour };
  }

  get port(): number {
    if (!this.server) throw new Error(`upstream ${this.name} is not running`);
    return this.server.port as number;
  }

  /** The base URL a provider row names. */
  get url(): string {
    return `http://127.0.0.1:${this.port}/v1`;
  }

  /** The reply text this upstream sends: its name, then numbered words. */
  reply(): string {
    if (this.behaviour.say !== null) return this.behaviour.say;
    return [`from-${this.name}`, ...Array.from({ length: this.behaviour.words - 1 }, (_, i) => `w${i + 1}`)].join(" ");
  }

  completions(): Seen[] {
    return this.seen.filter((s) => s.path.endsWith("/chat/completions"));
  }

  start(port = 0): this {
    this.server = Bun.serve({ port, hostname: "127.0.0.1", idleTimeout: 0, fetch: (req) => this.answer(req) });
    return this;
  }

  stop(): void {
    this.server?.stop(true);
    this.server = null;
  }

  private async answer(req: Request): Promise<Response> {
    const path = new URL(req.url).pathname;
    const text = req.method === "POST" ? await req.text() : "";
    let body: Record<string, unknown> | null = null;
    try {
      body = text ? (JSON.parse(text) as Record<string, unknown>) : null;
    } catch {
      body = null;
    }
    this.seen.push({ path, headers: Object.fromEntries(req.headers), body, at: Date.now() });
    if (path === "/health" || path === "/v1/health") {
      return this.behaviour.healthy
        ? Response.json({ status: "ok" })
        : Response.json({ error: { message: "Loading model", type: "unavailable_error" } }, { status: 503 });
    }
    if (path === "/v1/models" || path === "/models") {
      if (!this.behaviour.healthy) return Response.json({ error: { message: "down" } }, { status: 503 });
      return Response.json({ object: "list", data: [{ id: this.model, object: "model", owned_by: "llamacpp" }] });
    }
    if (path === "/v1/chat/completions" || path === "/chat/completions") return this.complete(body);
    return Response.json({ error: { message: `no route ${path}` } }, { status: 404 });
  }

  private async complete(body: Record<string, unknown> | null): Promise<Response> {
    const b = this.behaviour;
    if (!b.healthy) return Response.json({ error: { message: "down", type: "unavailable_error" } }, { status: 503 });
    if (b.status !== null) {
      return Response.json({ error: { message: `upstream ${this.name} says ${b.status}`, type: "server_error" } }, { status: b.status });
    }
    this.inflight++;
    const release = (() => {
      let done = false;
      return () => {
        if (!done) this.inflight--;
        done = true;
      };
    })();
    await Bun.sleep(b.prefillMs);
    const usage = {
      prompt_tokens: b.usage.prompt,
      completion_tokens: b.usage.completion,
      total_tokens: b.usage.prompt + b.usage.completion,
      prompt_tokens_details: { cached_tokens: b.usage.cached },
    };
    const words = this.reply().split(" ");
    const created = Math.floor(Date.now() / 1000);
    const id = `chatcmpl-${this.name}-${Date.now()}`;
    if (body?.stream !== true) {
      release();
      return Response.json({
        id,
        object: "chat.completion",
        created,
        model: this.model,
        choices: [{ index: 0, message: { role: "assistant", content: words.join(" ") }, finish_reason: "stop" }],
        usage,
        ...(b.timings ? { timings: this.timings() } : {}),
      });
    }
    const includeUsage = (body.stream_options as { include_usage?: boolean } | undefined)?.include_usage === true;
    const chunk = (delta: Record<string, unknown>, finish: string | null, extra: Record<string, unknown> = {}) =>
      `data: ${JSON.stringify({ id, object: "chat.completion.chunk", created, model: this.model, choices: [{ index: 0, delta, finish_reason: finish }], ...extra })}\n\n`;
    const enc = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      start: async (controller) => {
        try {
          controller.enqueue(enc.encode(chunk({ role: "assistant", content: "" }, null)));
          for (let i = 0; i < words.length; i++) {
            if (b.breakAfter !== null && i >= b.breakAfter) {
              release();
              controller.error(new Error("upstream tore the stream"));
              return;
            }
            if (b.stallAfter !== null && i === b.stallAfter) await Bun.sleep(b.stallMs);
            else if (i > 0) await Bun.sleep(b.gapMs);
            controller.enqueue(enc.encode(chunk({ content: (i ? " " : "") + words[i] }, null)));
          }
          controller.enqueue(enc.encode(chunk({}, "stop", b.timings ? { timings: this.timings() } : {})));
          if (includeUsage) {
            controller.enqueue(enc.encode(`data: ${JSON.stringify({ id, object: "chat.completion.chunk", created, model: this.model, choices: [], usage })}\n\n`));
          }
          if (!b.noDone) controller.enqueue(enc.encode("data: [DONE]\n\n"));
          release();
          controller.close();
        } catch {
          release();
        }
      },
      cancel: () => release(),
    });
    return new Response(stream, { headers: { "content-type": "text/event-stream", "cache-control": "no-cache" } });
  }

  private timings(): Record<string, number> {
    const t = this.behaviour.timings!;
    return {
      prompt_n: t.prompt_n,
      cache_n: t.cache_n,
      predicted_n: t.predicted_n,
      prompt_ms: t.prompt_ms,
      predicted_ms: t.predicted_ms,
      prompt_per_second: (t.prompt_n * 1000) / t.prompt_ms,
      predicted_per_second: (t.predicted_n * 1000) / t.predicted_ms,
    };
  }
}
