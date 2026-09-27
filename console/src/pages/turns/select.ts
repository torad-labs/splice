// What a saved view asks the turns page to draw, as pure functions: the flat table, the grouped
// rack, or the timeline. A view owns its layout, group and filter (CONTRACTS.md section 3), so
// this is the one place those three fields are read.
import type { View } from '@features/views';
import { clockMark, clockTitle } from '@shared/lib';
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
 *
 * It starts on the first clock mark of its bucket size inside those hours (clockMark, the rule the
 * Sessions timeline buckets by too), so every bucket starts on the clock time its title names and the
 * last one ends now (V4-300: the buckets began at now minus the window, and a 14:37-15:37 bucket was
 * titled 14:00, with a 15:10 turn under it).
 */
export function windowOf(view: View, now: number, defaultHours = 24, defaultBucketHours = 1): Window {
  const hours = parseHours(view.filter.window ?? '') ?? defaultHours;
  const bucketHours = parseHours(view.filter.bucket ?? '') ?? defaultBucketHours;
  const bucketMs = bucketHours * 3_600_000;
  return { from: clockMark(now - hours * 3_600_000, bucketMs), to: now, bucketMs, hours };
}

/**
 * Where a view's read of the landed turns starts: a timeline reads its own window, so it draws only
 * hours it asked for; every other view reads the fleet's newest turns (null). V4-300: every view
 * read the daemon's default 24 hours, and a 48h view drew its first day as idle.
 */
export function sinceOf(view: View, now: number): number | null {
  return isTimeline(view) ? windowOf(view, now).from : null;
}

/**
 * What the landed turns are narrowed to (acceptance Q47: by command, model, session, status and time),
 * each field null for every value. The page holds it while it is open; a saved view keeps the layout
 * and grouping it always kept. It narrows everything drawn from the landed turns, so the stage and
 * token sections, which say they read "the landed turns below", still read exactly the table.
 */
export interface TurnFilter {
  head: string | null;
  model: string | null;
  session: string | null;
  /** `ok`, or `failed` for every other outcome tag. */
  status: 'ok' | 'failed' | null;
  /** Only turns that landed within this many ms of now. */
  within: number | null;
}

export const NO_FILTER: TurnFilter = { head: null, model: null, session: null, status: null, within: null };

export function isFiltered(filter: TurnFilter): boolean {
  return Object.values(filter).some((value) => value !== null);
}

export function filterTurns(rows: readonly TurnRow[], filter: TurnFilter, now: number): TurnRow[] {
  return rows.filter((row) => (filter.head === null || row.head === filter.head)
    && (filter.model === null || row.model === filter.model)
    && (filter.session === null || row.session === filter.session)
    && (filter.status === null || (row.outcome === 'ok') === (filter.status === 'ok'))
    && (filter.within === null || (Number.isFinite(row.ts) && row.ts >= now - filter.within)));
}

export interface FilterChoices {
  heads: string[];
  models: string[];
  sessions: string[];
}

/** The values each filter chooses among: every one a loaded turn carries, sorted, and a chosen one
 *  kept even once no loaded turn carries it, so a filter still applied can always be cleared. */
export function filterChoices(rows: readonly TurnRow[], filter: TurnFilter): FilterChoices {
  const values = (pick: (row: TurnRow) => string | null | undefined, chosen: string | null): string[] => {
    const found = new Set<string>();
    for (const row of rows) {
      const value = pick(row);
      if (value !== null && value !== undefined) found.add(value);
    }
    if (chosen !== null) found.add(chosen);
    return [...found].sort();
  };
  return {
    heads: values((row) => row.head, filter.head),
    models: values((row) => row.model, filter.model),
    sessions: values((row) => row.session, filter.session),
  };
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
 * A traced row's key is its head and the trace turn id the daemon minted. Legacy rows without a
 * trace use head, timestamp and an ordinal among rows sharing both. Keys are assigned over the
 * UNFILTERED list: if the first timestamp twin is filtered away, the second must not inherit its
 * key and show a different request in the open panel (V4-345).
 */
export function rowKeyer(): (row: TurnRow) => string {
  const seen = new Map<string, number>();
  return (row) => {
    if (row.turn !== undefined) return `${row.head}:turn:${row.turn}`;
    const base = `${row.head}:${row.ts}`;
    const n = seen.get(base) ?? 0;
    seen.set(base, n + 1);
    return n === 0 ? base : `${base}:${n}`;
  };
}

/** A bucket's title: the clock time it starts at (@shared/lib's clockTitle, kept under this page's name). */
export const clockOf = clockTitle;
