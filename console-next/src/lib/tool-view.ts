// A tool call read the way Claude Code shows it. The block names the call in its summary; this module reads what goes in the
// opened body: a command as shell code under its description, an edit as a line diff, a write or a read as code in the file's
// language, and any other input or JSON result as labelled values. Nothing here is markup: the page renders the shapes.
import { toolPath } from './conversation';

/** One labelled value. A string keeps its own lines and quotes; an object or a list is its entries, a list's numbered from 1. */
export interface Field {
  key: string;
  value: { kind: 'text'; text: string } | { kind: 'fields'; fields: Field[] };
}

export type DiffLine = { op: 'same' | 'del' | 'add'; text: string };

/** What a call's input shows. `extra` holds every input field the shape did not use, so nothing the call carried is dropped. */
export type InputView =
  | { kind: 'command'; description: string | null; command: string; extra: Field[] }
  | { kind: 'edit'; path: string; lines: DiffLine[]; language: string | null; everywhere: boolean; extra: Field[] }
  | { kind: 'write'; path: string; content: string; language: string | null; extra: Field[] }
  | { kind: 'read'; path: string; language: string | null; extra: Field[] }
  | { kind: 'fields'; fields: Field[] }
  /** Text that is not a JSON object even when repaired: shown as it came. */
  | { kind: 'text'; text: string };

/** What a result shows: plain text, a file's lines with their numbers apart from the code, or a JSON result's values. */
export type OutputView =
  | { kind: 'text'; text: string }
  | { kind: 'numbered'; numbers: number[]; code: string; language: string | null; rest: string }
  | { kind: 'fields'; fields: Field[] };

/** Tools whose result is a program's or a file's own text, shown as it is even when it happens to be JSON. */
const TEXT_RESULTS = new Set(['Bash', 'BashOutput', 'Read', 'Grep', 'Glob']);

/** A file's language by its extension, as highlight.js names it. A file not listed reads as plain text. */
const LANGUAGE_BY_EXTENSION: Readonly<Record<string, string>> = {
  ts: 'typescript', tsx: 'typescript', mts: 'typescript', cts: 'typescript', js: 'javascript', jsx: 'javascript', mjs: 'javascript',
  cjs: 'javascript', kt: 'kotlin', kts: 'kotlin', java: 'java', py: 'python', rs: 'rust', go: 'go', json: 'json', md: 'markdown',
  sh: 'bash', bash: 'bash', zsh: 'bash', css: 'css', html: 'xml', xml: 'xml', svg: 'xml', toml: 'ini', ini: 'ini', yml: 'yaml',
  yaml: 'yaml', sql: 'sql', diff: 'diff', patch: 'diff',
};

