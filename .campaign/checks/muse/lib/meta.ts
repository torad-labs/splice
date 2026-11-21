// The Muse checks' stand-in for Meta's Model API, the backend a Muse head talks to: the model list (`GET
// /v1/models`) and the Responses route (`POST /v1/responses`). What it does comes from three places:
//   - from Meta's documentation (dev.meta.ai, read 2026-11-16): it caches a request by prefix, in the order
//     instructions, tools, input, among the requests that carry the same `prompt_cache_key`, and says how much of each
//     request it read from that cache;
//   - from Meta's own answers on 2026-11-16 and 2026-10-27: the three requests it refuses, in its words;
//   - from the real head on 2026-11-16, 04:04 to 04:41 CT, which is where its model's three ways with a tool come from:
//       a function tool the tool list declares outright is called, and the call comes back whole;
//       a tool a `tool_search_output` in the input hands over is called under its name, and the call comes back with
//       no arguments (10 of 10);
//       a tool whose definition is written in the conversation is called through the listed tool made for that, the
//       one that takes a tool's name and its arguments, and that call comes back whole (3 of 3);
//       a tool the list declares outright that the conversation later writes again with another schema, with the
//       words that say to call it through that tool, is called with the written schema (06:39 CT, 1 of 1: the call
//       reached the client under the tool's own name with a parameter only the written definition names). Which
//       way that call left Meta was not seen: no trace was saved, the disk being under the trace store's floor. The
//       model here takes the way the written words ask for.
// Its model calls a tool when the conversation's last words ask it to (`CALL <tool> WITH <json> END`), with arguments
// that fit the newest schema the request shows it for that tool.
// It counts the cache to the character. Meta's own count trails the request before by up to a block of 128 tokens
// (6,772 read of the 6,815 sent before).
// It records every request it is sent, so a check can say what reached the backend and what that request cost.

