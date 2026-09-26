// Formatting + small helpers. Real numbers from the mgmt API only — these
// format, never invent.
import { create } from 'zustand';

export { OPEN_PARAM, itemHref, linkedId, useLinkedId, useOpen } from './link';

export interface Resource<T> {
  data: T | null;
  error: string | null;
  loading: boolean;
  lastUpdated: number | null;
}

export interface ResourceStore<T> {
  use: <U>(selector: (state: Resource<T>) => U) => U;
  get: () => Resource<T>;
  setData: (data: T) => void;
  setError: (message: string) => void;
  startLoading: () => void;
}

/** Domain-state cell for one mgmt endpoint: entity api segments write it,
 * views subscribe via selectors. Components never hold server state. */
export function createResource<T>(): ResourceStore<T> {
  const useStore = create<Resource<T>>(() => ({
    data: null,
    error: null,
    loading: false,
    lastUpdated: null,
  }));
  return {
    use: (selector) => useStore(selector),
    get: () => useStore.getState(),
    setData: (data) => useStore.setState({ data, error: null, loading: false, lastUpdated: Date.now() }),
    setError: (message) => useStore.setState({ error: message, loading: false }),
    startLoading: () => useStore.setState((s) => ({ ...s, loading: s.data === null })),
  };
}

export function cx(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ');
}

/** What a cell prints when nobody reported a value for it: the en dash every table uses for "no
 *  value". It was `n/r`, an abbreviation no reader could expand (console review, 2026-09-24).
 *  One constant, so the next change of mind is one line and not eight copies. The other absence
 *  words (none, unknown, unavailable, ineligible) are different facts and stay words. */
export const ABSENT = '–';

export function fmtInt(n: number): string {
  return new Intl.NumberFormat('en-US').format(n);
}

/** A byte count as a person reads it, in binary units: `512 B`, `1.5 KiB`, `54.1 MiB`. */
export function fmtBytes(n: number): string {
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
  let value = n;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit += 1; }
  return unit === 0 ? `${n} B` : `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`;
}

export function fmtTokens(n: number): string {
  if (n >= 1_000_000_000) return `${(n / 1_000_000_000).toFixed(2)}B`;
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`;
  if (n >= 10_000) return `${Math.round(n / 1000)}k`;
  if (n >= 1_000) return `${(n / 1000).toFixed(1)}k`;
  return String(n);
}

export function fmtDurationS(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s % 60}s`;
  return `${s}s`;
}

/** A 0..1 share as a person reads it: `25%`, one decimal under ten so 2.4% is not 2%, and a floor
 *  of `<0.1%` so a rare failure never reads as none. One copy: turns and compaction each kept their
 *  own (code review, 2026-09-24). */
export function fmtShare(share: number): string {
  const value = share * 100;
  if (value === 0) return '0%';
  if (value < 0.1) return '<0.1%';
  return `${value < 10 ? value.toFixed(1) : value.toFixed(0)}%`;
}

/** A value against the largest of its column, 0..1 for a meter, and 0 when the column is empty
 *  rather than the NaN a bare division gives. */
export function ratio(value: number, max: number): number {
  return max <= 0 ? 0 : value / max;
}

/** Month names as the console prints a date (`sep 21`), one table for every page that dates a row. */
export const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'] as const;

