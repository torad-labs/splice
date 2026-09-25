// The arithmetic behind the turns page: the WATERFALL, the GROUPING and the IN-FLIGHT SET. Pure
// functions over the daemon's rows, kept out of the view so each is directly testable.
//
// WHY THE WATERFALL IS SEGMENTS AND NOT ONE DURATION. FEATURES.md 4.3 asks for queue wait, upstream
// wait and streaming to be "visibly separate", because the console cannot say WHY an upstream was
// slow (2.12): it can only say where the time went. Every perf row carries the marks that answer it,
// each in ms since the request arrived (PerfKeys: "marks are *_ms-since-arrival").
//
// THE SEGMENTS FOLLOW THE CLOCK, NOT THE KEY ORDER. The line prints its marks in PerfKeys.markOrder,
// and the daemon does not stamp them in that order: admission marks `gate` as the turn opens,
// before `parse` and `build` (AdmissionTelemetry.kt), and the client's first frame goes out at
// upstream handoff, before the provider's first byte (ClientChannel.kt, the dead-air fix). Read in
// key order, a live row's queue wait ran backwards and was dropped (gate=1 after build=18, the
// demo daemon's log on 2026-09-25). So each present mark ends a segment that starts at the mark
// before it in time, and the first one starts at arrival.
//
// ABSENT IS NOT ZERO. A failed turn has no `stream_end`, a turn that never reached the upstream has
// no `headers`. An absent mark draws no segment of its own: its time belongs to the next mark that
// was stamped, and the bar stops at the last one, so a broken turn reads as broken instead of as an
// instant one.
import type { HeadStatus } from '@shared/api';
import { MARK_KEYS } from './types';
import type { InflightTurn, MarkKey, TurnRow } from './types';

/** Where a segment sits in the turn: the proxy's own work, the admission queue, the upstream wait,
 *  the stream, or the close. */
export type StageGroup = 'ingest' | 'queue' | 'upstream' | 'stream' | 'finish';

export interface Stage {
  /** The mark pair this segment measures, e.g. "gate" is build..gate. */
  key: string;
  /** The daemon's own word for the segment (FEATURES.md 4.3 names the same three: "queue wait
   *  (gate)", "upstream wait (headers, first byte)", "streaming (first delta to stream end)"). */
  label: string;
  group: StageGroup;
  /** ms since arrival at the segment's start. */
  start: number;
  /** ms since arrival at the segment's end. */
  end: number;
  /** end - start, always >= 0. */
  ms: number;
}

/** What the segment a mark ENDS is called, and where in the turn it sits. `total` is the daemon's
 *  closing tally and not a phase, so it ends no segment. */
const STAGE_OF: Record<Exclude<MarkKey, 'total'>, { label: string; group: StageGroup }> = {
  recv: { label: 'receive', group: 'ingest' },
  parse: { label: 'parse', group: 'ingest' },
  build: { label: 'build', group: 'ingest' },
  gate: { label: 'gate', group: 'queue' },
  headers: { label: 'headers', group: 'upstream' },
  first_byte: { label: 'first byte', group: 'upstream' },
  first_frame: { label: 'first frame', group: 'stream' },
  first_delta: { label: 'first delta', group: 'stream' },
  stream_end: { label: 'streaming', group: 'stream' },
  finish: { label: 'finish', group: 'finish' },
};

/** The marks a row actually carries, in pipeline order. A row with no marks returns empty, and the
 *  page says the row carries no telemetry rather than drawing a flat bar. */
export function marksOf(row: TurnRow): MarkKey[] {
  return MARK_KEYS.filter((key) => typeof row[key] === 'number');
}

/** The turn's segments in the order they happened: each stamped mark ends one, starting at the
 *  mark stamped before it, the first at arrival. A mark stamped at the same ms as another keeps the
 *  key order between them (the sort is stable), and a negative mark is a defect in the row, dropped.
 *  It reads the marks and nothing else, so a perf line parsed off the log draws the same bar. */
export function waterfall(row: Pick<TurnRow, MarkKey>): Stage[] {
  const marks = MARK_KEYS
    .filter((key): key is Exclude<MarkKey, 'total'> => key !== 'total')
    .flatMap((key) => {
      const at = row[key];
      return typeof at === 'number' && at >= 0 ? [{ key, at }] : [];
    })
    .sort((left, right) => left.at - right.at);
  let from = 0;
  return marks.map(({ key, at }) => {
    const stage: Stage = { key, ...STAGE_OF[key], start: from, end: at, ms: at - from };
    from = at;
    return stage;
  });
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
        compact: live.compact,
        phase: live.phase,
        ageMs: live.age_ms,
        idleMs: live.idle_ms,
        streamIdleMs: gate.stream_idle_ms,
      });
    }
  }
  return inflight;
}
