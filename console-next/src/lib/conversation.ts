// A transcript as a conversation. The daemon sends a flat list: what a person or the model said, a tool
// call (role assistant, `tool` set, `result` false, its input as JSON text) and the result that answered it
// (role tool, `result` true). This folds each call with its result into one item, keeps the client's own
// bookkeeping (command wrappers, background-task notices) out of the way, and reads a call's input so a
// block can name what it did in a few words.
import type { SessionEdge, TranscriptMessage } from '../types/sessions';

export type Speaker = 'user' | 'assistant' | 'system';

export type Item =
  | { kind: 'say'; index: number; ts: number | null; who: Speaker; text: string }
  /** A call and its result. `output` is null until a result arrives: the tool is still running. */
  | { kind: 'tool'; index: number; ts: number | null; tool: string; input: unknown; inputText: string; output: string | null; last: boolean }
  /** The client's bookkeeping: a slash command, its output, a background task's notice. Folded, not hidden. */
  | { kind: 'note'; index: number; ts: number | null; label: string; text: string };

const WRAPPER = /^\s*<([a-zA-Z][\w-]*)>/;

/** What the client wrote around a message, by its outer tag: [label] names the tag, [text] is what's inside. */
export function noteOf(text: string): { label: string; text: string } | null {
  const tag = WRAPPER.exec(text)?.[1];
  if (tag === undefined) return null;
  const inner = (name: string): string | null => new RegExp(`<${name}>([\\s\\S]*?)</${name}>`).exec(text)?.[1]?.trim() ?? null;
  switch (tag) {
    case 'task-notification':
      return { label: 'Background task', text: inner('summary') ?? inner('result') ?? text.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim() };
    case 'command-name':
      return { label: 'Command', text: [inner('command-name'), inner('command-args')].filter((part) => part !== null && part !== '').join(' ') };
    case 'local-command-stdout':
    case 'local-command-stderr':
      return { label: 'Command output', text: inner(tag) ?? '' };
    case 'local-command-caveat':
    case 'system-reminder':
      return { label: 'Note', text: inner(tag) ?? '' };
    default:
      return null;
  }
}

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
    const note = noteOf(message.text);
    if (note !== null) {
      items.push({ kind: 'note', index: message.index, ts, ...note });
      continue;
    }
    items.push({ kind: 'say', index: message.index, ts, who: message.role, text: message.text });
  }
  const tail = items.at(-1);
  if (tail?.kind === 'tool') tail.last = true;
  return items;
}

const text = (value: unknown): string | null => (typeof value === 'string' && value.trim() !== '' ? value : null);
/** The first entry of a list of strings, or a string itself. */
const firstOf = (value: unknown): string | null => text(Array.isArray(value) ? value[0] : value);
const firstLine = (value: string): string => value.trim().split('\n', 1)[0] ?? '';

/** What a call acted on, in a few words: the file for a file tool, the first line of a command, the pattern of
 *  a search. Null for a tool with nothing obvious to name; its block then shows the raw input when opened. */
export function toolTarget(tool: string, input: unknown): string | null {
  if (typeof input !== 'object' || input === null) return null;
  const fields = input as Record<string, unknown>;
  const pick = (...names: string[]): string | null => names.map((name) => text(fields[name])).find((value) => value !== null) ?? null;
  switch (tool) {
    case 'Bash':
      return firstLine(pick('command') ?? '') || null;
    case 'Read':
    case 'Edit':
    case 'Write':
    case 'NotebookEdit':
      return pick('file_path', 'notebook_path');
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
    case 'SendMessage':
      return pick('to', 'recipient');
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
export type Timeline =
  | { kind: 'item'; at: number | null; item: Item }
  | { kind: 'handoff'; at: number; edge: SessionEdge };

export function interleave(items: readonly Item[], edges: readonly SessionEdge[]): Timeline[] {
  const pending = [...edges].sort((a, b) => a.at - b.at);
  const out: Timeline[] = [];
  for (const item of items) {
    while (item.ts !== null && pending[0] !== undefined && pending[0].at <= item.ts) {
      const edge = pending.shift();
      if (edge !== undefined) out.push({ kind: 'handoff', at: edge.at, edge });
    }
    out.push({ kind: 'item', at: item.ts, item });
  }
  return [...out, ...pending.map((edge): Timeline => ({ kind: 'handoff', at: edge.at, edge }))];
}
