// The speed checks' stand-in for the ChatGPT backend a GPT head signed in with ChatGPT talks to: the model catalog
// (`GET /models`, with each model's speed tiers as the real catalog lists them), the Responses route (`POST
// /responses`, answered as a stream that echoes the tier the turn was served at) and the token route a refresh asks.
// It records every request it is sent, so a check can say what reached the backend.

import { chmodSync, mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

/** One speed tier as the catalog lists it: its wire id, its name and what it costs, in the catalog's words. */
export interface Tier {
  id: string;
  name: string;
  description: string;
}

// The catalog's own entries, copied from the live ChatGPT catalog on 2026-11-15.
export const FAST: Tier = { id: "priority", name: "Fast", description: "2x speed, increased usage" };
export const ULTRAFAST: Tier = { id: "ultrafast", name: "Ultrafast", description: "The fastest available responses for latency-sensitive work." };
export const FAST_ONLY: Tier = { id: "priority", name: "Fast", description: "1.5x speed" };

export interface CatalogModel {
  slug: string;
  tiers: Tier[];
}

export interface Seen {
  method: string;
  path: string;
  headers: Record<string, string>;
  body: Record<string, unknown> | null;
}

export class CodexBackend {
  readonly seen: Seen[] = [];
  /** When set, every turn is answered as served at standard speed, whatever it asked for: a downgrade. */
  downgrade = false;
  /** When set, a fast turn is answered as served at "fast", the name OpenAI gives the tier for models after
   *  gpt-5.6, in place of "priority". */
  echoFast = false;
  private readonly server: ReturnType<typeof Bun.serve>;

  private constructor(private readonly models: CatalogModel[]) {
    this.server = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch: (req) => this.answer(req) });
  }

  static start(models: CatalogModel[]): CodexBackend {
    return new CodexBackend(models);
  }

  get url(): string {
    return `http://127.0.0.1:${this.server.port}`;
  }

  /** The turns that reached the backend, in order. */
  turns(): Seen[] {
    return this.seen.filter((s) => s.method === "POST" && s.path === "/responses");
  }

  /** The turns that carried `marker` in their input: one check's own turns. */
  turnsWith(marker: string): Seen[] {
    return this.turns().filter((s) => JSON.stringify(s.body).includes(marker));
  }

  stop(): void {
    this.server.stop(true);
  }

  private async answer(req: Request): Promise<Response> {
    const url = new URL(req.url);
    const headers: Record<string, string> = {};
    req.headers.forEach((v, k) => (headers[k.toLowerCase()] = v));
    let body: Record<string, unknown> | null = null;
    if (req.method === "POST") {
      const text = await req.text();
      try {
        body = JSON.parse(text) as Record<string, unknown>;
      } catch {
        body = { unparsed: text };
      }
    }
    this.seen.push({ method: req.method, path: url.pathname, headers, body });
    if (req.method === "GET" && url.pathname === "/models") return Response.json({ models: this.models.map(catalogEntry) });
    if (req.method === "POST" && url.pathname === "/oauth/token") {
      return Response.json({ access_token: syntheticToken("refreshed"), refresh_token: "mock-refresh", expires_in: 3600 });
    }
    if (req.method === "POST" && url.pathname === "/responses") return this.reply(body ?? {});
    return new Response("unexpected mock route", { status: 404 });
  }

  /** A short streamed answer whose last event names the tier the turn was served at, as the real backend does:
   *  "priority" for a fast turn (asked as "priority" or "fast"), "ultrafast", or "default" for a standard one. */
  private reply(body: Record<string, unknown>): Response {
    const asked = typeof body.service_tier === "string" ? body.service_tier : null;
    const fast = this.echoFast ? "fast" : "priority";
    const served = this.downgrade ? "default" : asked === "priority" || asked === "fast" ? fast : asked === "ultrafast" ? "ultrafast" : "default";
    const id = `resp_${this.seen.length}`;
    const item = { type: "message", id: `msg_${this.seen.length}`, role: "assistant", status: "completed", content: [{ type: "output_text", text: "OK", annotations: [] }] };
    const events = [
      { type: "response.created", response: { id, status: "in_progress", output: [], service_tier: served } },
      { type: "response.output_item.added", output_index: 0, item: { ...item, status: "in_progress", content: [] } },
      { type: "response.content_part.added", output_index: 0, content_index: 0, item_id: item.id, part: { type: "output_text", text: "", annotations: [] } },
      { type: "response.output_text.delta", output_index: 0, content_index: 0, item_id: item.id, delta: "OK" },
      { type: "response.output_text.done", output_index: 0, content_index: 0, item_id: item.id, text: "OK" },
      { type: "response.output_item.done", output_index: 0, item },
      {
        type: "response.completed",
        response: {
          id,
          status: "completed",
          model: body.model,
          service_tier: served,
          output: [item],
          usage: { input_tokens: 1000, input_tokens_details: { cached_tokens: 0 }, output_tokens: 100, output_tokens_details: { reasoning_tokens: 0 }, total_tokens: 1100 },
        },
      },
    ];
    const text = events.map((e) => `event: ${e.type}\ndata: ${JSON.stringify(e)}\n\n`).join("");
    return new Response(text, { headers: { "content-type": "text/event-stream" } });
  }
}

function catalogEntry(m: CatalogModel): Record<string, unknown> {
  return {
    slug: m.slug,
    display_name: m.slug,
    context_window: 400000,
    service_tiers: m.tiers,
    additional_speed_tiers: m.tiers.map((t) => t.name.toLowerCase()),
  };
}

/** An access token the way a ChatGPT login's file holds one: a JWT whose payload says it is good for an hour. */
function syntheticToken(name: string): string {
  const payload = Buffer.from(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 })).toString("base64url");
  return `e30.${payload}.synthetic-${name}`;
}

/** A provider and a head signed in with a ChatGPT login (a stand-in login file under `dir`), on `backend`, serving
 *  `model` under `command`. */
export function gptHead(o: { key: string; command: string; port: number; backend: string; model: string; dir: string; rates?: string }): string {
  mkdirSync(o.dir, { recursive: true });
  const auth = join(o.dir, `${o.key}-auth.json`);
  writeFileSync(
    auth,
    JSON.stringify({ tokens: { access_token: syntheticToken(o.key), refresh_token: "mock-refresh", account_id: o.key }, last_refresh: new Date().toISOString() }),
  );
  chmodSync(auth, 0o600);
  return `
[providers.${o.key}]
dialect = "openai-responses"
base_url = "${o.backend}"
auth = { kind = "chatgpt-oauth", file = "${auth}" }
quirks = { code_mode = false, account_id_header = true, websocket = false, zstd_request_body = false, tool_surface = { enabled = true } }

[[providers.${o.key}.models]]
id = "${o.model}"
context_window = 400000
${o.rates ? `rates = { ${o.rates} }` : ""}

[heads.${o.key}]
provider = "${o.key}"
port = ${o.port}
discovery_prefix = "claude-${o.key}--"
pinned_model = "${o.model}"
[heads.${o.key}.claude]
command = "${o.command}"
`;
}

/** What the daemon needs to reach the stand-in backend for a ChatGPT login: the token route a refresh asks. */
export function gptEnv(backend: string): Record<string, string> {
  return { CODEX_OAUTH_TOKEN_URL: `${backend}/oauth/token`, CLAUDEX_QUOTA_POLL: "off" };
}
