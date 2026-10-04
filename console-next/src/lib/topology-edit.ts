// The parsed splice.toml as something a form edits: its scalars by dotted path, the tables it is written in, a copy-on-write
// setter, the paths two documents disagree on, and a TOML renderer for the review. Pure over the parsed topology.
//
// The renderer is NOT a writer: it prints the parsed document, so the comments and layout of the operator's own file are not in
// it, and nothing sends its output anywhere. Writes go through PUT /api/topology, the daemon's structured writer.
import { TOPOLOGY_CHOICES } from './topology';
import { withHeadOverride } from './settings';

type Table = Record<string, unknown>;
type Scalar = string | number | boolean;

const isTable = (value: unknown): value is Table => typeof value === 'object' && value !== null && !Array.isArray(value);
const isLeaf = (value: unknown): value is Scalar => typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean';
const isScalarList = (value: unknown): value is (string | number)[] => Array.isArray(value) && value.every((entry) => typeof entry === 'string' || typeof entry === 'number');

// ── reading ──────────────────────────────────────────────────────────────────────────────────────

export interface TopologyLeaf {
  /** Dotted path with array indices: `heads.claudex.port`, `models[0].id`. */
  path: string;
  value: Scalar;
}

function pushLeaves(value: unknown, path: string, out: TopologyLeaf[]): void {
  if (isLeaf(value)) out.push({ path, value });
  else if (Array.isArray(value)) value.forEach((entry, index) => pushLeaves(entry, `${path}[${index}]`, out));
  else if (isTable(value)) for (const [key, child] of Object.entries(value)) pushLeaves(child, path === '' ? key : `${path}.${key}`, out);
}

/** Every scalar the document holds, as a dotted path, sorted so the form is stable across reads. */
export function flattenTopology(topology: Table): TopologyLeaf[] {
  const out: TopologyLeaf[] = [];
  pushLeaves(topology, '', out);
  return out.sort((left, right) => left.path.localeCompare(right.path));
}

interface Segment {
  key: string;
  index: number | null;
}

function segmentsOf(path: string): Segment[] {
  return path.split('.').map((part) => {
    const match = /^(.*)\[(\d+)\]$/.exec(part);
    return match === null ? { key: part, index: null } : { key: match[1] ?? '', index: Number.parseInt(match[2] ?? '0', 10) };
  });
}

/** The value at a dotted path, or undefined when the document has nothing there. */
export function valueAtPath(topology: Table, path: string): unknown {
  let cursor: unknown = topology;
  for (const { key, index } of segmentsOf(path)) {
    if (!isTable(cursor)) return undefined;
    cursor = cursor[key];
    if (index !== null) {
      if (!Array.isArray(cursor)) return undefined;
      cursor = cursor[index];
    }
  }
  return cursor;
}

/** The paths two documents disagree on, in stable order: the review before a write. */
export function changedPaths(loaded: Table, draft: Table): string[] {
  const paths = new Set(flattenTopology(loaded).map((leaf) => leaf.path));
  for (const leaf of flattenTopology(draft)) paths.add(leaf.path);
  return [...paths].filter((path) => JSON.stringify(valueAtPath(loaded, path)) !== JSON.stringify(valueAtPath(draft, path))).sort();
}

// ── writing ──────────────────────────────────────────────────────────────────────────────────────

/** A plan's override of one runtime knob: a blank value removes the key rather than writing an empty string. */
export function headOverrideOf(path: string): { head: string; key: string } | null {
  const match = /^heads\.([^.]+)\.overrides\.([^.]+)$/.exec(path);
  return match === null ? null : { head: match[1] ?? '', key: match[2] ?? '' };
}

/** A new document with `value` written at the dotted path; the old one is never touched, so the page can hold both and show the difference. */
export function setAtPath(topology: Table, path: string, value: unknown): Table {
  const override = headOverrideOf(path);
  if (override !== null && (value === '' || value === null)) return withHeadOverride(topology, override.head, override.key, null);
  const clone = (node: unknown): unknown => (Array.isArray(node) ? [...node] : isTable(node) ? { ...node } : node);
  const segments = segmentsOf(path);
  const root = clone(topology) as Table;
  let cursor: Table = root;
  for (const segment of segments.slice(0, -1)) {
    const next = clone(cursor[segment.key]);
    cursor[segment.key] = next;
    if (segment.index === null) {
      cursor = next as Table;
    } else {
      const list = next as unknown[];
      const element = clone(list[segment.index]);
      list[segment.index] = element;
      cursor = element as Table;
    }
  }
  const last = segments.at(-1);
  if (last === undefined) return root;
  if (last.index === null) cursor[last.key] = value;
  else (cursor[last.key] as unknown[])[last.index] = value;
  return root;
}

/** A TOML scalar's type is part of the document (`port = 3096` and `port = "3096"` are different keys), so a text edit keeps the type it found. */
export function coerce(raw: string, reference: Scalar): Scalar {
  if (typeof reference === 'boolean') return raw === 'true';
  if (typeof reference === 'number') {
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : reference;
  }
  return raw;
}

