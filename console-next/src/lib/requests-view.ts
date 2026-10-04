// V4-444: the Requests view, kept in the page's address so a link opens the same rows. Usage links here with a span
// (`since` and `until`, or a `day` in the viewer's zone) and a selector (`head`, `model`, `account`, `unattributed`); a
// command's Failed count links here with `status=failed`. Every selector runs on the daemon (TurnsFilter.kt), before its
// newest-n clamp, so the list and its count agree however many other rows the window holds.
import { WINDOW_MS } from './turns-page';
import { PERF_WINDOWS } from '../types/perf';
import type { PerfTurnsFilter, PerfWindowLabel } from '../types/perf';

export const REQUESTS_STATUSES = ['all', 'failed', 'stopped', 'compacted'] as const;
export type RequestsStatus = (typeof REQUESTS_STATUSES)[number];

export const SELECTORS = ['head', 'model', 'account', 'session'] as const;
export type Selector = (typeof SELECTORS)[number];

const UNATTRIBUTED = ['model', 'account'] as const;
type Unattributed = (typeof UNATTRIBUTED)[number];

/** A rolling window back from now, or a fixed span: `until` exclusive and null for now, `day` when the span is one. */
export type RequestsRange =
  | { kind: 'last'; window: PerfWindowLabel }
  | { kind: 'span'; since: number; until: number | null; day: string | null };

export type RequestsView = { range: RequestsRange; status: RequestsStatus; unattributed: Unattributed | null } & Record<Selector, string | null> & {
  /** The first part of the address this page cannot read. The page says so instead of listing a window nobody asked for. */
  unread: { param: string; value: string } | null;
};

const DAY = /^(\d{4})-(\d{2})-(\d{2})$/;

/** A calendar day in the viewer's zone, midnight to the next midnight: 23 or 25 hours on the days the clocks move. */
function dayRange(text: string): { since: number; until: number } | null {
  const parts = DAY.exec(text);
  if (parts === null) return null;
  const [year, month, date] = [Number(parts[1]), Number(parts[2]) - 1, Number(parts[3])];
  const start = new Date(year, month, date);
  if (start.getFullYear() !== year || start.getMonth() !== month || start.getDate() !== date) return null;
  return { since: start.getTime(), until: new Date(year, month, date + 1).getTime() };
}

const instant = (text: string | null): number | null => (text !== null && /^\d+$/.test(text) ? Number(text) : null);

type Read<T> = { value: T } | { unread: { param: string; value: string } };

function rangeOf(params: URLSearchParams): Read<RequestsRange> {
  const since = params.get('since');
  const until = params.get('until');
  const day = params.get('day');
  if (since !== null) {
    const from = instant(since);
    if (from === null) return { unread: { param: 'since', value: since } };
    const to = until === null ? null : instant(until);
    if (until !== null && to === null) return { unread: { param: 'until', value: until } };
    return { value: { kind: 'span', since: from, until: to, day: null } };
  }
  if (until !== null) return { unread: { param: 'since', value: '' } };
  if (day !== null) {
    const span = dayRange(day);
    return span === null ? { unread: { param: 'day', value: day } } : { value: { kind: 'span', ...span, day } };
  }
  return { value: { kind: 'last', window: PERF_WINDOWS.find((label) => label === params.get('window')) ?? '1h' } };
}

function oneOf<T extends string>(params: URLSearchParams, param: string, allowed: readonly T[], absent: T | null): Read<T | null> {
  const text = params.get(param);
  if (text === null || text === '') return { value: absent };
  const found = allowed.find((value) => value === text);
  return found === undefined ? { unread: { param, value: text } } : { value: found };
}

export function viewOf(params: URLSearchParams): RequestsView {
  const range = rangeOf(params);
  const status = oneOf(params, 'status', REQUESTS_STATUSES, 'all');
  const unattributed = oneOf(params, 'unattributed', UNATTRIBUTED, null);
  const unread = [range, status, unattributed].flatMap((read) => ('unread' in read ? [read.unread] : []))[0] ?? null;
  const given = (param: Selector): string | null => {
    const text = params.get(param);
    return text === null || text === '' ? null : text;
  };
  return {
    range: 'value' in range ? range.value : { kind: 'last', window: '1h' },
    status: ('value' in status ? status.value : null) ?? 'all',
    head: given('head'),
    model: given('model'),
    account: given('account'),
    session: given('session'),
    unattributed: 'value' in unattributed ? unattributed.value : null,
    unread,
  };
}

/** The address a view is written as, in a fixed order, with every default left out. */
export function searchOf(view: RequestsView): Record<string, string> {
  const out: Record<string, string> = {};
  const { range } = view;
  if (range.kind === 'last' && range.window !== '1h') out.window = range.window;
  if (range.kind === 'span' && range.day !== null) out.day = range.day;
  if (range.kind === 'span' && range.day === null) {
    out.since = String(range.since);
    if (range.until !== null) out.until = String(range.until);
  }
  if (view.status !== 'all') out.status = view.status;
  for (const selector of SELECTORS) {
    const value = view[selector];
    if (value !== null) out[selector] = value;
  }
  if (view.unattributed !== null) out.unattributed = view.unattributed;
  return out;
}

/** The Requests address for a view. */
export function requestsHref(view: RequestsView): string {
  const query = new URLSearchParams(searchOf(view)).toString();
  return query === '' ? '/requests' : `/requests?${query}`;
}

/** Whether the list shows less than the whole range: a status or a selector is set. */
export const narrowed = (view: RequestsView): boolean =>
  view.status !== 'all' || view.unattributed !== null || SELECTORS.some((selector) => view[selector] !== null);

/** What the daemon is asked for a view. A window is a rolling read, so a page left open keeps showing the last hour. The
 *  steps splice answered itself are left out: they are not requests to a model. */
export function askOf(view: RequestsView): { head: string | undefined; last?: number; since?: number; until?: number; filter: PerfTurnsFilter } {
  const filter: PerfTurnsFilter = {
    ...(view.status === 'failed' || view.status === 'stopped' ? { outcome: view.status } : {}),
    ...(view.status === 'compacted' ? { compact: true } : {}),
    ...(view.model === null ? {} : { model: view.model }),
    ...(view.account === null ? {} : { account: view.account }),
    ...(view.session === null ? {} : { session: view.session }),
    ...(view.unattributed === null ? {} : { unattributed: view.unattributed }),
    local: false,
  };
  const head = view.head ?? undefined;
  const { range } = view;
  if (range.kind === 'last') return { head, last: WINDOW_MS[range.window], filter };
  return { head, since: range.since, ...(range.until === null ? {} : { until: range.until }), filter };
}