export function languageOf(path: string): string | null {
  const name = path.split('/').pop() ?? path;
  const dot = name.lastIndexOf('.');
  return dot <= 0 ? null : (LANGUAGE_BY_EXTENSION[name.slice(dot + 1).toLowerCase()] ?? null);
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const label = (key: string): string => key.replace(/_+/g, ' ').trim() || key;

function fieldValue(value: unknown): Field['value'] {
  if (typeof value === 'string') return { kind: 'text', text: value };
  if (Array.isArray(value)) return { kind: 'fields', fields: value.map((entry, i) => ({ key: String(i + 1), value: fieldValue(entry) })) };
  if (isRecord(value)) return { kind: 'fields', fields: fieldsOf(value) };
  return { kind: 'text', text: value === undefined ? '' : String(value) };
}

/** An object's entries as labelled values, in the order the call wrote them; `first` names keys to lead with. */
export function fieldsOf(input: Record<string, unknown>, first: readonly string[] = []): Field[] {
  const keys = [...first.filter((key) => key in input), ...Object.keys(input).filter((key) => !first.includes(key))];
  return keys.map((key) => ({ key: label(key), value: fieldValue(input[key]) }));
}

const without = (input: Record<string, unknown>, used: readonly string[]): Record<string, unknown> =>
  Object.fromEntries(Object.entries(input).filter(([key]) => !used.includes(key)));

/** The lines that `fieldsOf` would print, for deciding whether a block is long. */
export function fieldLines(fields: readonly Field[]): number {
  return fields.reduce((sum, field) => sum + (field.value.kind === 'text' ? field.value.text.split('\n').length : 1 + fieldLines(field.value.fields)), 0);
}

const splitLines = (value: string): string[] => (value === '' ? [] : value.replace(/\n$/, '').split('\n'));

/** The most cells the line diff fills before it gives up on context and shows the whole change as removed then added. */
const DIFF_CELLS = 4_000_000;

/** A line diff of [before] against [after]: the shared lines as context, the rest removed and added in place, by the longest
 *  common subsequence of their lines. */
export function lineDiff(before: string, after: string): DiffLine[] {
  const a = splitLines(before);
  const b = splitLines(after);
  let start = 0;
  while (start < a.length && start < b.length && a[start] === b[start]) start++;
  let endA = a.length;
  let endB = b.length;
  while (endA > start && endB > start && a[endA - 1] === b[endB - 1]) {
    endA--;
    endB--;
  }
  const head = a.slice(0, start).map((text): DiffLine => ({ op: 'same', text }));
  const tail = a.slice(endA).map((text): DiffLine => ({ op: 'same', text }));
  const midA = a.slice(start, endA);
  const midB = b.slice(start, endB);
  const width = midB.length + 1;
  if ((midA.length + 1) * width > DIFF_CELLS) {
    return [...head, ...midA.map((text): DiffLine => ({ op: 'del', text })), ...midB.map((text): DiffLine => ({ op: 'add', text })), ...tail];
  }
  // rest[i * width + j]: the longest common run of midA from i and midB from j.
  const rest = new Uint32Array((midA.length + 1) * width);
  for (let i = midA.length - 1; i >= 0; i--) {
    for (let j = midB.length - 1; j >= 0; j--) {
      rest[i * width + j] = midA[i] === midB[j] ? (rest[(i + 1) * width + j + 1] ?? 0) + 1 : Math.max(rest[(i + 1) * width + j] ?? 0, rest[i * width + j + 1] ?? 0);
    }
  }
  const middle: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < midA.length || j < midB.length) {
    const left = midA[i];
    const right = midB[j];
    if (left !== undefined && left === right) {
      middle.push({ op: 'same', text: left });
      i++;
      j++;
    } else if (right === undefined || (left !== undefined && (rest[(i + 1) * width + j] ?? 0) >= (rest[i * width + j + 1] ?? 0))) {
      middle.push({ op: 'del', text: left ?? '' });
      i++;
    } else {
      middle.push({ op: 'add', text: right });
      j++;
    }
  }
  return [...head, ...middle, ...tail];
}

/** The fields of an object the daemon cut off mid-text: open strings, lists and objects are closed, and a key left with no value
 *  is dropped. Null when the text is not the start of a JSON object. */
export function partialObject(text: string): Record<string, unknown> | null {
  if (!text.trimStart().startsWith('{')) return null;
  const closers: string[] = [];
  let inString = false;
  let escaped = false;
  for (const ch of text) {
    if (inString) {
      if (escaped) escaped = false;
      else if (ch === '\\') escaped = true;
      else if (ch === '"') inString = false;
    } else if (ch === '"') inString = true;
    else if (ch === '{') closers.push('}');
    else if (ch === '[') closers.push(']');
    else if (ch === '}' || ch === ']') closers.pop();
  }
  let body = text;
  if (inString) body = `${(escaped ? body.slice(0, -1) : body).replace(/\\u[0-9a-fA-F]{0,3}$/, '')}"`;
  const close = closers.reverse().join('');
  const dangling = body
    .replace(/\s+$/, '')
    .replace(/:\s*[-\w.+]*$/, ':')
    .replace(/([{,])\s*"(?:[^"\\]|\\.)*"\s*:?$/, '$1')
    .replace(/,\s*$/, '');
  for (const candidate of [body, dangling]) {
    try {
      const value = JSON.parse(candidate + close) as unknown;
      if (isRecord(value)) return value;
    } catch {
      // Not this repair; try the next.
    }
  }
  return null;
}

/** The input as an object: parsed whole, or repaired from a cut-off text. `cut` says the repair was needed. */
function inputObject(input: unknown, inputText: string): { fields: Record<string, unknown>; cut: boolean } | null {
  if (isRecord(input)) return { fields: input, cut: false };
  const repaired = partialObject(inputText !== '' ? inputText : typeof input === 'string' ? input : '');
  return repaired === null ? null : { fields: repaired, cut: true };
}

