// Claude Code's side of a conversation whose tools change, without Claude Code. It declares its tools, sends the
// conversation so far, and answers the tool the model calls. The shapes are the ones the items name:
//   - its ToolSearch answers a search with a `tool_reference` for each tool it loads, and from then on the request's
//     tool list carries that tool's definition declared `defer_loading`;
//   - a server that connects adds its tools in a system message (`tool_addition`), and one that leaves takes them
//     away in a system message (`tool_removal`).
// The tool list is in name order, so a loaded tool lands in the middle of it. One tool is declared `defer_loading`
// from the first request and never loaded. The last block of each request carries Claude Code's `cache_control`
// mark, which moves on with every request.

import { sessionKey, type Launched } from "../../checks/speed/lib/session.ts";

export interface Tool {
  name: string;
  description: string;
  input_schema: Record<string, unknown>;
  defer_loading?: boolean;
}

/** A tool named `name` taking the string properties `props`, all of them required. Its description carries
 *  `[[name]]`, so the stand-in backend's model can find the tool when the backend is sent it under a shorter name. */
export function tool(name: string, props: string[] = ["x"]): Tool {
  return {
    name,
    description: `The ${name} tool. [[${name}]]`,
    input_schema: { type: "object", properties: Object.fromEntries(props.map((p) => [p, { type: "string" }])), required: props },
  };
}

/** The tool every conversation declares `defer_loading` from its first request and never loads. */
export const NEVER_LOADED = "mcp__idle__never_loaded";

export interface Block {
  type: string;
  [k: string]: unknown;
}

interface Message {
  role: string;
  content: string | Block[];
}

export interface Call {
  id: string;
  name: string;
  input: Record<string, unknown>;
}

/** The model's answer to one request, as Claude Code reads it. */
export interface Reply {
  status: number;
  raw: string;
  text: string;
  calls: Call[];
  /** The error Claude Code was answered with, when it was one. */
  error: string;
}

/** The words that make the stand-in's model call `name` with `input`. */
export function call(name: string, input: Record<string, string>): string {
  return `CALL ${name} WITH ${JSON.stringify(input)} END`;
}

/** About `tokens` tokens of words that never repeat between two conversations: a long file the person pasted. */
export function pasted(tokens: number): string {
  const id = crypto.randomUUID();
  const lines: string[] = [];
  let length = 0;
  for (let at = 0; length < tokens * 4; at++) {
    const line = `${id} line ${at}: nothing here needs an answer.`;
    lines.push(line);
    length += line.length + 1;
  }
  return lines.join("\n");
}

const COMPACT = "Your task is to create a detailed summary of the conversation so far, paying close attention to the user's explicit requests and your previous actions.";

export class Conversation {
  readonly session: string;
  readonly messages: Message[] = [];
  private readonly tools: Tool[];
  private readonly agent: string | undefined;

  /** A conversation of a session launched as `l`, whose Claude Code starts with `tools`. `session` is the Claude
   *  Code session it belongs to (a new one when left out), and `agent` makes it a subagent's conversation. */
  constructor(
    private readonly l: Launched,
    tools: Tool[],
    o: { session?: string; agent?: string } = {},
  ) {
    this.session = o.session ?? crypto.randomUUID();
    this.agent = o.agent;
    this.tools = [...tools, { ...tool(NEVER_LOADED), defer_loading: true }].sort(byName);
  }

  /** The person says `text`. */
  say(text: string): this {
    this.messages.push({ role: "user", content: text });
    return this;
  }

  /** A tool's result for the model's call `id`. `then` rides behind it as text, the way a reminder does. */
  result(id: string, content: string | Block[], then?: string): this {
    const blocks: Block[] = [{ type: "tool_result", tool_use_id: id, content }];
    if (then) blocks.push({ type: "text", text: then });
    this.messages.push({ role: "user", content: blocks });
    return this;
  }

  /** ToolSearch answers the model's call `id` by loading `tools`: the result references each, and each joins the
   *  request's tool list declared `defer_loading`. */
  loaded(id: string, tools: Tool[], then?: string): this {
    for (const t of tools) if (!this.tools.some((x) => x.name === t.name)) this.tools.push({ ...t, defer_loading: true });
    this.tools.sort(byName);
    return this.result(
      id,
      tools.map((t) => ({ type: "tool_reference", tool_name: t.name })),
      then,
    );
  }

  /** A server connects: its tools arrive as definitions in a system message. */
  added(tools: Tool[]): this {
    const blocks: Block[] = tools.map((t) => ({ type: "tool_addition", tool: { type: "tool_definition", definition: t } }));
    this.messages.push({ role: "system", content: [{ type: "text", text: "These tools are now available." }, ...blocks] });
    return this;
  }

