// What a request said and what came back, read from its trace records as pure values: the request as
// Claude Code sent it (instructions, messages, tools, settings) and the answer folded from the stream
// the head sent back. Nothing here renders; the widget draws these.
//
// THE REQUEST IS CLAUDE CODE'S: every head receives the Messages request Claude Code sends, whatever
// provider it then speaks to, so one reader serves every head. What the provider received after
// splice translated it is each attempt's own body, which the widget shows as sent.

/** One piece of a message: what a person reads it as, and the text or JSON it holds. */
export type Part =
  | { kind: 'text'; text: string }
  | { kind: 'thinking'; text: string }
  | { kind: 'tool-call'; id: string; name: string; input: string }
  | { kind: 'tool-result'; id: string; name: string | null; text: string; error: boolean }
  | { kind: 'other'; type: string; json: string };

export interface Message {
  role: string;
  parts: Part[];
}

export interface Tool {
  name: string;
  description: string;
  /** The tool's input schema as JSON, or empty for a tool that declares none. */
  schema: string;
}

export interface RequestView {
  instructions: string[];
  messages: Message[];
  tools: Tool[];
  /** The request's other fields (model, max_tokens, thinking, ...), each as the text it holds. */
  settings: [string, string][];
}

/** A request body read: its view, or why it has none, with the text as it was kept. */
export type RequestRead = { view: RequestView } | { unread: 'cut' | 'not-json'; text: string };

export interface AnswerView {
  parts: Part[];
  stopReason: string | null;
  /** The error the stream or the reply carried, in its own words. */
  error: string | null;
}

type Json = Record<string, unknown>;

const isObject = (value: unknown): value is Json => typeof value === 'object' && value !== null && !Array.isArray(value);
const text = (value: unknown): string => (typeof value === 'string' ? value : '');
const pretty = (value: unknown): string => JSON.stringify(value, null, 2);

/** The parsed value of [raw], or undefined when it is not JSON. */
function parse(raw: string): unknown {
  try {
    return JSON.parse(raw) as unknown;
  } catch {
    return undefined;
  }
}

/** A tool result's content as text: a string as it is, text blocks joined, anything else as JSON. */
function resultText(content: unknown): string {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return content === undefined ? '' : pretty(content);
  return content.map((block) => (isObject(block) && block.type === 'text' ? text(block.text) : pretty(block))).join('\n');
}

/** One content block as a part; [names] maps a tool call's id to its tool, so a result names it. */
function partOf(block: unknown, names: ReadonlyMap<string, string>): Part {
  if (!isObject(block)) return { kind: 'other', type: typeof block, json: pretty(block) };
  switch (block.type) {
    case 'text':
      return { kind: 'text', text: text(block.text) };
    case 'thinking':
      return { kind: 'thinking', text: text(block.thinking) };
    case 'tool_use':
    case 'server_tool_use':
      return { kind: 'tool-call', id: text(block.id), name: text(block.name), input: pretty(block.input ?? {}) };
    case 'tool_result': {
      const id = text(block.tool_use_id);
      return { kind: 'tool-result', id, name: names.get(id) ?? null, text: resultText(block.content), error: block.is_error === true };
    }
    default:
      return { kind: 'other', type: text(block.type) || 'block', json: pretty(block) };
  }
}

function blocksOf(content: unknown): unknown[] {
  if (typeof content === 'string') return [{ type: 'text', text: content }];
  return Array.isArray(content) ? content : [];
}

/** The tool each call id named, over every assistant message, so a result later names its tool. */
function toolNames(messages: readonly unknown[]): Map<string, string> {
  const names = new Map<string, string>();
  for (const message of messages) {
    if (!isObject(message)) continue;
    for (const block of blocksOf(message.content)) {
      if (isObject(block) && (block.type === 'tool_use' || block.type === 'server_tool_use')) names.set(text(block.id), text(block.name));
    }
  }
  return names;
}

const VIEWED = new Set(['system', 'messages', 'tools']);

