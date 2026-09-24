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

/**
 * The window a timeline view asks for, ending NOW, carried in the view's filter so two saved views
 * hold two windows without the page growing a setting of its own. A missing or unparseable word
 * falls back to the default: an empty board because a filter string was typo'd would read to the
 * operator as "nothing happened".
 */
export function windowOf(view: View, now: number, defaultHours = 24, defaultBucketHours = 1): Window {
  const hours = parseHours(view.filter.window ?? '') ?? defaultHours;
  const bucketHours = parseHours(view.filter.bucket ?? '') ?? defaultBucketHours;
  return { from: now - hours * 3_600_000, to: now, bucketMs: bucketHours * 3_600_000, hours };
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

/** One line of the virtualized list: either a row, or a band that names what follows it. */
export type Item =
  | { kind: 'row'; key: string; index: number; row: TurnRow }
  | { kind: 'band'; key: string; label: string; count: number };

/**
 * The selection as one flat list, so EVERY view virtualizes through the same loop: a grouped or
 * time-bucketed view with thousands of rows would otherwise be the one page that renders every
 * strip it holds.
 *
 * A bucket or group that holds no rows contributes NO band: an empty band would read as "this
 * hour has turns, they are just not listed". The idle-bucket count is printed in the page header
 * instead, where a number can say it without pretending to be a rack.
 */
export function itemsOf(selection: Selection): Item[] {
  if (selection.kind === 'table') {
    return selection.rows.map((row, index) => ({ kind: 'row', key: rowKey(row, index), index, row }));
  }
  if (selection.kind === 'timeline') {
    const items: Item[] = [];
    selection.timeline.buckets.forEach((bucket) => {
      if (bucket.rows.length === 0) return;
      items.push({ kind: 'band', key: `band:${bucket.start}`, label: clockOf(bucket.start), count: bucket.rows.length });
      bucket.rows.forEach((row, index) => items.push({ kind: 'row', key: rowKey(row, index), index, row }));
    });
    selection.timeline.undated.forEach((row, index) =>
      items.push({ kind: 'row', key: rowKey(row, index), index, row }),
    );
    return items;
  }
  const items: Item[] = [];
  selection.groups.forEach((group) => {
    items.push({ kind: 'band', key: `band:${group.key}`, label: `${group.key}`, count: group.count });
    group.rows.forEach((row, index) => items.push({ kind: 'row', key: rowKey(row, index), index, row }));
  });
  return items;
}

/** A per-head file can hold two turns in the same millisecond; the index keeps the key unique, and
 *  a wrong key here is a row React reuses for another row's data. */
export function rowKey(row: TurnRow, index: number): string {
  return `${row.head}:${row.ts}:${index}`;
}

/** HH:00 of a bucket start: the timeline's own axis label. */
export function clockOf(start: number): string {
  const at = new Date(start);
  return `${String(at.getHours()).padStart(2, '0')}:00`;
}
