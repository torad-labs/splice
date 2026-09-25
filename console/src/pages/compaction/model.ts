// What the compaction page computes from GET /api/compact, as shapes rather than sentences: each
// outcome's state, the split of outcomes a bar draws, and the series a sparkline plots. Moved here
// from the retired compact-feed widget, whose only reader was this page.
import type { CompactPayload, CompactRow } from '@shared/api';
import { ABSENT, fmtShare } from '@shared/lib';
import type { BarPart, Mark, Tone } from '@shared/ui';
import { S } from './strings';

/** What an outcome MEANT: a summary was produced, something needs a look, it failed. The names are
 *  the compaction layer's own (`gateway/compact`); an outcome this console does not recognise is
 *  `warn`, never `ok`, because an unknown outcome is not a success. */
export type CompactState = 'ok' | 'warn' | 'fail';

/** The two picks that are a summary (PickedText.kt): the model's own text, or its reasoning promoted.
 *  Named, never matched by prefix: `model_text_weak` also starts with `model`, and it is the text
 *  that failed the weak-summary check with no reasoning to promote (Marlin, 2026-09-25). */
const SUMMARY = new Set(['model_text', 'model_thinking']);
/** Nothing came back: an empty pick (no text, no reasoning), an empty reply, a broken stream. */
const FAILED = new Set(['empty', 'empty_model', 'stream_error', 'upstream_error']);

export function stateOf(outcome: string): CompactState {
  if (SUMMARY.has(outcome)) return 'ok';
  if (FAILED.has(outcome)) return 'fail';
  return 'warn';
}

/** The badge tone and the chart mark of a state, from one mapping so the two cannot disagree. */
export const TONE: Record<CompactState, Tone> = { ok: 'ok', warn: 'warn', fail: 'danger' };
export const MARK: Record<CompactState, Mark> = { ok: 'ok', warn: 'warn', fail: 'danger' };

/** An outcome as a reader says it: the word for a name the daemon is known to write, and the
 *  daemon's own spelling, underscores read as spaces, for one this console has not met. The set
 *  stays open: `by_outcome` is `Record<string, number>` (shared/api), so the daemon can add names. */
export function outcomeText(outcome: string): string {
  return (S.outcomeName as Readonly<Record<string, string>>)[outcome] ?? outcome.replaceAll('_', ' ');
}

/** One outcome's part of all compactions, to one decimal under 10% so a rare failure still reads. */
export function shareText(count: number, total: number): string {
  return total <= 0 ? ABSENT : fmtShare(count / total);
}

/** The counts the page leads with. A daemon with #226 sends the last seven days beside the counted
 *  rows, and those lead: the counted rows reach back to each head's oldest kept row, so an old
 *  failure rate would read as today's. An older daemon sends only the counted rows. */
export function outcomeCounts(stats: CompactPayload['stats']): { counts: Record<string, number>; total: number; week: boolean } {
  const week = stats.by_outcome_7d;
  if (week === undefined) return { counts: stats.by_outcome, total: stats.total, week: false };
  return { counts: week, total: Object.values(week).reduce((sum, n) => sum + n, 0), week: true };
}

/** How many of some counts failed. */
export function failedOf(counts: Readonly<Record<string, number>>): number {
  return Object.entries(counts).filter(([outcome]) => stateOf(outcome) === 'fail').reduce((sum, [, n]) => sum + n, 0);
}

/** The counts as bar parts, largest first: one part per outcome, coloured by its state. */
export function outcomeParts(counts: Readonly<Record<string, number>>): BarPart[] {
  return Object.entries(counts)
    .sort(([, a], [, b]) => b - a)
    .map(([outcome, value]) => ({ key: outcome, label: outcomeText(outcome), value, mark: MARK[stateOf(outcome)] }));
}

/** The counts as table rows, largest first. */
export function outcomeRows(counts: Readonly<Record<string, number>>): { outcome: string; count: number }[] {
  return Object.entries(counts).sort(([, a], [, b]) => b - a).map(([outcome, count]) => ({ outcome, count }));
}

/** The tail oldest first, so a sparkline reads left to right in time. A row with no value for the
 *  field is a gap (null), never a zero: a stream that failed wrote no summary, it did not write 0. */
export function seriesOf(tail: readonly CompactRow[], field: 'ms' | 'chars'): (number | null)[] {
  return [...tail].sort((a, b) => a.ts - b.ts).map((row) => row[field] ?? null);
}

/** The middle value of the tail's field, or null when no row reported it. */
export function medianOf(tail: readonly CompactRow[], field: 'ms' | 'chars'): number | null {
  const values = tail.map((row) => row[field]).filter((value): value is number => value !== undefined).sort((a, b) => a - b);
  if (values.length === 0) return null;
  const mid = Math.floor(values.length / 2);
  return values.length % 2 === 1 ? values[mid] ?? null : ((values[mid - 1] ?? 0) + (values[mid] ?? 0)) / 2;
}

/** One head's counts, the week when the daemon dates them, for the per-head table. */
export interface HeadOutcomes {
  head: string;
  counts: Record<string, number>;
  total: number;
  failed: number;
}

export function headRows(stats: CompactPayload['stats']): HeadOutcomes[] {
  return Object.entries(stats.heads ?? {})
    .map(([head, entry]) => {
      const counts = entry.by_outcome_7d ?? entry.by_outcome;
      const total = Object.values(counts).reduce((sum, n) => sum + n, 0);
      return { head, counts, total, failed: failedOf(counts) };
    })
    .filter((row) => row.total > 0)
    .sort((a, b) => b.total - a.total);
}
