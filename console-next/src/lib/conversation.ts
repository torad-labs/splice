// A transcript as a conversation. The daemon sends a flat list: what a person or the model said, a tool
// call (role assistant, `tool` set, `result` false, its input as JSON text) and the result that answered it
// (role tool, `result` true). This folds each call with its result into one item, keeps the client's own
// bookkeeping (command wrappers, background-task notices) out of the way, and reads a call's input so a
// block can name what it did in a few words.
import type { SessionEdge, TranscriptMessage } from '../types/sessions';
import { cleanMessage } from './message';
import { MSG } from './words-message';

export type Speaker = 'user' | 'assistant' | 'system';

export type Item =
  | { kind: 'say'; index: number; ts: number | null; who: Speaker; text: string }
  /** A call and its result. `output` is null until a result arrives: the tool is still running. */
  | { kind: 'tool'; index: number; ts: number | null; tool: string; input: unknown; inputText: string; output: string | null; last: boolean }
  /** A message another session sent this one, from its sender, never a person's own words. */
  | { kind: 'peer'; index: number; ts: number | null; from: string; text: string }
  /** The client's bookkeeping as one quiet line: a slash command (its output folded into `detail`), a background task's notice. */
  | { kind: 'note'; index: number; ts: number | null; label: string; text: string; line: string; detail: string | null };

function parseInput(text: string): unknown {
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return text;
  }
}

/** Fold a transcript page into items, oldest first. A result joins the newest still-open call of the same
 *  tool; a result with no call to join (its call was on an earlier page) is an item of its own, never lost. */
export function foldTranscript(messages: readonly TranscriptMessage[]): Item[] {
  const items: Item[] = [];
  const open: Extract<Item, { kind: 'tool' }>[] = [];
  for (const message of messages) {
    const ts = message.ts ?? null;
    if (message.tool !== undefined && message.result !== true) {
      const call: Extract<Item, { kind: 'tool' }> = {
        kind: 'tool', index: message.index, ts, tool: message.tool, input: parseInput(message.text), inputText: message.text, output: null, last: false,
      };
      items.push(call);
      open.push(call);
      continue;
    }
    if (message.tool !== undefined) {
      const at = open.findLastIndex((call) => call.tool === message.tool);
      const call = at === -1 ? undefined : open[at];
      if (call !== undefined) {
        call.output = message.text;
        open.splice(at, 1);
      } else {
        items.push({ kind: 'tool', index: message.index, ts, tool: message.tool, input: null, inputText: '', output: message.text, last: false });
      }
      continue;
    }
    if (message.role === 'tool') continue;
    const cleaned = cleanMessage(message.text);
    if (cleaned.kind === 'hidden') continue;
    if (cleaned.kind === 'peer') {
      items.push({ kind: 'peer', index: message.index, ts, from: cleaned.from, text: cleaned.text });
      continue;
    }
    if (cleaned.kind === 'event') {
      const tail = items.at(-1);
      if (cleaned.label === MSG.output && tail?.kind === 'note' && tail.label === MSG.ran && tail.detail === null) {
        tail.detail = cleaned.text;
        continue;
      }
      items.push({ kind: 'note', index: message.index, ts, label: cleaned.label, text: cleaned.text, line: cleaned.line, detail: null });
      continue;
    }
    items.push({ kind: 'say', index: message.index, ts, who: message.role, text: cleaned.text });
  }
  const tail = items.at(-1);
  if (tail?.kind === 'tool') tail.last = true;
  return items;
}

const text = (value: unknown): string | null => (typeof value === 'string' && value.trim() !== '' ? value : null);
/** The first entry of a list of strings, or a string itself. */
const firstOf = (value: unknown): string | null => text(Array.isArray(value) ? value[0] : value);
const firstLine = (value: string): string => value.trim().split('\n', 1)[0] ?? '';

/** The file tools, whose input names one file by its path. */
const FILE_TOOLS = new Set(['Read', 'Edit', 'Write', 'NotebookEdit']);

/** The path a file tool acted on, whole. Null for any other call. */
export function toolPath(tool: string, input: unknown): string | null {
  if (!FILE_TOOLS.has(tool) || typeof input !== 'object' || input === null) return null;
  const { file_path: file, notebook_path: notebook } = input as Record<string, unknown>;
  return text(file) ?? text(notebook);
}

/** What a call acted on, in a few words: the file's name for a file tool, the first line of a command, the pattern of
 *  a search. Null for a tool with nothing obvious to name; its block then shows the raw input when opened. */