const str = (value: unknown): string | null => (typeof value === 'string' ? value : null);

/** What the opened body shows for a call's input, and whether the daemon kept only the start of it. Null when there is no input. */
export function inputView(tool: string, input: unknown, inputText: string): { view: InputView; cut: boolean } | null {
  const read = inputObject(input, inputText);
  if (read === null) {
    const text = typeof input === 'string' ? input : inputText;
    return text.trim() === '' ? null : { view: { kind: 'text', text }, cut: false };
  }
  const { fields: raw, cut } = read;
  const path = toolPath(tool, raw);
  const command = str(raw.command);
  if (tool === 'Bash' && command !== null) {
    const description = str(raw.description);
    return { view: { kind: 'command', description: description?.trim() ? description : null, command, extra: fieldsOf(without(raw, ['command', 'description'])) }, cut };
  }
  const before = str(raw.old_string);
  const after = str(raw.new_string);
  if (tool === 'Edit' && path !== null && before !== null && after !== null) {
    const extra = fieldsOf(without(raw, ['file_path', 'old_string', 'new_string', 'replace_all']));
    return { view: { kind: 'edit', path, lines: lineDiff(before, after), language: languageOf(path), everywhere: raw.replace_all === true, extra }, cut };
  }
  const content = str(raw.content);
  if (tool === 'Write' && path !== null && content !== null) {
    return { view: { kind: 'write', path, content: content.replace(/\n$/, ''), language: languageOf(path), extra: fieldsOf(without(raw, ['file_path', 'content'])) }, cut };
  }
  if (tool === 'Read' && path !== null) {
    return { view: { kind: 'read', path, language: languageOf(path), extra: fieldsOf(without(raw, ['file_path', 'offset', 'limit'])) }, cut };
  }
  const fields = fieldsOf(raw, tool === 'Grep' || tool === 'Glob' ? ['pattern', 'path'] : []);
  return fields.length === 0 ? null : { view: { kind: 'fields', fields }, cut };
}

/** A line of a read result: its number, then a tab (or the arrow older clients wrote), then the file's line. */
const NUMBERED = /^\s*(\d+)(?:\t|→)(.*)$/;

/** What the opened body shows for a result. A read's numbered lines put their numbers apart from the code; a JSON object or list
 *  from a tool whose result is not a program's text reads as labelled values. */
export function outputView(tool: string, output: string, input: unknown): OutputView {
  if (tool === 'Read') {
    const lines = output.split('\n');
    const numbers: number[] = [];
    const code: string[] = [];
    for (const line of lines) {
      const match = NUMBERED.exec(line);
      if (match === null) break;
      numbers.push(Number(match[1]));
      code.push(match[2] ?? '');
    }
    if (numbers.length > 0) {
      const path = isRecord(input) ? toolPath(tool, input) : null;
      return { kind: 'numbered', numbers, code: code.join('\n'), language: path === null ? null : languageOf(path), rest: lines.slice(numbers.length).join('\n').trim() };
    }
  }
  const trimmed = output.trim();
  if (!TEXT_RESULTS.has(tool) && (trimmed.startsWith('{') || trimmed.startsWith('['))) {
    try {
      const value = JSON.parse(trimmed) as unknown;
      if (isRecord(value)) return { kind: 'fields', fields: fieldsOf(value) };
      if (Array.isArray(value)) return { kind: 'fields', fields: value.map((entry, i) => ({ key: String(i + 1), value: fieldValue(entry) })) };
    } catch {
      // Text that only starts like JSON stays text.
    }
  }
  return { kind: 'text', text: output };
}

/** The lines an input view prints, for deciding whether it opens folded. */
export function inputLines(view: InputView): number {
  switch (view.kind) {
    case 'command':
      return view.command.split('\n').length;
    case 'edit':
      return view.lines.length;
    case 'write':
      return view.content.split('\n').length;
    case 'read':
      return 0;
    case 'fields':
      return fieldLines(view.fields);
    case 'text':
      return view.text.split('\n').length;
  }
}

/** The lines an output view prints. */
export function outputLines(view: OutputView): number {
  switch (view.kind) {
    case 'text':
      return view.text.split('\n').length;
    case 'numbered':
      return view.numbers.length + (view.rest === '' ? 0 : view.rest.split('\n').length);
    case 'fields':
      return fieldLines(view.fields);
  }
}
