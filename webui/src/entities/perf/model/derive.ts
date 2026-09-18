// The arithmetic behind the turns page: the WATERFALL, the GROUPING and the IN-FLIGHT SET. Pure
// functions over the daemon's rows, kept out of the view so each is directly testable.
//
// WHY THE WATERFALL IS SEGMENTS AND NOT ONE DURATION. FEATURES.md 4.3 asks for queue wait, upstream
// wait and streaming to be "visibly separate", because the console cannot say WHY an upstream was
// slow (2.12): it can only say where the time went. The four marks that answer that are already in
// every perf row, cumulative ms since the request arrived - `gate` ends the queue wait, `headers`
// and `first_byte` span the upstream wait, and `first_delta` to `stream_end` is the stream itself.
//
// ABSENT IS NOT ZERO. A failed turn has no `stream_end`, a turn that never reached the upstream has
// no `headers`. A segment whose two marks are not both present is OMITTED, and the bar is drawn with
// a gap there, so a broken turn reads as broken instead of as an instant one. A mark pair that runs
// backwards is dropped for the same reason: marks are cumulative, so a decrease is a defect in the
// row, never a negative duration to draw.
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

/** Consecutive mark pairs, in pipeline order. The last segment ends at `finish`, which is where
 *  FEATURES.md 4.3 ends the bar; `total` is the daemon's own closing mark and is not a phase. */
const STAGES: readonly { key: string; label: string; group: StageGroup; from: MarkKey; to: MarkKey }[] = [
  { key: 'parse', label: 'parse', group: 'ingest', from: 'recv', to: 'parse' },
  { key: 'build', label: 'build', group: 'ingest', from: 'parse', to: 'build' },
  { key: 'gate', label: 'gate', group: 'queue', from: 'build', to: 'gate' },
  { key: 'headers', label: 'headers', group: 'upstream', from: 'gate', to: 'headers' },
  { key: 'first_byte', label: 'first byte', group: 'upstream', from: 'headers', to: 'first_byte' },
  { key: 'first_frame', label: 'first frame', group: 'stream', from: 'first_byte', to: 'first_frame' },
  { key: 'first_delta', label: 'first delta', group: 'stream', from: 'first_frame', to: 'first_delta' },
  { key: 'stream_end', label: 'streaming', group: 'stream', from: 'first_delta', to: 'stream_end' },
  { key: 'finish', label: 'finish', group: 'finish', from: 'stream_end', to: 'finish' },
];

/** The marks a row actually carries, in pipeline order. A row with no marks returns empty, and the
 *  page says the row carries no telemetry rather than drawing a flat bar. */
export function marksOf(row: TurnRow): MarkKey[] {
  return MARK_KEYS.filter((key) => typeof row[key] === 'number');
}

/** The turn's segments, in pipeline order, omitting every one whose marks are not both present. */
export function waterfall(row: TurnRow): Stage[] {
  const stages: Stage[] = [];
  for (const stage of STAGES) {
    const start = row[stage.from];
    const end = row[stage.to];
    if (typeof start !== 'number' || typeof end !== 'number' || end < start) continue;
    stages.push({ key: stage.key, label: stage.label, group: stage.group, start, end, ms: end - start });
  }
  return stages;
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
      return row.model;
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
