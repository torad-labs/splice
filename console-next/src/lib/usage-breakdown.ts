// Usage combines complete-window daemon aggregates, never the displayed request rows.
import type { TurnsState, TurnUsageStats } from '../types/perf';

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
}

/** Adds daemon aggregates across commands; absent token and price facts remain absent. */
export function mergeWindowStats(stats: readonly TurnUsageStats[]): TurnUsageStats {
  const sum = (key: 'input_tokens' | 'cached_tokens' | 'output_tokens' | 'cost_usd'): number | null => stats.reduce<number | null>((total, row) => row[key] === null ? total : (total ?? 0) + row[key], null);
  const count = (key: 'requests' | 'unpriced_requests' | 'missing_input_requests' | 'missing_output_requests' | 'missing_cache_requests'): number => stats.reduce((total, row) => total + row[key], 0);
  const input = sum('input_tokens');
  const cached = sum('cached_tokens');
  const missingCache = count('missing_cache_requests');
  return { requests: count('requests'), input_tokens: input, cached_tokens: cached, output_tokens: sum('output_tokens'), cost_usd: sum('cost_usd'), cache_share: input !== null && input > 0 && cached !== null && missingCache === 0 ? cached / input : null, unpriced_requests: count('unpriced_requests'), missing_input_requests: count('missing_input_requests'), missing_output_requests: count('missing_output_requests'), missing_cache_requests: missingCache };
}

export function fullWindowUsage(data: TurnsState | null): TurnUsageStats | null {
  if (data?.usageBy === undefined || data.matched === null || Object.keys(data.usageBy).length === 0 || Object.keys(data.matchedBy).some(head => data.usageBy?.[head] === undefined)) return null;
  return mergeWindowStats(Object.values(data.usageBy).map(usage => usage.totals));
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

export function fullUsageBreakdown(data: TurnsState, by: UsageDimension): UsageBreakdown[] | null {
  if (fullWindowUsage(data) === null) return null;
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
    return { id, key: group.key, head: group.head, turns: stats.requests, input: stats.input_tokens, output: stats.output_tokens, cost: stats.cost_usd, unpriced: stats.unpriced_requests, missingInput: stats.missing_input_requests, missingOutput: stats.missing_output_requests };
  }).sort((a, b) => by === 'day' ? (b.key ?? '').localeCompare(a.key ?? '') : (b.cost ?? -1) - (a.cost ?? -1) || b.turns - a.turns || (a.key ?? '').localeCompare(b.key ?? ''));
}

export function budgetWarning(cap: number | null, spent: number | null): { kind: 'near' | 'reached'; remaining: number; share: number } | null {
  if (cap === null || cap <= 0 || spent === null || spent < cap * 0.8) return null;
  return { kind: spent >= cap ? 'reached' : 'near', remaining: Math.max(0, cap - spent), share: spent / cap };
}
