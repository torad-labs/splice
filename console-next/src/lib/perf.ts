// Grouping and in-flight arithmetic over the daemon's rows. Request timing uses measured spans
// in turns-page.ts, never causal labels inferred from gaps between legacy marks.
import type { HeadStatus } from '../types/core';
import { MARK_KEYS } from '../types/perf';
import type { InflightTurn, MarkKey, TurnRow } from '../types/perf';

/** The marks a row actually carries, in pipeline order. A row with no marks returns empty, and the
 *  page says the row carries no telemetry rather than drawing a flat bar. */
export function marksOf(row: TurnRow): MarkKey[] {
  return MARK_KEYS.filter((key) => typeof row[key] === 'number');
}

/** What a turn with no client session tag is grouped under, so unattributed turns are counted and
 *  shown rather than dropped from a total (FEATURES.md 4.13). */
export const UNATTRIBUTED = 'unattributed';

export type GroupBy = 'head' | 'model' | 'outcome' | 'session';

export interface TurnGroup {
  key: string;
  count: number;
  turns: TurnRow[];
}

function groupKeyOf(row: TurnRow, by: GroupBy): string {
  switch (by) {
    case 'head':
      return row.head;
    case 'model':
      return row.model ?? UNATTRIBUTED;
    case 'outcome':
      return row.outcome;
    case 'session':
      return row.session !== undefined && row.session !== '' ? row.session : UNATTRIBUTED;
  }
}

/** The turns filed by [by], biggest group first and ties broken by key so the order is stable
 *  across renders. */
export function groupTurns(turns: readonly TurnRow[], by: GroupBy): TurnGroup[] {
  const groups = new Map<string, TurnGroup>();
  for (const row of turns) {
    const key = groupKeyOf(row, by);
    const existing = groups.get(key);
    if (existing === undefined) groups.set(key, { key, count: 1, turns: [row] });
    else {
      existing.count++;
      existing.turns.push(row);
    }
  }
  return [...groups.values()].sort((a, b) => (b.count - a.count) || a.key.localeCompare(b.key));
}

/**
 * The in-flight set, read off the gate snapshots of GET /api/heads: one entry per live turn, in the
 * order the daemon lists heads and their turns. A head whose gate snapshot is absent (not running,
 * or no gate published yet) contributes nothing rather than an empty turn.
 */
export interface TurnBucket {
  start: number;
  end: number;
  rows: TurnRow[];
}

export interface TurnTimeline {
  /** Every bucket in the window, oldest first, EMPTY ONES INCLUDED: an idle hour has to be a gap,
   *  and a bucket list with holes would restate the day. */
  buckets: TurnBucket[];
  /** Rows the window cannot place because the row carries no `ts`. Rows that fall OUTSIDE the
   *  window are not listed here: the window is what the caller asked for. */
  undated: TurnRow[];
}

export interface TurnWindow {
  /** Window start, epoch ms. Included. */
  from: number;
  /** Window end, epoch ms. EXCLUDED, so adjacent windows never count a turn twice. */
  to: number;
  bucketMs: number;
}

export function timelineOf(rows: readonly TurnRow[], window: TurnWindow): TurnTimeline {
  const { from, to, bucketMs } = window;
  if (bucketMs <= 0) throw new Error(`timelineOf needs a positive bucketMs, got ${bucketMs}`);
  const buckets: TurnBucket[] = [];
  for (let start = from; start < to; start += bucketMs) {
    buckets.push({ start, end: Math.min(start + bucketMs, to), rows: [] });
  }
  const undated: TurnRow[] = [];
  for (const row of rows) {
    if (typeof row.ts !== 'number') {
      undated.push(row);
      continue;
    }
    if (row.ts < from || row.ts >= to) continue;
    const bucket = buckets[Math.floor((row.ts - from) / bucketMs)];
    if (bucket !== undefined) bucket.rows.push(row);
  }
  return { buckets, undated };
}

export function inflightFrom(heads: readonly HeadStatus[]): InflightTurn[] {
  const inflight: InflightTurn[] = [];
  for (const head of heads) {
    const gate = head.gate;
    if (gate === null) continue;
    for (const live of gate.live) {
      inflight.push({
        head: head.key,
        label: live.label,
        ...(live.turn_id === undefined ? {} : { turnId: live.turn_id }),
        compact: live.compact,
        phase: live.phase,
        ageMs: live.age_ms,
        idleMs: live.idle_ms,
      });
    }
  }
  return inflight;
}
