// The Settings page's pure half: what the parsed topology looks like as a flat list of fields,
// how a field writes back into it, the TOML text the raw view shows, and the three views the page
// ships with. No React, no stores, no network — every one of these is a function of its argument,
// which is what lets the tests exercise the page's whole data story without a DOM.
import type { View } from '@features/views';
import { TOPOLOGY_CHOICES } from '@entities/topology';
import { S } from './strings';

/** The honest empties this page prints. Sentences, not labels, so they live here and not in the
 *  string table (CONTRACTS.md section 4), and each one names the thing that did not answer. */
export const EMPTIES = {
  noConfig: { text: 'no config from the daemon yet', source: 'GET /api/config' },
  noKnobs: { text: 'this view holds no knobs', source: 'the other view tabs' },
  noHeads: { text: 'no heads declared', source: 'splice.toml' },
  topologyPending: { text: 'splice.toml unavailable', source: 'this splice version does not serve splice.toml editing' },
  // V4-175: not "pending" any more. The route is served (V4-129), so the only absence left is the
  // one before the first poll answers, and it names the route rather than a row that closed.
  claudeUnread: { text: 'the mode has not been read yet', source: 'GET /api/claude-head' },
  nothingChanged: { text: 'nothing changed yet', source: 'the loaded topology' },
} as const;

/** The page's saved views. `all knobs` is first because it is the default (CONTRACTS.md section 3). */
export const DEFAULT_VIEWS: readonly View[] = [
  { id: 'all', name: S.allKnobs, layout: 'rack', filter: {}, sort: null, group: null, fields: [] },
  { id: 'live', name: S.live, layout: 'rack', filter: { hot: 'true' }, sort: null, group: null, fields: [] },
  { id: 'restart', name: S.restart, layout: 'rack', filter: { hot: 'false' }, sort: null, group: null, fields: [] },
];

export type TopologyLeaf = {
  /** Dotted path inside the document, with array indices: `heads.claudex.port`, `models[0].id`. */
  path: string;
  value: string | number | boolean;
};

