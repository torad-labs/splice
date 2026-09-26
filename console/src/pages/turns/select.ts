// What a saved view asks the turns page to draw, as pure functions: the flat table, the grouped
// rack, or the timeline. A view owns its layout, group and filter (CONTRACTS.md section 3), so
// this is the one place those three fields are read.
import type { View } from '@features/views';
import { groupTurns, timelineOf } from '@entities/perf';
import type { GroupBy, TurnRow, TurnTimeline } from '@entities/perf';

/** The groupings the turns views use. Head is deliberately absent: the route already filters by
 *  head, so a head grouping would draw one bay with every row in it. */
export function groupByOf(view: View): GroupBy | null {
  if (view.group === 'model' || view.group === 'outcome' || view.group === 'head' || view.group === 'session') {
    return view.group;
  }
  return null;
}

export function isTimeline(view: View): boolean {
  return view.layout === 'timeline';
}

/** Hours from a view's filter word ("24h"), or null when it is not one. */
export function parseHours(label: string): number | null {
  const match = /^(\d+)h$/.exec(label.trim());
  if (match === null) return null;
  const hours = Number(match[1]);
  return Number.isFinite(hours) && hours > 0 ? hours : null;
}

export interface Window {
  from: number;
  to: number;
  bucketMs: number;
  hours: number;
}

/** The first local clock hour at or after `at`. */
function hourFrom(at: number): number {
  const hour = new Date(at);
  hour.setMinutes(0, 0, 0);
  return hour.getTime() < at ? hour.getTime() + 3_600_000 : hour.getTime();
}

/**
 * The window a timeline view asks for, ending NOW, carried in the view's filter so two saved views
 * hold two windows without the page growing a setting of its own. A missing or unparseable word
 * falls back to the default: an empty board because a filter string was typo'd would read to the
 * operator as "nothing happened".
 *
 * It starts on the first clock hour inside those hours, so every bucket starts on the hour its title
 * names and the last one ends now (V4-300: the buckets began at now minus the window, and a
 * 14:37-15:37 bucket was titled 14:00, with a 15:10 turn under it).
 */
export function windowOf(view: View, now: number, defaultHours = 24, defaultBucketHours = 1): Window {
  const hours = parseHours(view.filter.window ?? '') ?? defaultHours;
  const bucketHours = parseHours(view.filter.bucket ?? '') ?? defaultBucketHours;
  return { from: hourFrom(now - hours * 3_600_000), to: now, bucketMs: bucketHours * 3_600_000, hours };
}

/**
 * Where a view's read of the landed turns starts: a timeline reads its own window, so it draws only
 * hours it asked for; every other view reads the fleet's newest turns (null). V4-300: every view
 * read the daemon's default 24 hours, and a 48h view drew its first day as idle.
 */
export function sinceOf(view: View, now: number): number | null {
  return isTimeline(view) ? windowOf(view, now).from : null;
}

export interface BoardGroup {
  key: string;
  count: number;
  rows: TurnRow[];
}

export type Selection =
  | { kind: 'table'; rows: TurnRow[] }
  | { kind: 'groups'; groups: BoardGroup[] }
  | { kind: 'timeline'; timeline: TurnTimeline; window: Window };

export function selectionOf(rows: readonly TurnRow[], view: View, now: number): Selection {
  if (isTimeline(view)) {
    const window = windowOf(view, now);
    return { kind: 'timeline', timeline: timelineOf(rows, window), window };
  }
  // Newest first: the rows arrive oldest first (turns-wire keeps the last N), and a feed whose
  // latest turn sits at the bottom of 200 rows made the operator scroll for the one they came for.
  // The timeline keeps its axis order above; within a group the rows follow this order too.
  // An undated row (a torn legacy line; the timeline lists these apart) sorts last.
  const at = (row: TurnRow): number => (Number.isFinite(row.ts) ? row.ts : Number.NEGATIVE_INFINITY);
  const newest = [...rows].sort((left, right) => at(right) - at(left));
  const by = groupByOf(view);
  if (by === null) return { kind: 'table', rows: newest };
  return { kind: 'groups', groups: groupTurns(newest, by).map((g) => ({ key: g.key, count: g.count, rows: g.turns })) };
}

/**
 * A row's key: its head and ts, plus an ordinal only among rows that share both (a per-head file can
 * hold two turns in the same millisecond, and a wrong key is a row React reuses for another row's
 * data). NOT THE LIST INDEX: the list is newest first, so every new turn shifted every other row's
 * index, and an opened turn lost its key, and the detail column closed, on the next poll. One keyer
 * per list, so the ordinals count within it.
 */
export function rowKeyer(): (row: TurnRow) => string {
  const seen = new Map<string, number>();
  return (row) => {
    const base = `${row.head}:${row.ts}`;
    const n = seen.get(base) ?? 0;
    seen.set(base, n + 1);
    return n === 0 ? base : `${base}:${n}`;
  };
}

/** HH:00 of a bucket start: the timeline's own axis label. */
export function clockOf(start: number): string {
  const at = new Date(start);
  return `${String(at.getHours()).padStart(2, '0')}:00`;
}
