// Usage combines complete-window daemon aggregates, never the displayed request rows.
import type { TurnsState, TurnUsageStats } from '../types/perf';
import { U } from './words-usage';

export type UsageDimension = 'model' | 'account' | 'day';
export interface UsageBreakdown {
  id: string;
  key: string | null;
  head: string | null;
  turns: number;
  cost: number | null;
  unpriced: number;
  input: number | null;
  output: number | null;
  missingInput: number;
  missingOutput: number;
  gaps: PriceGaps;
  /** Replies stopped before completion, whose tokens were never reported; the cause is not identified. */
  cut: number;
}

/** Why requests have no dollar figure. [unknown] is the count a daemon older than the causes left unexplained. */
export interface PriceGaps { uncounted: number; plan: number; undeclared: number; unknown: number; local?: number; unanswered?: number }

const CAUSES = ['unpriced_uncounted_requests', 'unpriced_plan_requests', 'unpriced_undeclared_requests'] as const;

export function priceGaps(stats: TurnUsageStats): PriceGaps {
  const uncounted = stats.unpriced_uncounted_requests ?? 0;
  const plan = stats.unpriced_plan_requests ?? 0;
  const undeclared = stats.unpriced_undeclared_requests ?? 0;
  const local = stats.unpriced_local_requests ?? 0;
  return { uncounted, plan, undeclared, unknown: Math.max(0, stats.unpriced_requests - uncounted - plan - undeclared - local), ...(stats.unpriced_local_requests === undefined ? {} : { local }), ...(stats.unanswered_requests === undefined ? {} : { unanswered: stats.unanswered_requests }) };
}

/** One sentence per cause. Only a price that was never declared reads as "no recorded price". */
export function priceGapLines(gaps: PriceGaps): string[] {
  const lines: [number, (n: number) => string][] = [[gaps.uncounted, U.unpricedUncounted], [gaps.plan, U.unpricedPlan], [gaps.local ?? 0, U.unpricedLocal], [gaps.unanswered ?? 0, U.unanswered], [gaps.undeclared, U.unpricedUndeclared], [gaps.unknown, U.unpricedUnknown]];
  return lines.flatMap(([n, line]) => n === 0 ? [] : [line(n)]);
}

/** The sentence for replies stopped before completion, or none. */
export function cutLines(cut: number): string[] {
  return cut === 0 ? [] : [U.cutRounds(cut)];
}

/** Adds daemon aggregates across commands; absent token and price facts remain absent. */
export function mergeWindowStats(stats: readonly TurnUsageStats[]): TurnUsageStats {
  const sum = (key: 'input_tokens' | 'cached_tokens' | 'output_tokens' | 'cost_usd'): number | null => stats.reduce<number | null>((total, row) => row[key] === null ? total : (total ?? 0) + row[key], null);
  const count = (key: 'requests' | 'unpriced_requests' | 'missing_input_requests' | 'missing_output_requests' | 'missing_cache_requests'): number => stats.reduce((total, row) => total + row[key], 0);
  const input = sum('input_tokens');
  const cached = sum('cached_tokens');
  const missingCache = count('missing_cache_requests');
  const merged: TurnUsageStats = { requests: count('requests'), input_tokens: input, cached_tokens: cached, output_tokens: sum('output_tokens'), cost_usd: sum('cost_usd'), cache_share: input !== null && input > 0 && cached !== null && missingCache === 0 ? cached / input : null, unpriced_requests: count('unpriced_requests'), missing_input_requests: count('missing_input_requests'), missing_output_requests: count('missing_output_requests'), missing_cache_requests: missingCache };
  // A command without causes adds its unpriced requests to the total only, so they stay unexplained.
  const cause = (key: typeof CAUSES[number]): number => stats.reduce((total, row) => total + (row[key] ?? 0), 0);
  const caused = stats.some(row => CAUSES.some(key => row[key] !== undefined))
    ? { ...merged, unpriced_uncounted_requests: cause('unpriced_uncounted_requests'), unpriced_plan_requests: cause('unpriced_plan_requests'), unpriced_undeclared_requests: cause('unpriced_undeclared_requests') }
    : merged;
  // New causes and source-cut evidence stay absent when no command reports them.
  const optional = ['unpriced_local_requests', 'unanswered_requests', 'cut_source_rounds'] as const;
  return optional.reduce<TurnUsageStats>((merged, key) => stats.some(row => row[key] !== undefined)
    ? { ...merged, [key]: stats.reduce((total, row) => total + (row[key] ?? 0), 0) }
    : merged, caused);
}

