// What the compaction block computes from GET /api/compact: each outcome's meaning, the counts the block leads with, and
// the rules in effect in words. Pure over the daemon's payloads.
import { ABSENT, fmtShare } from './format';
import { W } from './words-compaction';
import type { CompactPayload, CompactRow, InstructionRule, InstructionScopeWire } from '../types/compaction';

/** What an outcome MEANT: a summary was produced, something needs a look, it failed. An outcome this console does not
 *  recognise is a warning, never a success. */
export type CompactState = 'ok' | 'warn' | 'fail';

/** The two picks that are a summary: the model's own text, or its reasoning promoted. Named, never matched by prefix:
 *  `model_text_weak` also starts with `model`, and it is the text that failed the weak-summary check. */
const SUMMARY = new Set(['model_text', 'model_thinking']);
/** Nothing came back: an empty pick, an empty reply, a broken stream. */
const FAILED = new Set(['empty', 'empty_model', 'stream_error', 'upstream_error']);

export function stateOf(outcome: string): CompactState {
  if (SUMMARY.has(outcome)) return 'ok';
  if (FAILED.has(outcome)) return 'fail';
  return 'warn';
}

/** An outcome as a reader says it: a word for a name the daemon is known to write, else its own spelling with
 *  underscores read as spaces. The set stays open. */
export const outcomeText = (outcome: string): string => (W.outcome as Readonly<Record<string, string>>)[outcome] ?? outcome.replaceAll('_', ' ');

export const shareText = (count: number, total: number): string => (total <= 0 ? ABSENT : fmtShare(count / total));

/** The counts the block leads with. A daemon that dates its rows sends the last seven days beside the counted rows, and
 *  those lead: the counted rows reach back to each plan's oldest kept row, so an old failure rate would read as today's. */
export function outcomeCounts(stats: CompactPayload['stats']): { counts: Record<string, number>; total: number; week: boolean } {
  const week = stats.by_outcome_7d;
  if (week === undefined) return { counts: stats.by_outcome, total: stats.total, week: false };
  return { counts: week, total: Object.values(week).reduce((sum, n) => sum + n, 0), week: true };
}

export const failedOf = (counts: Readonly<Record<string, number>>): number =>
  Object.entries(counts).reduce((sum, [outcome, n]) => (stateOf(outcome) === 'fail' ? sum + n : sum), 0);

/** The counts as rows, largest first. */
export const outcomeRows = (counts: Readonly<Record<string, number>>): { outcome: string; count: number }[] =>
  Object.entries(counts).sort(([, a], [, b]) => b - a).map(([outcome, count]) => ({ outcome, count }));

/** The middle value of the tail's field, or null when no row reported it: a stream that failed wrote no summary, it did not
 *  write 0. */
export function medianOf(tail: readonly CompactRow[], field: 'ms' | 'chars'): number | null {
  const values = tail.flatMap((row) => (row[field] === undefined ? [] : [row[field]])).sort((a, b) => a - b);
  if (values.length === 0) return null;
  const mid = Math.floor(values.length / 2);
  return values.length % 2 === 1 ? (values[mid] ?? null) : ((values[mid - 1] ?? 0) + (values[mid] ?? 0)) / 2;
}

/** The newest compactions first, at most `n`. */
export const recentOf = (tail: readonly CompactRow[], n: number): CompactRow[] => [...tail].sort((a, b) => b.ts - a.ts).slice(0, n);

/** The sentence above the outcomes. */
export function compactLede(total: number, failed: number, week: boolean): string {
  if (total === 0) return W.none;
  const count = W.count(total, week);
  return failed === 0 ? `${count} ${W.noneFailed}` : `${count} ${W.someFailed(failed, shareText(failed, total))}`;
}

/** A rule as a person reads it: its scope in words, and the project or model it names when it names one. */
export function ruleText(rule: Pick<InstructionRule, 'scope' | 'source'>): { scope: string; names: string | null } {
  const detail = rule.source.includes(':') ? rule.source.slice(rule.source.indexOf(':') + 1).replace(/ file:.*$/, '') : null;
  return { scope: W.scope[rule.scope as InstructionScopeWire] ?? rule.scope, names: detail === '' ? null : detail };
}

/** A rule's length in words: its characters, an explicit opt-out, or that its file cannot be read. */
export const charsText = (chars: number | null): string => (chars === null ? W.ruleUnreadable : chars === 0 ? W.ruleOptOut : W.ruleChars(chars));

/** The plans a rule applies to, by their names; nothing when the answer names none. */
export const plansText = (heads: readonly string[], labelOf: (key: string) => string): string | null =>
  heads.length === 0 ? null : W.rulePlans(heads.map(labelOf).join(', '));
