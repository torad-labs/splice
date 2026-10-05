// V4-444: the Models page's one table. Every model every command serves, with its price per million
// input and output tokens, its context window, and the command and account that serve it. A price
// the provider does not declare stays null all the way to the page, so it can never print as a
// rate of zero (the persona walk of 36218a37c found "No price declared" and "$0.000" side by side).
import type { AccountRow } from '../types/accounts';
import type { HeadStatus } from '../types/core';
import type { HeadCatalog } from '../types/models';
import { kindOf } from './fleet';
import { accountEmail, accountName, poolOf } from './accounts';

/** Who pays for a command's tokens: a stored key, the operator's own machine, or a login. */
export type ServedBy =
  | { kind: 'key' }
  | { kind: 'local' }
  | { kind: 'login'; name: string; email: string | null; plan: string | null; others: number }
  | { kind: 'signedOut' }
  | { kind: 'unreported' };

/** Above this many input tokens a provider charges its long-context rates. */
export interface LongContext {
  over: number;
  input: number;
  output: number;
}

export interface ModelTableRow {
  /** Unique per command and model: one model id can be served by several commands. */
  key: string;
  label: string;
  id: string;
  head: string;
  command: string;
  servedBy: ServedBy;
  window: number | null;
  /** USD per million tokens, or null when the provider declares no price. */
  input: number | null;
  output: number | null;
  longContext: LongContext | null;
  pinned: boolean;
  resolved: boolean;
}

export const MODEL_SORTS = ['model', 'command', 'account', 'window', 'input', 'output'] as const;
export type ModelSort = (typeof MODEL_SORTS)[number];
export type SortDirection = 'asc' | 'desc';

const isSort = (value: string | null): value is ModelSort => MODEL_SORTS.some((sort) => sort === value);

/** The table's view as its address holds it: what is searched and how it is sorted. */
export interface ModelTableView {
  query: string;
  sort: ModelSort;
  direction: SortDirection;
}

export function tableViewOf(params: URLSearchParams): ModelTableView {
  const sort = params.get('sort');
  return {
    query: params.get('q') ?? '',
    sort: isSort(sort) ? sort : 'command',
    direction: params.get('dir') === 'desc' ? 'desc' : 'asc',
  };
}

/** The address of a view, defaults left out, so a bare /models is the catalogue in command order. */
export function tableSearchOf(view: ModelTableView): Record<string, string> {
  return {
    ...(view.query === '' ? {} : { q: view.query }),
    ...(view.sort === 'command' ? {} : { sort: view.sort }),
    ...(view.direction === 'asc' ? {} : { dir: 'desc' }),
  };
}

/** A header pressed: the same column flips its direction, another column starts ascending. */
export function sortedBy(view: ModelTableView, sort: ModelSort): ModelTableView {
  return { ...view, sort, direction: view.sort === sort && view.direction === 'asc' ? 'desc' : 'asc' };
}

const longContextOf = (rates: unknown): LongContext | null => {
  if (typeof rates !== 'object' || rates === null || !('long_context' in rates)) return null;
  const tier = rates.long_context as { over_input_tokens?: unknown; input?: unknown; output?: unknown } | null;
  const { over_input_tokens: over, input, output } = tier ?? {};
  return typeof over === 'number' && typeof input === 'number' && typeof output === 'number' ? { over, input, output } : null;
};

/** The selected or last carrying login's displayed name, with provider-verified email kept separately. */
function servedBy(head: HeadStatus, family: string | null, accounts: readonly AccountRow[]): ServedBy {
  const kind = kindOf(head, family);
  if (kind === 'local') return { kind: 'local' };
  if (kind === 'api-key') return { kind: 'key' };
  const pool = poolOf(accounts, head.key);
  const serving = pool.find((account) => account.selected === true) ?? accounts.find((account) => account.heads.includes(head.key) && account.carrying_request === true) ?? (pool.length === 1 ? pool[0] : undefined);
  if (serving === undefined) return { kind: 'unreported' };
  if (!serving.credential_present) return { kind: 'signedOut' };
  return { kind: 'login', name: accountName(serving), email: accountEmail(serving), plan: serving.plan ?? null, others: pool.length - 1 };
}

/** One row per command and model, in the catalogue's own order: commands as the daemon lists them, each command's models as its
 *  catalogue declares them. A catalogue for a command the heads read does not name is still shown, under its own key. */
export function modelRows(
  catalogs: readonly HeadCatalog[],
  heads: readonly HeadStatus[],
  accounts: readonly AccountRow[],
  families: ReadonlyMap<string, string | null | undefined>,
): ModelTableRow[] {
  const byKey = new Map(heads.map((head) => [head.key, head] as const));
  return catalogs.flatMap((catalog) => {
    const head = byKey.get(catalog.head);
    const served: ServedBy = head === undefined ? { kind: 'unreported' } : servedBy(head, families.get(head.key) ?? null, accounts);
    return catalog.models.map((model) => ({
      key: `${catalog.head}\u0000${model.slot ?? ''}\u0000${model.id}`,
      label: model.label === '' ? model.id : model.label,
      id: model.id,
      head: catalog.head,
      command: head?.label ?? catalog.head,
      servedBy: served,
      window: model.context_window,
      input: model.rates?.input ?? null,
      output: model.rates?.output ?? null,
      longContext: longContextOf(model.rates),
      pinned: model.pinned,
      resolved: model.resolved,
    }));
  });
}

const accountText = (served: ServedBy): string => (served.kind === 'login' ? [served.name, served.email, served.plan].join(' ') : served.kind);

/** Rows whose model, id, command or account holds every word of the query, in any case. */
export function searchRows(rows: readonly ModelTableRow[], query: string): ModelTableRow[] {
  const words = query.toLowerCase().split(/\s+/).filter((word) => word !== '');
  if (words.length === 0) return [...rows];
  return rows.filter((row) => {
    const text = [row.label, row.id, row.command, row.head, accountText(row.servedBy)].join(' ').toLowerCase();
    return words.every((word) => text.includes(word));
  });
}

const valueOf = (row: ModelTableRow, sort: ModelSort): string | number | null => {
  switch (sort) {
    case 'model': return row.label.toLowerCase();
    case 'command': return null;
    case 'account': return accountText(row.servedBy).toLowerCase();
    case 'window': return row.window;
    case 'input': return row.input;
    case 'output': return row.output;
  }
};

/** Sorted by one column. A value the provider does not declare goes last in either direction, never read as zero, and rows that tie
 *  keep the catalogue's order. Command order is the catalogue's own, reversed for descending. */
export function sortRows(rows: readonly ModelTableRow[], sort: ModelSort, direction: SortDirection): ModelTableRow[] {
  const sign = direction === 'asc' ? 1 : -1;
  if (sort === 'command') {
    const order = [...new Set(rows.map((row) => row.head))];
    const rank = (row: ModelTableRow): number => order.indexOf(row.head);
    return [...rows].sort((a, b) => sign * (rank(a) - rank(b)));
  }
  return [...rows].sort((a, b) => {
    const x = valueOf(a, sort);
    const y = valueOf(b, sort);
    if (x === null || y === null) return x === y ? 0 : x === null ? 1 : -1;
    return sign * (x < y ? -1 : x > y ? 1 : 0);
  });
}

/** A declared price per million tokens: cents from a cent up, two significant figures under one, and a declared zero as $0.00. */
export function priceText(usd: number): string {
  return usd === 0 || usd >= 0.01 ? `$${usd.toFixed(2)}` : `$${Number(usd.toPrecision(2))}`;
}
