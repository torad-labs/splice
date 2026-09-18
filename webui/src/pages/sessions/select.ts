// What a saved view asks the page to draw, as pure functions. A view owns its
// layout, group and filter (CONTRACTS.md section 3), so this module is the one
// place that reads those three fields and says what the board should be: the
// page renders the answer, the test asserts it without a DOM.
import type { View } from '@features/views';
import { groupSessions, timeline } from '@entities/session';
import type { GroupBy, SessionGroup, SessionRow, Timeline } from '@entities/session';

/** The grouping a view asks for, or null when it does not group at all. */
export function groupByOf(view: View): GroupBy | null {
  switch (view.group) {
    case 'head':
    case 'repo':
    case 'team':
      return view.group;
    default:
      return null;
  }
}

/** A timeline is a layout, not a group: the same rows, placed on a clock. */
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

export interface TimelineWindow {
  from: number;
  to: number;
  bucketMs: number;
  /** How many hours the window covers, for the page to print beside it. */
  hours: number;
}

/**
 * The window a timeline view asks for, ending NOW. The filter carries it
 * (`window: '24h'`, `bucket: '1h'`), so two saved views can hold two windows
 * without the page growing a setting of its own.
 *
 * A missing or unparseable word falls back to the default rather than to a
 * zero-length window: an empty board because a filter string was typo'd would
 * read to the operator as "nothing happened today".
 */
export function windowOf(view: View, now: number, defaultHours = 24, defaultBucketHours = 1): TimelineWindow {
  const hours = parseHours(view.filter.window ?? '') ?? defaultHours;
  const bucketHours = parseHours(view.filter.bucket ?? '') ?? defaultBucketHours;
  const bucketMs = bucketHours * 3_600_000;
  return { from: now - hours * 3_600_000, to: now, bucketMs, hours };
}

/** One group of the board: the rack view's unit. */
export interface BoardGroup {
  key: string;
  count: number;
  rows: SessionRow[];
}

export type Selection =
  | { kind: 'groups'; groups: BoardGroup[] }
  | { kind: 'timeline'; timeline: Timeline; window: TimelineWindow };

/**
 * The board a view asks for. A rack view with no group field falls back to the
 * head grouping rather than to an ungrouped list: the page's default view is
 * `by head`, and a view that lost its group should still draw a rack.
 */
export function selectionOf(rows: readonly SessionRow[], view: View, now: number): Selection {
  if (isTimeline(view)) {
    const window = windowOf(view, now);
    return { kind: 'timeline', timeline: timeline(rows, window), window };
  }
  const groups: SessionGroup[] = groupSessions(rows, groupByOf(view) ?? 'head');
  return { kind: 'groups', groups: groups.map((g) => ({ key: g.key, count: g.count, rows: g.sessions })) };
}

/** The address a group header opens: the page that group belongs to. */
export function groupHref(by: GroupBy | null): string {
  switch (by) {
    case 'repo':
      return '#/projects';
    case 'team':
      return '#/teams';
    default:
      return '#/fleet';
  }
}