  /** A server leaves: the tools it held are taken away by name in a system message. */
  removed(names: string[]): this {
    const blocks: Block[] = names.map((name) => ({ type: "tool_removal", tool: { type: "tool_reference", name } }));
    this.messages.push({ role: "system", content: [{ type: "text", text: "These tools are no longer available." }, ...blocks] });
    return this;
  }

  /** Send the conversation as it stands, and add the model's answer to it. */
  async send(): Promise<Reply> {
    const reply = await this.post(this.messages);
    const content: Block[] = [];
    if (reply.text) content.push({ type: "text", text: reply.text });
    for (const c of reply.calls) content.push({ type: "tool_use", id: c.id, name: c.name, input: c.input });
    if (content.length) this.messages.push({ role: "assistant", content });
    return reply;
  }

  /** Claude Code compacts: the conversation as it stands, with the same system prompt and tools, and one more
   *  message asking for the summary. The conversation itself is left as it was. */
  compact(): Promise<Reply> {
    return this.post([...this.messages, { role: "user", content: COMPACT }]);
  }

  private async post(messages: Message[]): Promise<Reply> {
    const headers: Record<string, string> = {
      "content-type": "application/json",
      "anthropic-version": "2023-06-01",
      "x-api-key": sessionKey(this.l),
      "x-claude-code-session-id": this.session,
    };
    if (this.agent) headers["x-claude-code-agent-id"] = this.agent;
    const r = await fetch(`${this.l.sets.ANTHROPIC_BASE_URL}/v1/messages?beta=true`, {
      method: "POST",
      headers,
      body: JSON.stringify({
        model: this.l.sets.ANTHROPIC_MODEL,
        max_tokens: 1024,
        stream: true,
        system: [{ type: "text", text: "You are Claude Code, working in a terminal." }],
        tools: this.tools,
        messages: marked(messages),
      }),
    });
    return read(r.status, await r.text());
  }
}

function byName(a: Tool, b: Tool): number {
  return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
}

/** The messages as one request sends them: the last block of the last message carries Claude Code's cache mark. */
function marked(messages: Message[]): Message[] {
  const end = messages.at(-1);
  if (!end) return messages;
  const blocks: Block[] = typeof end.content === "string" ? [{ type: "text", text: end.content }] : end.content.map((b) => ({ ...b }));
  const tail = blocks.at(-1);
  if (tail) tail.cache_control = { type: "ephemeral" };
  return [...messages.slice(0, -1), { role: end.role, content: blocks }];
}

interface StreamEvent {
  type?: string;
  index?: number;
  content_block?: { type?: string; id?: string; name?: string; text?: string };
  delta?: { type?: string; text?: string; partial_json?: string };
  error?: { type?: string; message?: string };
}

/** The text, the tool calls and the error of one answer: Anthropic's stream, or its error body. */
function read(status: number, raw: string): Reply {
  const blocks = new Map<number, { type: string; id: string; name: string; text: string; json: string }>();
  let error = "";
  for (const chunk of raw.split(/\r?\n\r?\n/)) {
    const data = chunk.split(/\r?\n/).find((line) => line.startsWith("data:"));
    if (!data) continue;
    let e: StreamEvent;
    try {
      e = JSON.parse(data.slice(5)) as StreamEvent;
    } catch {
      continue;
    }
    if (e.type === "error" && e.error) error = `${e.error.type ?? "error"}: ${e.error.message ?? ""}`;
    if (e.type === "content_block_start" && e.index !== undefined && e.content_block) {
      const b = e.content_block;
      blocks.set(e.index, { type: b.type ?? "", id: b.id ?? "", name: b.name ?? "", text: b.text ?? "", json: "" });
    }
    const block = e.index === undefined ? undefined : blocks.get(e.index);
    if (e.type === "content_block_delta" && block && e.delta) {
      if (e.delta.type === "text_delta") block.text += e.delta.text ?? "";
      if (e.delta.type === "input_json_delta") block.json += e.delta.partial_json ?? "";
    }
  }
  if (!blocks.size && !error) {
    try {
      const j = JSON.parse(raw) as StreamEvent;
      if (j.error) error = `${j.error.type ?? "error"}: ${j.error.message ?? ""}`;
    } catch {}
  }
  const ordered = [...blocks.entries()].sort((a, b) => a[0] - b[0]).map(([, b]) => b);
  return {
    status,
    raw,
    text: ordered
      .filter((b) => b.type === "text")
      .map((b) => b.text)
      .join(""),
    calls: ordered.filter((b) => b.type === "tool_use").map((b) => ({ id: b.id, name: b.name, input: input(b.json) })),
    error,
  };
}

/** A call's arguments, as Claude Code reads them off the stream. */
function input(json: string): Record<string, unknown> {
  if (!json) return {};
  try {
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return { "arguments that are not JSON": json };
  }
}
