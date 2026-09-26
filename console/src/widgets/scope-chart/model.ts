// The chart data mapping, as pure functions. Every series is one row per HOUR of the selected
// window, oldest first and zero-filled: the daemon's economics rollup is hourly
// (FEATURES.md 2.5), so an hour with no turns is an IDLE hour and must draw as a gap rather than
// being closed up by filtering it out — a chart that silently skips empty hours makes an overnight
// pause look like continuous work.
//
// Nothing here reads a store, a clock or the network: the caller passes the window and `now`, which
// is what lets a test assert the mapping against a fixed clock.
import type { EconomicsBucket } from '@shared/api';
import { S } from './strings';

const HOUR_MS = 3_600_000;

export interface ChartWindow {
  id: string;
  /** Printed on the tab and in the frame. Comes from the string table, so the label wall sees it. */
  label: string;
  hours: number;
}

/** The three windows FEATURES.md 4.6 asks for. `1h` is ONE bucket — the rollup is hourly. */
export const WINDOWS: readonly ChartWindow[] = [
  { id: '1h', label: S.hour1, hours: 1 },
  { id: '24h', label: S.hour24, hours: 24 },
  { id: '7d', label: S.day7, hours: 168 },
];

/** One hour's row, whatever the chart plots. `at` is the hour's own start, for the axis. */
export interface HourRow {
  at: number;
  values: Record<string, number>;
}

/** The hours of the window, oldest first, each aligned to the hour boundary like the daemon's.
 *  Exported because a total and the bars beside it must count the SAME hours, or the number under a
 *  chart describes a different window than the chart. */
export function windowHours(hours: number, now: number): number[] {
  const end = Math.floor(now / HOUR_MS) * HOUR_MS;
  return Array.from({ length: hours }, (_, index) => end - (hours - 1 - index) * HOUR_MS);
}

function byHour(buckets: readonly EconomicsBucket[]): Map<number, EconomicsBucket> {
  return new Map(buckets.map((bucket) => [bucket.hour, bucket]));
}

/** tokens by bucket: the fresh remainder, the cache read half, the cache write half, and output. */
export function tokenRows(buckets: readonly EconomicsBucket[], hours: number, now: number): HourRow[] {
  const found = byHour(buckets);
  return windowHours(hours, now).map((at) => {
    const bucket = found.get(at);
    const total = bucket?.in_tokens ?? 0;
    const cached = bucket?.cached_tokens ?? 0;
    const write = bucket?.cache_write_tokens ?? 0;
    return {
      at,
      values: {
        // The subtraction that keeps a stack honest: `in_tokens` CONTAINS both cache halves, so a
        // fresh segment that did not subtract them would draw the same tokens twice and make the
        // stack taller than the total it claims to decompose.
        fresh: Math.max(0, total - cached - write),
        cached,
        write,
        out: bucket?.out_tokens ?? 0,
      },
    };
  });
}

/** request bytes against upstream bytes: what the wire carried out, and what it became. */
export function byteRows(buckets: readonly EconomicsBucket[], hours: number, now: number): HourRow[] {
  const found = byHour(buckets);
  return windowHours(hours, now).map((at) => ({
    at,
    values: {
      request: found.get(at)?.req_bytes ?? 0,
      upstream: found.get(at)?.upstream_req_bytes ?? 0,
    },
  }));
}

/** the tool partition: deferred tools against the eager ones that rode in full. */
export function toolRows(buckets: readonly EconomicsBucket[], hours: number, now: number): HourRow[] {
  const found = byHour(buckets);
  return windowHours(hours, now).map((at) => ({
    at,
    values: {
      eager: found.get(at)?.tools_eager ?? 0,
      deferred: found.get(at)?.tools_deferred ?? 0,
    },
  }));
}

/** turns the provider rate-limited, which is the cost of running at the ceiling. */
export function limitedRows(buckets: readonly EconomicsBucket[], hours: number, now: number): HourRow[] {
  const found = byHour(buckets);
  return windowHours(hours, now).map((at) => ({ at, values: { limited: found.get(at)?.rate_limited ?? 0 } }));
}

/**
 * The tallest SINGLE value, for series that are two measurements of the same thing rather than parts
 * of a whole.
 *
 * Request bytes and upstream bytes are exactly that: the same traffic read at two points on the
 * wire. STACKING THEM SAYS THEY ADD UP, and they do not — the stack would read as a volume neither
 * number ever was, and on a deferring head (where upstream < request) it would also bury the very
 * saving the second number exists to show.
 */
export function peakMax(rows: readonly HourRow[], keys: readonly string[]): number {
  return rows.reduce((peak, row) => Math.max(peak, ...keys.map((key) => row.values[key] ?? 0)), 0) || 1;
}

export function totalOf(rows: readonly HourRow[], key: string): number {
  return rows.reduce((sum, row) => sum + (row.values[key] ?? 0), 0);
}

/**
 * The tallest STACK in the series, floored at 1 so an all-zero window still has a scale to draw
 * against instead of dividing by zero. For series that are parts of a whole (fresh + read + write +
 * out) the scale is the total, never the largest part.
 */
export function peakOf(rows: readonly HourRow[], keys: readonly string[]): number {
  return rows.reduce(
    (peak, row) => Math.max(peak, keys.reduce((sum, key) => sum + (row.values[key] ?? 0), 0)),
    0,
  ) || 1;
}
