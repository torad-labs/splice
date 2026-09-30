// Formatting only: real numbers from the daemon are formatted here, never invented.

/** What a cell prints when nobody reported a value for it: the en dash every table uses for "no
 *  value". It was `n/r`, an abbreviation no reader could expand (console review, 2026-09-24).
 *  One constant, so the next change of mind is one line and not eight copies. The other absence
 *  words (none, unknown, unavailable, ineligible) are different facts and stay words. */
export const ABSENT = '–';

export function fmtInt(n: number): string {
  return new Intl.NumberFormat('en-US').format(n);
}

/** The noun that agrees with a count: [one] for exactly one, [many] for every other count, zero
 *  included (`0 messages`). A count printed beside a plural-only word read `1 messages`; the sentence
 *  keeps its own number formatting and asks only for the word. */
export function noun(n: number, one: string, many: string): string {
  return n === 1 ? one : many;
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

/** Dollars at the precision a figure that small needs: cents from a dollar up, a tenth of a cent
 *  below it. One copy: the usage page's totals and one request's cost print the same way. */
export function fmtUsd(usd: number): string {
  return `$${usd >= 1 ? usd.toFixed(2) : usd.toFixed(3)}`;
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

// ONE RULE FOR A TIMELINE'S BUCKETS . Two timelines stepped their buckets from now
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