/** A list field's line back into the array it edits: comma separated, blanks dropped, numbers kept as numbers when the list held numbers. */
export function parseList(raw: string, reference: readonly (string | number)[]): (string | number)[] {
  const numeric = reference.length > 0 && reference.every((entry) => typeof entry === 'number');
  const items = raw.split(',').map((item) => item.trim()).filter((item) => item !== '');
  return numeric ? items.map(Number).filter(Number.isFinite) : items;
}

// ── the tables a file is written in ──────────────────────────────────────────────────────────────

export type TopologyFieldKind = 'flag' | 'choice' | 'number' | 'text' | 'list';

export interface TopologyField {
  /** The key as the file spells it inside its table. */
  key: string;
  /** The dotted path `setAtPath` writes. */
  path: string;
  kind: TopologyFieldKind;
  value: Scalar | readonly (string | number)[];
  /** The daemon's own values, for a choice. */
  choices?: readonly string[];
}

export interface TopologyTable {
  /** The table's dotted path: `heads.claudex`, `providers.xai.models[0]`. */
  path: string;
  fields: TopologyField[];
}

function fieldOf(key: string, path: string, parent: string, value: Scalar | (string | number)[], providers: readonly string[]): TopologyField {
  if (Array.isArray(value)) return { key, path, kind: 'list', value };
  if (typeof value === 'boolean') return { key, path, kind: 'flag', value };
  // A plan's provider names one of the file's own [providers.*] tables.
  const choices = parent.startsWith('heads.') && key === 'provider' ? providers : TOPOLOGY_CHOICES[parent.endsWith('auth') && key === 'kind' ? 'auth.kind' : key];
  if (choices !== undefined && typeof value === 'string') return { key, path, kind: 'choice', value, choices };
  return { key, path, kind: typeof value === 'number' ? 'number' : 'text', value };
}

function pushTables(table: Table, path: string, out: TopologyTable[], providers: readonly string[]): void {
  const fields: TopologyField[] = [];
  const children: [string, Table][] = [];
  for (const [key, value] of Object.entries(table)) {
    const at = path === '' ? key : `${path}.${key}`;
    if (isLeaf(value) || isScalarList(value)) fields.push(fieldOf(key, at, path, value, providers));
    else if (isTable(value)) children.push([at, value]);
    else if (Array.isArray(value)) value.forEach((entry, index) => { if (isTable(entry)) children.push([`${at}[${index}]`, entry]); });
  }
  if (fields.length > 0) out.push({ path, fields });
  for (const [at, child] of children) pushTables(child, at, out, providers);
}

/** The document as the tables the file is written in, each with its own fields, in the file's order. A table with no values of its own is
 *  only a heading for the tables under it, so it is not a table here. */
export function topologyTables(topology: Table): TopologyTable[] {
  const out: TopologyTable[] = [];
  pushTables(topology, '', out, isTable(topology['providers']) ? Object.keys(topology['providers']) : []);
  return out;
}

/** The first segment of a table's path: the group it sits under (`heads`, `providers`, `daemon`); '' for the top level. */
export const groupOf = (path: string): string => path.split(/[.[]/)[0] ?? '';

// ── the raw view ─────────────────────────────────────────────────────────────────────────────────

const tomlValue = (value: Scalar): string => (typeof value === 'string' ? `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"` : String(value));
const tomlInline = (value: readonly unknown[]): string => `[${value.map((entry) => (isLeaf(entry) ? tomlValue(entry) : '?')).join(', ')}]`;

function writeTable(table: Table, path: string, lines: string[]): void {
  const scalars: string[] = [];
  const tables: [string, Table][] = [];
  const arrays: [string, readonly unknown[]][] = [];
  for (const [key, value] of Object.entries(table)) {
    // TOML has no null: an absent key is how "unset" is spelled on disk.
    if (value === null || value === undefined) continue;
    if (isTable(value)) tables.push([key, value]);
    else if (Array.isArray(value)) arrays.push([key, value]);
    else if (isLeaf(value)) scalars.push(`${key} = ${tomlValue(value)}`);
  }
  lines.push(...scalars);
  for (const [key, value] of arrays) {
    const child = path === '' ? key : `${path}.${key}`;
    if (value.every(isTable)) {
      for (const entry of value) {
        if (lines.length > 0) lines.push('');
        lines.push(`[[${child}]]`);
        writeTable(entry as Table, child, lines);
      }
    } else {
      lines.push(`${key} = ${tomlInline(value)}`);
    }
  }
  for (const [key, value] of tables) {
    const child = path === '' ? key : `${path}.${key}`;
    if (lines.length > 0) lines.push('');
    lines.push(`[${child}]`);
    writeTable(value, child, lines);
  }
}

/** The document as TOML text, for the read-only raw view. */
export function toToml(topology: Table): string {
  const lines: string[] = [];
  writeTable(topology, '', lines);
  return `${lines.join('\n')}\n`;
}
