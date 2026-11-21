// What Claude Code does with a conversation whose tools change, without Claude Code. It declares its tools, sends the
// conversation so far, and answers the tool the model calls. Its ToolSearch answers with a `tool_reference` for each
// tool it loads, and from then on the request's tool list carries that tool flagged `defer_loading`. A server that
// connects mid-conversation adds its tools in a system message (`tool_addition`), and one that leaves takes them away
// (`tool_removal`). The shapes are the ones Claude Code sent the Muse head on 2026-11-16: its tool list is in name
// order, so a loaded tool lands in the middle of it; a placeholder tool is declared `defer_loading` from the start and
// never loaded; and the last block of each request carries the `cache_control` mark, which moves on with every turn.

import { sessionKey, type Launched } from "../../speed/lib/session.ts";

export interface Tool {
  name: string;
  description: string;
  input_schema: Record<string, unknown>;
  defer_loading?: boolean;
}

/** A tool named `name` taking the string properties `props`. Its description carries `[[name]]`, so the stand-in
 *  backend can find it when the backend is sent it under a shortened name. */
export function tool(name: string, props: string[] = ["x"]): Tool {
  return {
    name,
    description: `The ${name} tool. [[${name}]]`,
    input_schema: { type: "object", properties: Object.fromEntries(props.map((p) => [p, { type: "string" }])), required: props },
  };
}

export interface Block {
  type: string;
  [k: string]: unknown;
}

export interface Call {
  id: string;
  name: string;
  input: Record<string, unknown>;
}

/** The model's answer to one request, as Claude Code reads it off the stream. */
export interface Reply {
  status: number;
  raw: string;
  text: string;
  calls: Call[];
}

/** The words that make the stand-in's model call `name` with `input`. */
export function call(name: string, input: Record<string, string>): string {
  return `CALL ${name} WITH ${JSON.stringify(input)} END`;
}

/** About `tokens` tokens of words that never repeat between two conversations: a long file the person pasted. */
export function pasted(tokens: number): string {
  const id = crypto.randomUUID();
  const lines: string[] = [];
  for (let at = 0; lines.join("\n").length < tokens * 4; at++) lines.push(`${id} line ${at}: nothing here needs an answer.`);
  return lines.join("\n");
}

export class Conversation {
  readonly session: string = crypto.randomUUID();
  readonly messages: { role: string; content: string | Block[] }[] = [];
  private readonly tools: Tool[];

  /** A conversation of a session launched as `l`, whose Claude Code starts with `tools`. `agent` makes it a
   *  subagent's. */
  constructor(
    private readonly l: Launched,
    tools: Tool[],
    private readonly agent?: string,
  ) {
    this.tools = [...tools, { ...tool("DeferredToolPlaceholder"), defer_loading: true }].sort(byName);
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
   *  request's tool list flagged `defer_loading`. */
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

  /** Claude Code compacts: the conversation as it stands, with the same system prompt and tools, and one more
   *  message asking for the summary. The conversation itself is left as it was. */
  async compact(): Promise<Reply> {
    const ask = "Your task is to create a detailed summary of the conversation so far, paying close attention to the user's explicit requests and your previous actions.";
    return this.post([...this.messages, { role: "user", content: ask }]);
  }

  private async post(messages: { role: string; content: string | Block[] }[]): Promise<Reply> {
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

  /** Send the conversation as it stands, and add the model's answer to it. */
  async send(): Promise<Reply> {
    const reply = await this.post(this.messages);
    const content: Block[] = [];
    if (reply.text) content.push({ type: "text", text: reply.text });
    for (const c of reply.calls) content.push({ type: "tool_use", id: c.id, name: c.name, input: c.input });
    if (content.length) this.messages.push({ role: "assistant", content });
    return reply;
  }
}

function byName(a: Tool, b: Tool): number {
  return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
}

/** The messages as one request sends them: the last block of the last message carries Claude Code's cache mark. */
function marked(messages: { role: string; content: string | Block[] }[]): { role: string; content: string | Block[] }[] {
  const end = messages.at(-1);
  if (!end) return messages;
  const blocks: Block[] = typeof end.content === "string" ? [{ type: "text", text: end.content }] : end.content.map((b) => ({ ...b }));
  const tail = blocks.at(-1);
  if (tail) tail.cache_control = { type: "ephemeral" };
  return [...messages.slice(0, -1), { role: end.role, content: blocks }];
}

/** The text and the tool calls of one streamed answer. */
function read(status: number, raw: string): Reply {
  const blocks = new Map<number, { type: string; id: string; name: string; text: string; json: string }>();
  for (const chunk of raw.split("\n\n")) {
    const data = chunk.split("\n").find((line) => line.startsWith("data:"));
    if (!data) continue;
    let e: { type?: string; index?: number; content_block?: { type?: string; id?: string; name?: string; text?: string }; delta?: { type?: string; text?: string; partial_json?: string } };
    try {
      e = JSON.parse(data.slice(5));
    } catch {
      continue;
    }
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
  const ordered = [...blocks.entries()].sort((a, b) => a[0] - b[0]).map(([, b]) => b);
  return {
    status,
    raw,
    text: ordered
      .filter((b) => b.type === "text")
      .map((b) => b.text)
      .join(""),
    calls: ordered.filter((b) => b.type === "tool_use").map((b) => ({ id: b.id, name: b.name, input: b.json ? (JSON.parse(b.json) as Record<string, unknown>) : {} })),
  };
}
