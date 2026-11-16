// The muse-tool-cache cases' stand-in for Meta's Model API, the backend a Muse head talks to: the model list (`GET
// /v1/models`) and the Responses route (`POST /v1/responses`). It does what docs/specs/muse-tool-cache.md ("What
// Meta's API does") says Meta does, and nothing the spec does not say:
//   - it caches a request by prefix, in the order instructions, tools, input, among the requests that carry the same
//     `prompt_cache_key`, and says how much of each request it read from that cache. The prefix is the bytes the
//     request was sent in, never a re-reading of them;
//   - the tools its model may call are the function tools the request's tool list declares outright and the tools a
//     `tool_search_output` in the input hands over, a name handed over again taking its newest definition;
//   - it refuses what Meta refused on 2026-10-27 and 2026-11-16, in Meta's words: an `additional_tools` input item, a
//     tool name past 64 characters, and a tool flagged `defer_loading` in a tool list with no `tool_search` tool.
// Its model calls a tool when the conversation's newest words ask it to (`CALL <tool> WITH <json> END`), if the
// request lets it call that tool and the arguments fit the schema the request gave for it; otherwise it says why.
// It keeps every request it was sent, so a case can say what reached the backend, in which bytes, and what it cost.

import { chmodSync, mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export const MODEL = "muse-wonder-full-1.0";

/** A tool as a Responses request names it. */
export interface WireTool {
  type?: string;
  name?: string;
  description?: string;
  parameters?: { properties?: Record<string, unknown>; required?: string[] };
  defer_loading?: boolean;
  [k: string]: unknown;
}

/** One item of a request's input: the bytes it was sent in, and what they hold. */
export interface Item {
  raw: string;
  value: Record<string, unknown>;
}

/** One request the Responses route was sent, and what it cost. */
export interface Sent {
  /** What Meta answered: 200, or the status of a refusal. */
  status: number;
  /** Meta's words when it refused the request. */
  refusal: string;
  /** The request's `prompt_cache_key`. */
  key: string;
  /** The body, byte for byte. */
  raw: string;
  body: Record<string, unknown>;
  /** The request's tool list, in the bytes it was sent in. */
  tools: string;
  /** The tools that list declares, in order. */
  listed: WireTool[];
  /** The request's input, item by item. */
  items: Item[];
  /** The whole request in tokens: instructions, tools and input, 4 bytes to a token. */
  input: number;
  /** The tokens read from the cache: the longest prefix an earlier request with the same key also sent. */
  cached: number;
  /** The tokens paid in full: `input - cached`. */
  fresh: number;
  /** The names of the tools the model may call on this request, as the backend knows them. */
  callable: string[];
}

const SEPARATOR = "\u0000";
const TOKEN_CHARS = 4;
/** Meta takes a tool name of at most this many characters. */
const NAME_MAX = 64;
const BLANK = " \n\r\t";

/** Where the JSON value that starts at `at` ends. */
function endOf(s: string, at: number): number {
  const c = s[at];
  if (c === '"') {
    for (let i = at + 1; i < s.length; i++) {
      if (s[i] === "\\") i++;
      else if (s[i] === '"') return i + 1;
    }
    throw new Error("a string of the body does not end");
  }
  if (c === "{" || c === "[") {
    let depth = 0;
    for (let i = at; i < s.length; i++) {
      const d = s[i];
      if (d === '"') i = endOf(s, i) - 1;
      else if (d === "{" || d === "[") depth++;
      else if (d === "}" || d === "]") {
        depth--;
        if (depth === 0) return i + 1;
      }
    }
    throw new Error("an object or a list of the body does not end");
  }
  let i = at;
  while (i < s.length && !",}]".includes(s[i] as string) && !BLANK.includes(s[i] as string)) i++;
  return i;
}

function skip(s: string, at: number): number {
  while (at < s.length && BLANK.includes(s[at] as string)) at++;
  return at;
}

/** The text of each element of the JSON list `s`, as it was written. */
export function elements(s: string): string[] {
  const out: string[] = [];
  let i = skip(s, 0);
  if (s[i] !== "[") throw new Error("not a JSON list");
  i = skip(s, i + 1);
  while (s[i] !== "]") {
    const e = endOf(s, i);
    if (e === i) throw new Error("a list of the body is not JSON");
    out.push(s.slice(i, e));
    i = skip(s, e);
    if (s[i] === ",") i = skip(s, i + 1);
    else if (s[i] !== "]") throw new Error("a list of the body is not JSON");
  }
  return out;
}

/** The text of each member of the JSON object `s`, as it was written, by its key. */
export function members(s: string): Map<string, string> {
  const out = new Map<string, string>();
  let i = skip(s, 0);
  if (s[i] !== "{") throw new Error("not a JSON object");
  i = skip(s, i + 1);
  while (s[i] !== "}") {
    const k = endOf(s, i);
    const key: unknown = JSON.parse(s.slice(i, k));
    i = skip(s, k);
    if (typeof key !== "string" || s[i] !== ":") throw new Error("an object of the body is not JSON");
    i = skip(s, i + 1);
    const e = endOf(s, i);
    out.set(key, s.slice(i, e));
    i = skip(s, e);
    if (s[i] === ",") i = skip(s, i + 1);
    else if (s[i] !== "}") throw new Error("an object of the body is not JSON");
  }
  return out;
}

/** Every string a value holds. */
export function strings(v: unknown): string[] {
  if (typeof v === "string") return [v];
  if (Array.isArray(v)) return v.flatMap(strings);
  if (v && typeof v === "object") return Object.values(v).flatMap(strings);
  return [];
}

/** Whether `t` is the tool Claude Code names `name`: under that name, or under a shorter one with the tool's own
 *  description, which carries `[[name]]` (see `tool` in claude.ts). */
export function isTool(t: WireTool, name: string): boolean {
  return t.name === name || (t.description ?? "").includes(`[[${name}]]`);
}

export class Meta {
  private readonly record: Sent[] = [];
  /** Every prefix sent so far, by cache key. */
  private readonly cache = new Map<string, string[]>();
  private readonly server: ReturnType<typeof Bun.serve>;
  private n = 0;

  private constructor() {
    this.server = Bun.serve({ hostname: "127.0.0.1", port: 0, idleTimeout: 120, fetch: (req) => this.answer(req) });
  }

  static start(): Meta {
    return new Meta();
  }

  /** The provider's `base_url`. */
  get url(): string {
    return `http://127.0.0.1:${this.server.port}/v1`;
  }

  /** Every request the Responses route was sent, in order. */
  sent(): Sent[] {
    return [...this.record];
  }

  stop(): void {
    this.server.stop(true);
  }

  private async answer(req: Request): Promise<Response> {
    const url = new URL(req.url);
    if (req.method === "GET" && url.pathname === "/v1/models") return Response.json({ object: "list", data: [{ id: MODEL, object: "model" }] });
    if (req.method === "POST" && url.pathname === "/v1/responses") {
      const raw = await req.text();
      try {
        return this.respond(raw);
      } catch (e) {
        // The stand-in could not read a request: that is the stand-in's failure, and it says so.
        console.error(`the stand-in for Meta could not read a request: ${e instanceof Error ? e.message : String(e)}`);
        return new Response(`the stand-in for Meta could not read the request: ${e instanceof Error ? e.message : String(e)}`, { status: 500 });
      }
    }
    return new Response("the stand-in has no such route", { status: 404 });
  }

  private respond(raw: string): Response {
    let body: Record<string, unknown>;
    try {
      body = JSON.parse(raw) as Record<string, unknown>;
    } catch {
      return refusal({ message: "The request body is not valid JSON.", param: null });
    }
    const top = members(raw);
    const listed = Array.isArray(body.tools) ? (body.tools as WireTool[]) : [];
    const tools = top.get("tools") ?? "[]";
    const toolTexts = Array.isArray(body.tools) ? elements(tools) : [];
    const values = Array.isArray(body.input) ? (body.input as unknown[]) : body.input === undefined ? [] : [body.input];
    const itemTexts = Array.isArray(body.input) ? elements(top.get("input") ?? "[]") : body.input === undefined ? [] : [top.get("input") ?? ""];
    if (toolTexts.length !== listed.length || itemTexts.length !== values.length) throw new Error("its tools or its input were not read whole");
    const items: Item[] = itemTexts.map((text, at) => {
      const v = values[at];
      return { raw: text, value: v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {} };
    });
    const key = typeof body.prompt_cache_key === "string" ? body.prompt_cache_key : "";
    const prefix = [top.get("instructions") ?? '""', ...toolTexts, ...itemTexts].join(SEPARATOR);
    const total = Math.ceil(prefix.length / TOKEN_CHARS);
    const callable = callableTools(listed, items);
    const sent: Sent = { status: 200, refusal: "", key, raw, body, tools, listed, items, input: total, cached: 0, fresh: total, callable: callable.map((t) => t.name ?? "") };
    this.record.push(sent);
    const refused = refuses(listed, items);
    if (refused) {
      sent.status = 400;
      sent.refusal = refused.message;
      return refusal(refused);
    }
    const earlier = this.cache.get(key) ?? [];
    sent.cached = Math.ceil(Math.max(0, ...earlier.map((e) => shared(e, prefix))) / TOKEN_CHARS);
    sent.fresh = total - sent.cached;
    this.cache.set(key, [...earlier, prefix]);
    return this.stream(body, sent, callable, asked(items));
  }

  /** The answer as Meta streams it: one message, or one call of a tool the request lets the model call, then the
   *  usage and the subscription's meter. */
  private stream(body: Record<string, unknown>, sent: Sent, callable: WireTool[], ask: Ask | null): Response {
    const n = ++this.n;
    const id = `resp_${n}`;
    const tool = ask ? callable.find((t) => isTool(t, ask.tool)) : undefined;
    const events: Record<string, unknown>[] = [
      { type: "response.created", response: { id, status: "in_progress", model: body.model, output: [] } },
      { type: "response.in_progress", response: { id, status: "in_progress", model: body.model, output: [] } },
    ];
    let item: Record<string, unknown>;
    if (ask && tool && fits(tool, ask.args)) {
      item = { type: "function_call", id: `fc_${n}`, call_id: `call_${n}`, name: tool.name, arguments: ask.args, status: "completed" };
      events.push(
        { type: "response.output_item.added", output_index: 0, item: { ...item, arguments: "", status: "in_progress" } },
        { type: "response.function_call_arguments.delta", output_index: 0, item_id: item.id, delta: ask.args },
        { type: "response.function_call_arguments.done", output_index: 0, item_id: item.id, arguments: ask.args },
        { type: "response.output_item.done", output_index: 0, item },
      );
    } else {
      const text = !ask ? "OK" : tool ? `THE ARGUMENTS DO NOT FIT ${ask.tool}` : `NO SUCH TOOL ${ask.tool}`;
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
          usage: { input_tokens: sent.input, input_tokens_details: { cached_tokens: sent.cached }, output_tokens: 20, output_tokens_details: { reasoning_tokens: 0 }, total_tokens: sent.input + 20 },
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

/** The tools a `tool_search_output` item hands over. */
export function handed(item: Item): WireTool[] {
  return item.value.type === "tool_search_output" && Array.isArray(item.value.tools) ? (item.value.tools as WireTool[]) : [];
}

/** The tools the model may call: the function tools the tool list declares outright, then the tools each
 *  `tool_search_output` in the input hands over. A name handed over again takes its newest definition. */
function callableTools(listed: WireTool[], items: Item[]): WireTool[] {
  const by = new Map<string, WireTool>();
  for (const t of listed) if (t.type === "function" && t.name && t.defer_loading !== true) by.set(t.name, t);
  for (const item of items) for (const t of handed(item)) if (t.type === "function" && t.name) by.set(t.name, t);
  return [...by.values()];
}

/** Whether `args` fit the schema the backend holds for `tool`: every required property, and none it does not name.
 *  A model calls a tool with the schema it was shown, so a call that fits says which schema that was. */
function fits(tool: WireTool, args: string): boolean {
  let given: string[];
  try {
    given = Object.keys(JSON.parse(args) as Record<string, unknown>);
  } catch {
    return false;
  }
  const known = Object.keys(tool.parameters?.properties ?? {});
  return (tool.parameters?.required ?? []).every((r) => given.includes(r)) && given.every((g) => known.includes(g));
}

interface Refusal {
  message: string;
  param: string | null;
}

/** What Meta answers 400 to, in its words, or null when it takes the request. */
function refuses(listed: WireTool[], items: Item[]): Refusal | null {
  const at = items.findIndex((i) => i.value.type === "additional_tools");
  if (at >= 0) return { message: `\`input[${at}]\` did not match any supported type`, param: `input[${at}]` };
  const long = [...listed, ...items.flatMap(handed)].find((t) => (t.name ?? "").length > NAME_MAX);
  if (long) return { message: `\`name\` must be at most ${NAME_MAX} characters, got ${(long.name ?? "").length}`, param: "name" };
  if (listed.some((t) => t.defer_loading === true) && !listed.some((t) => t.type === "tool_search")) {
    return { message: "Deferred tools require tools.tool_search.", param: "tools.defer_loading" };
  }
  return null;
}

function refusal(r: Refusal): Response {
  return Response.json({ error: { code: null, message: r.message, param: r.param, type: "invalid_request_error" } }, { status: 400 });
}

interface Ask {
  tool: string;
  args: string;
}

/** What the conversation's newest words ask the model to call. Only the newest thing the person or a tool said
 *  counts: once the model has answered it, or a tool's result has come back after it, the ask is spent. What the
 *  backend is told about tools (a tool-search pair, a system or developer message) is not the person speaking. */
function asked(items: Item[]): Ask | null {
  for (let at = items.length - 1; at >= 0; at--) {
    const v = (items[at] as Item).value;
    if (v.type === "tool_search_call" || v.type === "tool_search_output") continue;
    if (v.role === "system" || v.role === "developer") continue;
    if (v.role !== "user") return null;
    const m = /CALL (\S+) WITH (\{.*?\}) END/.exec(strings(v).join("\n"));
    return m ? { tool: m[1] as string, args: m[2] as string } : null;
  }
  return null;
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
