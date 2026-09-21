// The geometry behind the waterfall, as pure functions: stages in, bars out.
//
// The chart is drawn from data at runtime (ScopeInset's contract), so the numbers
// that place every bar are here and not inside a render function, where they
// could only be checked by looking at a picture.
import type { Stage, StageGroup } from '@entities/perf';

/** The reading order of the phases: the proxy's own work, then the queue, the upstream wait, the
 *  stream and the close. FEATURES.md 4.3 asks for the three waits to be visibly separate, and one
 *  ROW per group is what makes them separate at a glance rather than by color. */
export const GROUP_ORDER: readonly StageGroup[] = ['ingest', 'queue', 'upstream', 'stream', 'finish'];

/** One segment, as a fraction of the turn: x and w are 0..1, so the same numbers draw at any
 *  width and nothing in the chart depends on a pixel size. */
export interface Bar {
  key: string;
  label: string;
  x: number;
  w: number;
  ms: number;
}

/** One phase group's row: its segments, and the span they cover. */
export interface BarRow {
  group: StageGroup;
  start: number;
  end: number;
  /** end - start: the wall time this group covers, which a segment sum would understate when the
   *  mark pairs in the group are not contiguous. */
  ms: number;
  bars: Bar[];
}

/** The turn's own length on the axis: the furthest mark any segment reaches. Zero when the row
 *  carries no usable marks, which the caller renders as "no telemetry" rather than as a flat bar
 *  of zero length. */
export function totalOf(stages: readonly Stage[]): number {
  let total = 0;
  for (const stage of stages) total = Math.max(total, stage.end);
  return total;
}

/**
 * The stages filed by phase group, in reading order, with each segment's position as a fraction of
 * [total]. A group with no stages contributes NO row: an empty row would read as "this phase took
 * no time", when the truth is that the row does not carry its marks.
 */
export function barRows(stages: readonly Stage[], total: number): BarRow[] {
  if (total <= 0) return [];
  const rows: BarRow[] = [];
  for (const group of GROUP_ORDER) {
    const mine = stages.filter((stage) => stage.group === group);
    if (mine.length === 0) continue;
    const start = Math.min(...mine.map((stage) => stage.start));
    const end = Math.max(...mine.map((stage) => stage.end));
    rows.push({
      group,
      start,
      end,
      ms: end - start,
      bars: mine.map((stage) => ({
        key: stage.key,
        label: stage.label,
        x: stage.start / total,
        w: stage.ms / total,
        ms: stage.ms,
      })),
    });
  }
  return rows;
}