/** A Messages request body, as the trace kept it; [cut] says the trace cut it at its cap. */
export function readRequest(body: string, cut: boolean): RequestRead {
  const value = parse(body);
  if (!isObject(value)) return { unread: cut ? 'cut' : 'not-json', text: body };
  const messages = Array.isArray(value.messages) ? value.messages : [];
  const names = toolNames(messages);
  const system = value.system;
  return {
    view: {
      instructions: typeof system === 'string' ? [system] : blocksOf(system).map((block) => (isObject(block) ? text(block.text) : pretty(block))),
      messages: messages.map((message) => ({
        role: isObject(message) ? text(message.role) : '',
        parts: isObject(message) ? blocksOf(message.content).map((block) => partOf(block, names)) : [],
      })),
      tools: (Array.isArray(value.tools) ? value.tools : []).map((tool) => ({
        name: isObject(tool) ? text(tool.name) || text(tool.type) : '',
        description: isObject(tool) ? text(tool.description) : '',
        schema: isObject(tool) && tool.input_schema !== undefined ? pretty(tool.input_schema) : '',
      })),
      settings: Object.entries(value)
        .filter(([key]) => !VIEWED.has(key))
        .map(([key, field]) => [key, typeof field === 'string' ? field : JSON.stringify(field)]),
    },
  };
}

/** A block the stream is still building: its start, and the text its deltas added. */
interface Building {
  start: Json;
  text: string;
}

function built({ start, text: added }: Building, names: ReadonlyMap<string, string>): Part {
  switch (start.type) {
    case 'text':
      return { kind: 'text', text: text(start.text) + added };
    case 'thinking':
      return { kind: 'thinking', text: text(start.thinking) + added };
    case 'tool_use':
    case 'server_tool_use': {
      const input = parse(added);
      return { kind: 'tool-call', id: text(start.id), name: text(start.name), input: input === undefined ? added : pretty(input) };
    }
    default:
      return partOf(start, names);
  }
}

/** What one delta adds to its block's text. */
function added(delta: unknown): string {
  if (!isObject(delta)) return '';
  if (delta.type === 'text_delta') return text(delta.text);
  if (delta.type === 'thinking_delta') return text(delta.thinking);
  if (delta.type === 'input_json_delta') return text(delta.partial_json);
  return '';
}

/** The events of a server-sent stream, as the JSON each `data:` line holds. */
function events(stream: string): Json[] {
  return stream.split('\n')
    .filter((line) => line.startsWith('data:'))
    .map((line) => parse(line.slice('data:'.length).trim()))
    .filter(isObject);
}

/** The answer a head sent Claude Code: a Messages stream folded into its blocks, or a reply read whole. */
export function readAnswer(body: string, stream: boolean): AnswerView {
  if (!stream) {
    const value = parse(body);
    if (!isObject(value)) return { parts: body === '' ? [] : [{ kind: 'text', text: body }], stopReason: null, error: null };
    const error = isObject(value.error) ? text(value.error.message) || pretty(value.error) : null;
    return { parts: blocksOf(value.content).map((block) => partOf(block, new Map())), stopReason: text(value.stop_reason) || null, error };
  }
  const blocks = new Map<number, Building>();
  let stopReason: string | null = null;
  let error: string | null = null;
  for (const event of events(body)) {
    const index = typeof event.index === 'number' ? event.index : -1;
    if (event.type === 'content_block_start' && isObject(event.content_block)) blocks.set(index, { start: event.content_block, text: '' });
    else if (event.type === 'content_block_delta') {
      const block = blocks.get(index);
      if (block !== undefined) block.text += added(event.delta);
    } else if (event.type === 'message_delta' && isObject(event.delta)) stopReason = text(event.delta.stop_reason) || stopReason;
    else if (event.type === 'error') error = isObject(event.error) ? text(event.error.message) || pretty(event.error) : pretty(event);
  }
  const names = new Map<string, string>();
  const parts = [...blocks.entries()].sort(([left], [right]) => left - right).map(([, block]) => built(block, names));
  return { parts, stopReason, error };
}