export function fmtMs(ms: number): string {
  if (ms >= 60_000) return fmtDurationS(ms / 1000);
  if (ms >= 1_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.round(ms)}ms`;
}

export function timeAgo(ts: number, now = Date.now()): string {
  const delta = Math.max(0, now - ts);
  if (delta < 5_000) return 'now';
  if (delta < 60_000) return `${Math.floor(delta / 1000)}s ago`;
  if (delta < 3_600_000) return `${Math.floor(delta / 60_000)}m ago`;
  // Hours up to two days, then days: `50h ago` made the reader do the division.
  if (delta < 172_800_000) return `${Math.floor(delta / 3_600_000)}h ago`;
  return `${Math.floor(delta / 86_400_000)}d ago`;
}

// ONE RULE FOR A TIMELINE'S BUCKETS (V4-300, V4-302). Two timelines stepped their buckets from now
// minus the window and titled each by the hour it began in, so at 14:37 a 15:10 row filed under
// "14:00". A bucket starts on a clock mark and is titled by its own start, so a title always names
// a clock time its bucket holds, whatever the bucket size.

/** The first local clock mark of `stepMs` at or after `at`: local midnight plus a whole number of
 *  steps, so an hour step lands on the hour and a 40-minute one on 00:00, 00:40, 01:20. */
export function clockMark(at: number, stepMs: number): number {
  const midnight = new Date(at);
  midnight.setHours(0, 0, 0, 0);
  return midnight.getTime() + Math.ceil((at - midnight.getTime()) / stepMs) * stepMs;
}

/** A timeline's spans from `from` to `to`: every one starts on a clock mark of `stepMs` but the
 *  first, which starts at `from` itself when that is not one, and the last ends at `to`. */
export function clockSpans(from: number, to: number, stepMs: number): { start: number; end: number }[] {
  const spans: { start: number; end: number }[] = [];
  for (let start = from; start < to;) {
    const end = Math.min(clockMark(start + 1, stepMs), to);
    spans.push({ start, end });
    start = end;
  }
  return spans;
}

/** The title of a timeline span: the local clock time it starts at, HH:MM. */
export function clockTitle(start: number): string {
  const at = new Date(start);
  return `${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}`;
}

/** Interval runner with immediate first tick; returns a stop function.
 *
 *  A HIDDEN TAB DOES NOT TICK. Nobody reads a page in a background tab, and every poller on it kept
 *  its full rate anyway (29 requests in 20 s, console walkthrough 2026-09-24), so while the document
 *  is hidden the interval is cleared, and the tab coming back ticks at once and restarts it: the
 *  reader never waits out an interval for data that went stale behind their back. The first tick
 *  stays unconditional, so a page opened in a background tab still loads. Without a document (the
 *  node test environment) it is the plain interval it always was. */
export function poll(fn: () => void | Promise<void>, intervalMs: number): () => void {
  const doc = typeof document === 'undefined' ? null : document;
  let stopped = false;
  let id: ReturnType<typeof setInterval> | null = null;
  const tick = () => {
    if (stopped) return;
    void fn();
  };
  const resume = () => {
    if (id === null) id = setInterval(tick, intervalMs);
  };
  const pause = () => {
    if (id !== null) clearInterval(id);
    id = null;
  };
  const onVisibility = () => {
    if (doc?.visibilityState === 'hidden') {
      pause();
    } else if (id === null) {
      tick();
      resume();
    }
  };
  tick();
  if (doc?.visibilityState !== 'hidden') resume();
  doc?.addEventListener('visibilitychange', onVisibility);
  return () => {
    stopped = true;
    pause();
    doc?.removeEventListener('visibilitychange', onVisibility);
  };
}

/**
 * How a write ended, and the only answer a write gives: the daemon's answer when it applied, the
 * v0.4.0 item that will serve a route not built yet, or the reason it failed in the daemon's words.
 * Writes that answered `null` or `false` for all three read as success to every caller, and the
 * panels printed "Saved" over a refusal (Marlin's HOLD, 2026-09-25). The wall
 * `webui-write-never-swallows` keeps it the only answer; `writeFailure` (shared/api) builds the
 * failures.
 */
export type WriteResult<T> =
  | { status: 'applied'; answer: T }
  | { status: 'pending'; item: string }
  | { status: 'failed'; reason: string };

/** The one line a panel prints for a write's result: `done` when it applied, `pending` for a route
 *  not built yet, and the reason when it failed. Only an applied write reads as success. */
export function writeNote(result: WriteResult<unknown>, words: { done: string; pending: string }): { text: string; failed: boolean } {
  if (result.status === 'applied') return { text: words.done, failed: false };
  return { text: result.status === 'pending' ? words.pending : result.reason, failed: true };
}