export function toolTarget(tool: string, input: unknown): string | null {
  if (typeof input !== 'object' || input === null) return null;
  const fields = input as Record<string, unknown>;
  const pick = (...names: string[]): string | null => names.map((name) => text(fields[name])).find((value) => value !== null) ?? null;
  switch (tool) {
    case 'Bash':
      return pick('description') ?? (firstLine(pick('command') ?? '') || null);
    case 'Read':
    case 'Edit':
    case 'Write':
    case 'NotebookEdit': {
      const path = pick('file_path', 'notebook_path');
      return path === null ? null : (path.replace(/\/+$/, '').split('/').pop() ?? path);
    }
    case 'Grep':
    case 'Glob':
      return pick('pattern');
    case 'WebFetch':
      return pick('url');
    case 'WebSearch':
      return pick('query');
    case 'Agent':
    case 'Task':
      return pick('description');
    case 'SendMessage': {
      const to = pick('to', 'recipient');
      // A socket address names a session to the machine, not to a person.
      return to === null ? null : MSG.sentTo(/^uds:|^\/|^[a-z]+:\/\//.test(to) ? MSG.peer : to);
    }
    case 'Skill':
      return pick('skill');
    case 'TaskStop':
      return pick('task_id', 'shell_id');
    case 'SendUserFile':
      return firstOf(fields.files);
    case 'AskUserQuestion':
      return firstOf((Array.isArray(fields.questions) ? (fields.questions[0] as Record<string, unknown> | undefined)?.question : null) ?? null);
    default:
      return pick('file_path', 'path', 'command', 'query', 'pattern', 'description', 'name');
  }
}

/** A shell command in full, for the body of a row that names what it did in words: null when the row has no such
 *  words (its own text already is the command) or the call is not a command. */
export function toolCommand(tool: string, input: unknown): string | null {
  if (tool !== 'Bash' || typeof input !== 'object' || input === null) return null;
  const { command, description } = input as Record<string, unknown>;
  return text(description) !== null ? text(command) : null;
}

/** What a tool call did, from the input as the daemon kept it in a turn's conversation: a JSON object as text. A cut-off object
 *  still gives its description, else the opening of its command. Null when the text names neither. */
export function callTarget(tool: string, inputText: string): string | null {
  try {
    return toolTarget(tool, JSON.parse(inputText));
  } catch {
    const opening = (key: string): string | undefined => new RegExp(`"${key}":"((?:[^"\\\\]|\\\\.)*)`).exec(inputText)?.[1];
    const said = opening('description') ?? opening('command');
    return said === undefined ? null : firstLine(said.replace(/\\n/g, '\n').replace(/\\(.)/g, '$1')) || null;
  }
}

/** A tool's name as a person reads it: `mcp__ast-grep__find_code_by_rule` is `ast-grep · find code by rule`. */
export function toolLabel(tool: string): string {
  const parts = /^mcp__(.+?)__(.+)$/.exec(tool);
  if (parts === null) return tool;
  const spaced = (id: string): string => id.replace(/_+/g, ' ').trim();
  const server = spaced((parts[1] ?? '').replace(/^plugin_/, '')).split(' ');
  const half = server.length / 2;
  const once = Number.isInteger(half) && server.slice(0, half).join(' ') === server.slice(half).join(' ');
  return `${(once ? server.slice(0, half) : server).join(' ')} · ${spaced(parts[2] ?? '')}`;
}

/** The lines an Edit removed and added, for a diff. Null for a call that is not an edit or whose input is not
 *  the edit shape; the caller shows the input as it came. */
export function editDiff(tool: string, input: unknown): { removed: string[]; added: string[] } | null {
  if (tool !== 'Edit' || typeof input !== 'object' || input === null) return null;
  const { old_string: before, new_string: after } = input as Record<string, unknown>;
  if (typeof before !== 'string' || typeof after !== 'string') return null;
  const lines = (value: string): string[] => (value === '' ? [] : value.replace(/\n$/, '').split('\n'));
  return { removed: lines(before), added: lines(after) };
}

/** A written file's content, or null when the call is not a write. */
export function writtenContent(tool: string, input: unknown): string | null {
  if (tool !== 'Write' || typeof input !== 'object' || input === null) return null;
  return text((input as Record<string, unknown>).content);
}

/** The figures a finished result gives in a line: its size. A result that has not come is null, never 0. */
export function outputSize(output: string | null): { lines: number; chars: number } | null {
  if (output === null) return null;
  return { lines: output === '' ? 0 : output.split('\n').length, chars: output.length };
}

/** Items in the order they happened, with each hand-off from [edges] placed by its time among them. An edge
 *  with no text to show still takes its place, and a hand-off that predates the first message comes first. */
export type Timeline<E extends SessionEdge = SessionEdge> =
  | { kind: 'item'; at: number | null; item: Item }
  | { kind: 'handoff'; at: number; edge: E };

/** A hand-off the receiver's own transcript already shows, as a message from its sender, is not shown twice: an `in` edge within two
 *  minutes of such a message whose sender is the same name, or one the registry no longer names, is that message. */
export function withoutEchoed<E extends SessionEdge>(edges: readonly E[], items: readonly Item[], nameOf: (edge: E) => string | null): E[] {
  const said = items.flatMap((item) => (item.kind === 'peer' && item.ts !== null ? [{ from: item.from.toLowerCase(), at: item.ts }] : []));
  return edges.filter((edge) => {
    if (edge.direction !== 'in') return true;
    const name = nameOf(edge)?.toLowerCase() ?? null;
    return !said.some((message) => Math.abs(message.at - edge.at) <= 120_000 && (name === null || name === message.from));
  });
}

export function interleave<E extends SessionEdge>(items: readonly Item[], edges: readonly E[]): Timeline<E>[] {
  const pending = [...edges].sort((a, b) => a.at - b.at);
  const out: Timeline<E>[] = [];
  for (const item of items) {
    while (item.ts !== null && pending[0] !== undefined && pending[0].at <= item.ts) {
      const edge = pending.shift();
      if (edge !== undefined) out.push({ kind: 'handoff', at: edge.at, edge });
    }
    out.push({ kind: 'item', at: item.ts, item });
  }
  return [...out, ...pending.map((edge): Timeline<E> => ({ kind: 'handoff', at: edge.at, edge }))];
}