import { chmodSync, mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export const MODEL = "muse-wonder-full-1.0";

/** A tool as a Responses request names it. */
interface WireTool {
  type?: string;
  name?: string;
  description?: string;
  parameters?: unknown;
  defer_loading?: boolean;
}

interface WireItem {
  type?: string;
  role?: string;
  call_id?: string;
  tools?: WireTool[];
  [k: string]: unknown;
}

/** One request the Responses route was sent, and what it cost. */
export interface Turn {
  headers: Record<string, string>;
  body: Record<string, unknown>;
  /** What Meta answered: 200, or the status of a refusal. */
  status: number;
  /** The request's `prompt_cache_key`. */
  key: string;
  /** The whole request in tokens: instructions, tools and input, 4 characters of JSON to a token. */
  input: number;
  /** The tokens read from the cache: the longest prefix an earlier request with the same key also sent. */
  cached: number;
  /** The tokens paid in full: `input - cached`. */
  fresh: number;
  /** The names of the tools the request's tool list declares, in order. */
  listed: string[];
  /** The names of the tools whose call comes back whole on this request: the ones the tool list declares outright,
   *  and the ones written in the conversation when the list holds the tool that calls them. */
  callable: string[];
}

const SEPARATOR = "\u0000";
const TOKEN_CHARS = 4;

/** The item types Meta's Responses route reads in `input`. Anything else is refused. */
const ITEM_TYPES = new Set(["message", "function_call", "function_call_output", "reasoning", "tool_search_call", "tool_search_output"]);

export class MetaBackend {
  readonly seen: { method: string; path: string }[] = [];
  private readonly record: Turn[] = [];
  /** Every prefix sent so far, by cache key. */
  private readonly cache = new Map<string, string[]>();
  private readonly server: ReturnType<typeof Bun.serve>;
  private n = 0;

  private constructor() {
    this.server = Bun.serve({ hostname: "127.0.0.1", port: 0, idleTimeout: 120, fetch: (req) => this.answer(req) });
  }

  static start(): MetaBackend {
    return new MetaBackend();
  }

  /** The provider's `base_url`. */
  get url(): string {
    return `http://127.0.0.1:${this.server.port}/v1`;
  }

  /** Every request the Responses route was sent, in order. */
  turns(): Turn[] {
    return [...this.record];
  }

  /** The requests that carried `marker` anywhere in their input: one conversation's own. */
  turnsWith(marker: string): Turn[] {
    return this.record.filter((t) => JSON.stringify(t.body.input ?? "").includes(marker));
  }

  stop(): void {
    this.server.stop(true);
  }

  private async answer(req: Request): Promise<Response> {
    const url = new URL(req.url);
    this.seen.push({ method: req.method, path: url.pathname });
    if (req.method === "GET" && url.pathname === "/v1/models") return Response.json({ object: "list", data: [{ id: MODEL, object: "model" }] });
    if (req.method === "POST" && url.pathname === "/v1/responses") {
      const headers: Record<string, string> = {};
      req.headers.forEach((v, k) => (headers[k.toLowerCase()] = v));
      let body: Record<string, unknown>;
      try {
        body = (await req.json()) as Record<string, unknown>;
      } catch {
        return refusal({ message: "The request body is not valid JSON.", param: null });
      }
      return this.respond(headers, body);
    }
    return new Response("the stand-in has no such route", { status: 404 });
  }

  private respond(headers: Record<string, string>, body: Record<string, unknown>): Response {
    const tools = Array.isArray(body.tools) ? (body.tools as WireTool[]) : [];
    const input = Array.isArray(body.input) ? (body.input as WireItem[]) : [];
    const key = typeof body.prompt_cache_key === "string" ? body.prompt_cache_key : "";
    const prefix = [JSON.stringify(body.instructions ?? ""), ...tools.map((t) => JSON.stringify(t)), ...input.map((i) => JSON.stringify(i))].join(SEPARATOR);
    const total = Math.ceil(prefix.length / TOKEN_CHARS);
    const model = toolsOf(tools, input);
    const turn: Turn = {
      headers,
      body,
      status: 200,
      key,
      input: total,
      cached: 0,
      fresh: total,
      listed: tools.map((t) => t.name ?? t.type ?? ""),
      callable: [...model.listed, ...(model.via ? model.written : [])].map((t) => t.name ?? ""),
    };
    this.record.push(turn);
    const refused = refuses(tools, input);
    if (refused) {
      turn.status = 400;
      return refusal(refused);
    }
    const earlier = this.cache.get(key) ?? [];
    turn.cached = Math.ceil(Math.max(0, ...earlier.map((e) => shared(e, prefix))) / TOKEN_CHARS);
    turn.fresh = total - turn.cached;
    this.cache.set(key, [...earlier, prefix]);
    return this.stream(body, turn, decide(model, asked(input)));
  }

  /** The answer as Meta streams it: one message, or one call of a tool, then the usage and the subscription's
   *  meter. */
  private stream(body: Record<string, unknown>, turn: Turn, said: Said): Response {
    const n = ++this.n;
    const id = `resp_${n}`;
    const events: Record<string, unknown>[] = [
      { type: "response.created", response: { id, status: "in_progress", model: body.model, output: [] } },
      { type: "response.in_progress", response: { id, status: "in_progress", model: body.model, output: [] } },
    ];
    let item: Record<string, unknown>;
    if ("call" in said) {
      const args = said.args;
      item = { type: "function_call", id: `fc_${n}`, call_id: `call_${n}`, name: said.call, arguments: args, status: "completed" };
      events.push(
        { type: "response.output_item.added", output_index: 0, item: { ...item, arguments: "", status: "in_progress" } },
        // A call that comes back with no arguments carries no delta: Meta sent only the `done` (2026-11-16).
        ...(args === "{}" ? [] : [{ type: "response.function_call_arguments.delta", output_index: 0, item_id: item.id, delta: args }]),
        { type: "response.function_call_arguments.done", output_index: 0, item_id: item.id, arguments: args },
        { type: "response.output_item.done", output_index: 0, item },
      );
    } else {
      const text = said.text;
      item = { type: "message", id: `msg_${n}`, role: "assistant", status: "completed", content: [{ type: "output_text", text, annotations: [] }] };
      events.push(
        { type: "response.output_item.added", output_index: 0, item: { ...item, status: "in_progress", content: [] } },
        { type: "response.content_part.added", output_index: 0, content_index: 0, item_id: item.id, part: { type: "output_text", text: "", annotations: [] } },
        { type: "response.output_text.delta", output_index: 0, content_index: 0, item_id: item.id, delta: text },
        { type: "response.output_text.done", output_index: 0, content_index: 0, item_id: item.id, text },
        { type: "response.content_part.done", output_index: 0, content_index: 0, item_id: item.id, part: { type: "output_text", text, annotations: [] } },
        { type: "response.output_item.done", output_index: 0, item },
      );
    }
    const now = Math.floor(Date.now() / 1000);
    events.push(
      {
        type: "response.completed",
        response: {
          id,
          status: "completed",
          model: body.model,
          output: [item],
          usage: { input_tokens: turn.input, input_tokens_details: { cached_tokens: turn.cached }, output_tokens: 20, output_tokens_details: { reasoning_tokens: 0 }, total_tokens: turn.input + 20 },
        },
      },
      {
        type: "response.subscription_usage",
        subscription: { tier: "stand-in", weekly: { resets_at: now + 86400, used_percent: 1 }, window: { resets_at: now + 3600, used_percent: 1, window_duration_mins: 300 } },
      },
    );
    const text = events.map((e, at) => `event: ${String(e.type)}\ndata: ${JSON.stringify({ ...e, sequence_number: at })}\n\n`).join("");
    return new Response(text, { headers: { "content-type": "text/event-stream" } });
  }
}

/** The length of the prefix two requests share, in characters. */
function shared(a: string, b: string): number {
  const max = Math.min(a.length, b.length);
  let at = 0;
  while (at < max && a.charCodeAt(at) === b.charCodeAt(at)) at++;
  return at;
}

/** What a request shows the model of its tools. */
interface ModelTools {
  /** The function tools the tool list declares outright. */
  listed: WireTool[];
  /** The tools a `tool_search_output` in the input hands over, the newest definition of each name. */
  handed: WireTool[];
  /** The tools whose definition is written in the conversation, the newest definition of each name. */
  written: WireTool[];
  /** The listed tool that calls a written one, the parameter that takes its name and the one that takes its
   *  arguments. Null when the list holds no such tool, or more than one. */
  via: { tool: WireTool; name: string; args: string } | null;
}

function toolsOf(tools: WireTool[], input: WireItem[]): ModelTools {
  const listed = tools.filter((t) => t.type === "function" && !!t.name && t.defer_loading !== true);
  const handed = new Map<string, WireTool>();
  for (const item of input) {
    if (item.type !== "tool_search_output") continue;
    for (const t of item.tools ?? []) if (t.type === "function" && t.name) handed.set(t.name, t);
  }
  return { listed, handed: [...handed.values()], written: writtenTools(input), via: caller(listed) };
}

/** The listed tool made to call a tool the conversation wrote: its parameters are a string, the tool's name, and an
 *  object, the tool's arguments, both required and nothing else. That is the tool Meta took and its model used on the
 *  real head (2026-11-16). */
function caller(listed: WireTool[]): ModelTools["via"] {
  const found = listed.flatMap((tool) => {
    const schema = (tool.parameters ?? {}) as { properties?: Record<string, { type?: unknown }>; required?: string[] };
    const props = Object.entries(schema.properties ?? {});
    const name = props.filter(([, p]) => p?.type === "string").map(([k]) => k);
    const args = props.filter(([, p]) => p?.type === "object").map(([k]) => k);
    const required = schema.required ?? [];
    if (props.length !== 2 || name.length !== 1 || args.length !== 1) return [];
    if (!required.includes(name[0]!) || !required.includes(args[0]!)) return [];
    return [{ tool, name: name[0]!, args: args[0]! }];
  });
  return found.length === 1 ? found[0]! : null;
}

/** The tools whose definition is written in the conversation: every JSON object, in the text of a message or of a
 *  tool's result, that names a tool and gives its schema (`input_schema` or `parameters`). The newest definition of a
 *  name is the one the model holds. */
function writtenTools(input: WireItem[]): WireTool[] {
  const by = new Map<string, WireTool>();
  const read = (v: unknown): void => {
    if (Array.isArray(v)) return v.forEach(read);
    if (!v || typeof v !== "object") return;
    const o = v as Record<string, unknown>;
    const schema = o.input_schema ?? o.parameters;
    if (typeof o.name === "string" && o.name && schema && typeof schema === "object" && !Array.isArray(schema)) {
      by.set(o.name, { type: "function", name: o.name, description: typeof o.description === "string" ? o.description : "", parameters: schema });
      return;
    }
    Object.values(o).forEach(read);
  };
  for (const item of input) {
    const type = item.type ?? (item.role ? "message" : "");
    if (type !== "message" && type !== "function_call_output") continue;
    for (const text of strings(item)) embedded(text).forEach(read);
  }
  return [...by.values()];
}

/** Every JSON object a text holds, outermost first. */
function embedded(text: string): unknown[] {
  const out: unknown[] = [];
  let at = text.indexOf("{");
  while (at >= 0) {
    const end = closing(text, at);
    let parsed: unknown;
    try {
      parsed = end < 0 ? undefined : JSON.parse(text.slice(at, end + 1));
    } catch {
      parsed = undefined;
    }
    if (parsed !== undefined) out.push(parsed);
    at = text.indexOf("{", parsed !== undefined ? end + 1 : at + 1);
  }
  return out;
}

/** Where the brace opened at `from` closes, minding strings, or -1. */
function closing(text: string, from: number): number {
  let depth = 0;
  let quoted = false;
  for (let at = from; at < text.length; at++) {
    const c = text[at];
    if (quoted) {
      if (c === "\\") at++;
      else if (c === '"') quoted = false;
    } else if (c === '"') quoted = true;
    else if (c === "{") depth++;
    else if (c === "}" && --depth === 0) return at;
  }
  return -1;
}

/** What the model says to a request: a call, or words. */
type Said = { call: string; args: string } | { text: string };

/** The model's three ways with the tool it is asked to call, as the real head showed them on 2026-11-16. */
function decide(model: ModelTools, ask: Ask | null): Said {
  if (!ask) return { text: "OK" };
  const named = (t: WireTool) => t.name === ask.tool || (t.description ?? "").includes(`[[${ask.tool}]]`);
  const unfit: Said = { text: `THE ARGUMENTS DO NOT FIT ${ask.tool}` };
  // A tool written in the conversation: called through the listed tool that takes a name and arguments. What the
  // conversation wrote is newer than the list, so it is the definition the model holds, also of a tool the list
  // declares outright.
  const written = model.written.find(named);
  if (written && model.via) {
    if (!fits(written, ask.args)) return unfit;
    return { call: model.via.tool.name!, args: JSON.stringify({ [model.via.name]: written.name, [model.via.args]: JSON.parse(ask.args) }) };
  }
  // A tool the list declares outright: the call comes back whole.
  const listed = model.listed.find(named);
  if (listed) return fits(listed, ask.args) ? { call: listed.name!, args: ask.args } : unfit;
  // A tool a tool-search pair handed over: Meta returns its call with no arguments.
  const handed = model.handed.find(named);
  if (handed) return { call: handed.name!, args: "{}" };
  return { text: `NO SUCH TOOL ${ask.tool}` };
}

/** Whether `args` fit the schema the backend holds for `tool`: every required property, and none it does not name.
 *  A model calls a tool with the schema it was shown, so a call that fits says which schema that was. */
function fits(tool: WireTool, args: string): boolean {
  const schema = (tool.parameters ?? {}) as { properties?: Record<string, unknown>; required?: string[] };
  let given: string[];
  try {
    given = Object.keys(JSON.parse(args) as Record<string, unknown>);
  } catch {
    return false;
  }
  const known = Object.keys(schema.properties ?? {});
  return (schema.required ?? []).every((r) => given.includes(r)) && given.every((g) => known.includes(g));
}

/** Meta takes a tool name of at most this many characters. */
const NAME_MAX = 64;

/** What Meta answers 400 to, in its words, or null when it takes the request: an input item of a type it does not
 *  read (2026-10-27 and 2026-11-16), a tool name past 64 characters and a deferred tool with no tool_search tool
 *  (both 2026-11-16). */
function refuses(tools: WireTool[], input: WireItem[]): Refusal | null {
  for (const [at, item] of input.entries()) {
    const type = item.type ?? (item.role ? "message" : "");
    if (!ITEM_TYPES.has(type)) return { message: `\`input[${at}]\` did not match any supported type`, param: `input[${at}]` };
  }
  const handed = input.filter((i) => i.type === "tool_search_output").flatMap((i) => i.tools ?? []);
  const long = [...tools, ...handed].find((t) => (t.name ?? "").length > NAME_MAX);
  if (long) return { message: `\`name\` must be at most ${NAME_MAX} characters, got ${(long.name ?? "").length}`, param: "name" };
  if (tools.some((t) => t.defer_loading === true) && !tools.some((t) => t.type === "tool_search")) {
    return { message: "Deferred tools require tools.tool_search.", param: "tools.defer_loading" };
  }
  return null;
}

interface Refusal {
  message: string;
  param: string | null;
}

function refusal(r: Refusal): Response {
  return Response.json({ error: { code: null, message: r.message, param: r.param, type: "invalid_request_error" } }, { status: 400 });
}

interface Ask {
  tool: string;
  args: string;
}

/** What the conversation's last words ask the model to call. Only what was said since the model last spoke or a tool
 *  last answered counts: once the model has answered it, or a tool's result has come back after it, the ask is spent. */
function asked(input: WireItem[]): Ask | null {
  for (let at = input.length - 1; at >= 0; at--) {
    const item = input[at]!;
    if (item.type === "tool_search_call" || item.type === "tool_search_output") continue;
    if (item.role === "system" || item.role === "developer") continue;
    if (item.role !== "user") return null;
    const m = /CALL (\S+) WITH (\{.*?\}) END/.exec(strings(item).join("\n"));
    if (m) return { tool: m[1]!, args: m[2]! };
  }
  return null;
}

/** Every string a value holds. */
function strings(v: unknown): string[] {
  if (typeof v === "string") return [v];
  if (Array.isArray(v)) return v.flatMap(strings);
  if (v && typeof v === "object") return Object.values(v).flatMap(strings);
  return [];
}

/** A Muse provider on the stand-in and a head serving it under `command`, signed in with a stand-in login file under
 *  `dir`, as the daemon's own Muse sign-in writes one. */
export function museHead(o: { command: string; port: number; backend: string; dir: string }): string {
  mkdirSync(o.dir, { recursive: true });
  const auth = join(o.dir, "muse.json");
  const now = Math.floor(Date.now() / 1000);
  writeFileSync(
    auth,
    JSON.stringify({
      access_token: "stand-in-access",
      token_type: "Bearer",
      api_key: "stand-in-key",
      is_subs_active: true,
      require_payment: false,
      action_url: null,
      subs_tier_id: "stand-in",
      subs_tier_name: "Stand-in",
      subs_usage: { tier: "stand-in", window: { used_percent: 1, window_duration_mins: 300, resets_at: now + 3600 }, weekly: { used_percent: 1, resets_at: now + 86400 } },
    }),
  );
  chmodSync(auth, 0o600);
  return `
[providers.muse]
dialect = "openai-responses"
base_url = "${o.backend}"
auth = { kind = "muse-oauth", file = "${auth}" }

[[providers.muse.models]]
id = "${MODEL}"
context_window = 1000000

[heads.muse]
provider = "muse"
port = ${o.port}
discovery_prefix = "${o.command}--"
pinned_model = "${MODEL}"
[heads.muse.claude]
command = "${o.command}"
`;
}

/** What keeps the daemon's own traffic off the stand-in's record: no quota poll. */
export const MUSE_ENV: Record<string, string> = { CLAUDEX_QUOTA_POLL: "off" };