function isTable(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isLeaf(value: unknown): value is string | number | boolean {
  return typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean';
}

function pushLeaves(value: unknown, path: string, out: TopologyLeaf[]): void {
  if (isLeaf(value)) {
    out.push({ path, value });
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((entry, index) => pushLeaves(entry, `${path}[${index}]`, out));
    return;
  }
  if (isTable(value)) {
    for (const [key, child] of Object.entries(value)) {
      pushLeaves(child, path === '' ? key : `${path}.${key}`, out);
    }
  }
}

/** Every scalar the document holds, as a dotted path. Sorted, so the form is stable across reads. */
export function flattenTopology(topology: Record<string, unknown>): TopologyLeaf[] {
  const out: TopologyLeaf[] = [];
  pushLeaves(topology, '', out);
  return out.sort((left, right) => left.path.localeCompare(right.path));
}

type Segment = { key: string; index: number | null };

function segmentsOf(path: string): Segment[] {
  return path.split('.').map((part) => {
    const match = /^(.*)\[(\d+)\]$/.exec(part);
    if (match === null) return { key: part, index: null };
    return { key: match[1], index: Number.parseInt(match[2], 10) };
  });
}

/** Read the value at a dotted path, or undefined when the document has nothing there. */
export function valueAtPath(topology: Record<string, unknown>, path: string): unknown {
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

/**
 * Write a value at a dotted path, returning a new document and never touching the old one.
 *
 * The copy-on-write is the point: the page holds the daemon's document and a draft side by side,
 * and the diff between them is what the operator reviews before a write.
 */
export function setAtPath(
  topology: Record<string, unknown>,
  path: string,
  value: unknown,
): Record<string, unknown> {
  const segments = segmentsOf(path);
  const clone = (node: unknown): unknown => {
    if (Array.isArray(node)) return [...node];
    if (isTable(node)) return { ...node };
    return node;
  };
  const root = clone(topology) as Record<string, unknown>;

  let cursor: Record<string, unknown> | unknown[] = root;
  for (let at = 0; at < segments.length - 1; at += 1) {
    const segment = segments[at];
    const container = cursor as Record<string, unknown>;
    const next = clone(container[segment.key]);
    container[segment.key] = next;
    if (segment.index !== null) {
      const list = next as unknown[];
      const element = clone(list[segment.index]);
      list[segment.index] = element;
      cursor = element as Record<string, unknown>;
    } else {
      cursor = next as Record<string, unknown>;
    }
  }

  const last = segments[segments.length - 1];
  const target = cursor as Record<string, unknown>;
  if (last.index !== null) {
    const list = target[last.key] as unknown[];
    list[last.index] = value;
  } else {
    target[last.key] = value;
  }
  return root;
}

/**
 * Keep the value's own type when an edit round-trips through a text field.
 *
 * A TOML scalar's type is part of the document — `port = 3096` and `port = "3096"` are different
 * keys to the daemon — so the field edits the text and this restores the type it found, falling
 * back to the original rather than inventing a value out of an unparseable edit.
 */
export function coerce(raw: string, reference: string | number | boolean): string | number | boolean {
  if (typeof reference === 'boolean') return raw === 'true';
  if (typeof reference === 'number') {
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : reference;
  }
  return raw;
}

/** How a topology value is edited: a switch, a picker over the daemon's own values, a number, a
 *  line of text, or a list (an array of scalars, typed as one comma-separated line). */
export type TopologyFieldKind = 'flag' | 'choice' | 'number' | 'text' | 'list';

export interface TopologyField {
  /** The key as the file spells it, inside its table: `port`, `share`. */
  key: string;
  /** The dotted path `setAtPath` writes: `heads.claudex.port`, `claude.share`. */
  path: string;
  kind: TopologyFieldKind;
  value: string | number | boolean | readonly (string | number)[];
  /** The daemon's values, for a `choice`. */
  choices?: readonly string[];
}

export interface TopologyTable {
  /** The table's dotted path: `heads.claudex`, `providers.xai.models[0]`. */
  path: string;
  fields: TopologyField[];
}

function isScalarList(value: unknown): value is (string | number)[] {
  return Array.isArray(value) && value.every((entry) => typeof entry === 'string' || typeof entry === 'number');
}

function fieldOf(
  key: string,
  path: string,
  parent: string,
  value: string | number | boolean | (string | number)[],
  providers: readonly string[],
): TopologyField {
  if (Array.isArray(value)) return { key, path, kind: 'list', value };
  if (typeof value === 'boolean') return { key, path, kind: 'flag', value };
  // A head's provider names one of the file's own [providers.*] tables.
  const choices = parent.startsWith('heads.') && key === 'provider'
    ? providers
    : TOPOLOGY_CHOICES[parent.endsWith('auth') && key === 'kind' ? 'auth.kind' : key];
  if (choices !== undefined && typeof value === 'string') return { key, path, kind: 'choice', value, choices };
  return { key, path, kind: typeof value === 'number' ? 'number' : 'text', value };
}

function pushTables(table: Record<string, unknown>, path: string, out: TopologyTable[], providers: readonly string[]): void {
  const fields: TopologyField[] = [];
  const children: [string, Record<string, unknown>][] = [];
  for (const [key, value] of Object.entries(table)) {
    const at = path === '' ? key : `${path}.${key}`;
    if (isLeaf(value) || isScalarList(value)) fields.push(fieldOf(key, at, path, value, providers));
    else if (isTable(value)) children.push([at, value]);
    else if (Array.isArray(value)) {
      value.forEach((entry, index) => {
        if (isTable(entry)) children.push([`${at}[${index}]`, entry]);
      });
    }
  }
  if (fields.length > 0) out.push({ path, fields });
  for (const [at, child] of children) pushTables(child, at, out, providers);
}

/**
 * The document as the tables the file is written in, each with its own fields, in the file's order
 * (console review, 2026-09-24). The form was one full-width box per scalar, labelled with its
 * whole path and repeating `splice.toml / restart to apply` under every one: `claude.share` alone
 * was ten boxes. A table with no values of its own (`heads`, `providers`) is only a heading for
 * the tables under it, so it is not a table here.
 */
export function topologyTables(topology: Record<string, unknown>): TopologyTable[] {
  const out: TopologyTable[] = [];
  const providers = isTable(topology.providers) ? Object.keys(topology.providers) : [];
  pushTables(topology, '', out, providers);
  return out;
}

/** A list field's line back into the array it edits: comma-separated, blanks dropped, and numbers
 *  kept as numbers when the list held numbers. */
export function parseList(raw: string, reference: readonly (string | number)[]): (string | number)[] {
  const numeric = reference.length > 0 && reference.every((entry) => typeof entry === 'number');
  const items = raw.split(',').map((item) => item.trim()).filter((item) => item !== '');
  return numeric ? items.map(Number).filter(Number.isFinite) : items;
}

/** The paths two documents disagree on, in stable order. This is the review before a write. */
export function changedPaths(
  loaded: Record<string, unknown>,
  draft: Record<string, unknown>,
): string[] {
  const paths = new Set(flattenTopology(loaded).map((leaf) => leaf.path));
  for (const leaf of flattenTopology(draft)) paths.add(leaf.path);
  return [...paths]
    .filter((path) => {
      const before = valueAtPath(loaded, path);
      const after = valueAtPath(draft, path);
      return JSON.stringify(before) !== JSON.stringify(after);
    })
    .sort();
}

function tomlValue(value: string | number | boolean): string {
  if (typeof value === 'boolean' || typeof value === 'number') return String(value);
  return `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

function tomlInline(value: readonly unknown[]): string {
  return `[${value
    .map((entry) => (isLeaf(entry) ? tomlValue(entry) : '?'))
    .join(', ')}]`;
}

function writeTable(table: Record<string, unknown>, path: string, lines: string[]): void {
  const scalars: string[] = [];
  const tables: Array<[string, Record<string, unknown>]> = [];
  const arrays: Array<[string, readonly unknown[]]> = [];

  for (const [key, value] of Object.entries(table)) {
    // TOML has no null: an absent key is how "unset" is spelled on disk, so it is not written.
    if (value === null || value === undefined) continue;
    if (isTable(value)) tables.push([key, value]);
    else if (Array.isArray(value)) arrays.push([key, value]);
    else if (isLeaf(value)) scalars.push(`${key} = ${tomlValue(value)}`);
  }

  for (const line of scalars) lines.push(line);
  for (const [key, value] of arrays) {
    const child = path === '' ? key : `${path}.${key}`;
    if (value.every((entry) => isTable(entry))) {
      for (const entry of value) {
        if (lines.length > 0) lines.push('');
        lines.push(`[[${child}]]`);
        writeTable(entry as Record<string, unknown>, child, lines);
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

/**
 * The document as TOML text, for the read-only raw view.
 *
 * THIS IS A RENDERER, NOT A WRITER, and the distinction is load-bearing: it serializes the parsed
 * document it was handed, so the comments and layout of the operator's own file are not in it, and
 * its output is never sent anywhere. Writes go through the forms and the daemon's structured
 * writer, which is the only thing that can round-trip the file faithfully.
 */
export function toToml(topology: Record<string, unknown>): string {
  const lines: string[] = [];
  writeTable(topology, '', lines);
  return `${lines.join('\n')}\n`;
}

/**
 * The document with one head's override of one knob set, or removed when `value` is null.
 *
 * `[heads.<head>.overrides]` holds knob values as TOML strings (`maxInflight = "100"`), the
 * spelling the operator's own file uses and the daemon coerces per knob, so the value is written
 * as its string form. Removing the last override removes the empty table with it, so a reset
 * leaves the file as it was before the override existed. Copy-on-write, like setAtPath.
 */
export function withHeadOverride(
  topology: Record<string, unknown>,
  head: string,
  key: string,
  value: string | number | boolean | null,
): Record<string, unknown> {
  const heads = isTable(topology.heads) ? topology.heads : {};
  const entry = isTable(heads[head]) ? heads[head] : {};
  const kept = Object.entries(isTable(entry.overrides) ? entry.overrides : {}).filter(([name]) => name !== key);
  const overrides: Record<string, unknown> = Object.fromEntries(value === null ? kept : [...kept, [key, String(value)]]);
  const rest = Object.fromEntries(Object.entries(entry).filter(([name]) => name !== 'overrides'));
  const nextEntry: Record<string, unknown> = Object.keys(overrides).length === 0 ? rest : { ...rest, overrides };
  return { ...topology, heads: { ...heads, [head]: nextEntry } };
}

/** The knobs the active view shows. An unknown filter key shows everything, never nothing. */
export function knobsForView<T extends { key: string; hot: boolean }>(
  dispositions: readonly T[],
  view: View,
): T[] {
  const wanted = view.filter.hot;
  if (wanted === undefined) return [...dispositions];
  const hot = wanted === 'true';
  return dispositions.filter((disposition) => disposition.hot === hot);
}