/** Full-window facts require every command to settle with readable counts and aggregates. */
export function hasCompleteUsage(data: TurnsState | null): boolean {
  return data !== null && data.unread.length === 0 && (data.pendingHeads?.length ?? 0) === 0 && data.matched !== null && data.usageBy !== undefined && Object.keys(data.usageBy).length > 0 && Object.keys(data.matchedBy).every(head => data.usageBy?.[head] !== undefined);
}

export function fullWindowUsage(data: TurnsState | null): TurnUsageStats | null {
  return hasCompleteUsage(data) ? reportedWindowUsage(data) : null;
}

/** Known command aggregates remain useful as lower bounds when fleet coverage is incomplete. */
export function reportedWindowUsage(data: TurnsState | null): TurnUsageStats | null {
  const stats = Object.values(data?.usageBy ?? {}).map(usage => usage.totals);
  return stats.length === 0 ? null : mergeWindowStats(stats);
}

/** Known request counts remain useful when a sibling did not report its count. */
export function reportedRequestCount(data: TurnsState | null): number | null {
  if (data === null) return null;
  if (data.matched !== null) return data.matched;
  const heads = new Set([...Object.keys(data.matchedBy), ...Object.keys(data.usageBy ?? {})]);
  return heads.size === 0 ? null : [...heads].reduce((sum, head) => sum + (data.matchedBy[head] ?? data.usageBy?.[head]?.totals.requests ?? 0), 0);
}

/** Whole-window groups from reporting commands remain visible when sibling coverage is incomplete. */
export function fullUsageBreakdown(data: TurnsState, by: UsageDimension): UsageBreakdown[] | null {
  if (reportedWindowUsage(data) === null) return null;
  const groups = new Map<string, { key: string | null; head: string | null; stats: TurnUsageStats[] }>();
  for (const [command, usage] of Object.entries(data.usageBy ?? {})) {
    for (const row of usage[by === 'model' ? 'models' : by === 'account' ? 'accounts' : 'days']) {
      const head = by === 'account' ? command : null;
      const id = JSON.stringify([head, row.key]);
      const group = groups.get(id) ?? { key: row.key, head, stats: [] };
      group.stats.push(row);
      groups.set(id, group);
    }
  }
  return [...groups.entries()].map(([id, group]) => {
    const stats = mergeWindowStats(group.stats);
    return { id, key: group.key, head: group.head, turns: stats.requests, input: stats.input_tokens, output: stats.output_tokens, cost: stats.cost_usd, unpriced: stats.unpriced_requests, missingInput: stats.missing_input_requests, missingOutput: stats.missing_output_requests, gaps: priceGaps(stats), cut: stats.cut_source_rounds ?? 0 };
  }).sort((a, b) => by === 'day' ? (b.key ?? '').localeCompare(a.key ?? '') : (b.cost ?? -1) - (a.cost ?? -1) || b.turns - a.turns || (a.key ?? '').localeCompare(b.key ?? ''));
}

export function budgetWarning(cap: number | null, spent: number | null): { kind: 'near' | 'reached'; remaining: number; share: number } | null {
  if (cap === null || cap <= 0 || spent === null || spent < cap * 0.8) return null;
  return { kind: spent >= cap ? 'reached' : 'near', remaining: Math.max(0, cap - spent), share: spent / cap };
}
