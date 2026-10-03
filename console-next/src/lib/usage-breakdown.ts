// Only facts recorded on a retained request can attribute its spend or tokens.
import type { TurnRow } from '../types/perf';

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

const viewerDay = new Intl.DateTimeFormat('en-CA', {
  year: 'numeric', month: '2-digit', day: '2-digit',
});

export function usageBreakdown(rows: readonly TurnRow[], by: UsageDimension): UsageBreakdown[] {
  const groups = new Map<string, UsageBreakdown>();
  for (const row of rows) {
    if (row.local_step === 1) continue;
    const parts = by === 'day' ? Object.fromEntries(viewerDay.formatToParts(row.ts).map(part => [part.type, part.value])) : null;
    const key = by === 'model' ? row.model : by === 'account' ? row.account ?? null : `${parts?.['year']}-${parts?.['month']}-${parts?.['day']}`;
    const head = by === 'account' ? row.head : null;
    const id = JSON.stringify([head, key]);
    const group = groups.get(id) ?? {
      id, key, head, turns: 0, cost: null, unpriced: 0, input: null, output: null, missingInput: 0, missingOutput: 0,
    };
    group.turns++;
    if (row.cost_usd == null) group.unpriced++;
    else group.cost = (group.cost ?? 0) + row.cost_usd;
    if (row.in_tokens === undefined) group.missingInput++;
    else group.input = (group.input ?? 0) + row.in_tokens;
    if (row.out_tokens === undefined) group.missingOutput++;
    else group.output = (group.output ?? 0) + row.out_tokens;
    groups.set(id, group);
  }
  return [...groups.values()].sort((a, b) => by === 'day'
    ? (b.key ?? '').localeCompare(a.key ?? '')
    : (b.cost ?? -1) - (a.cost ?? -1) || b.turns - a.turns || Number(a.key === null) - Number(b.key === null) || (a.key ?? '').localeCompare(b.key ?? ''));
}

/** Sums only recorded facts; absent prices or tokens remain unknown. */
export function usageTotals(rows: readonly TurnRow[]) {
  const groups = usageBreakdown(rows, 'model');
  const sum = (key: 'cost' | 'input' | 'output'): number | null => groups.reduce<number | null>((total, group) => group[key] === null ? total : (total ?? 0) + group[key], null);
  return {
    requests: groups.reduce((total, group) => total + group.turns, 0),
    cost: sum('cost'), input: sum('input'), output: sum('output'),
    unpriced: groups.reduce((total, group) => total + group.unpriced, 0),
    missingInput: groups.reduce((total, group) => total + group.missingInput, 0),
    missingOutput: groups.reduce((total, group) => total + group.missingOutput, 0),
  };
}

export function budgetWarning(cap: number | null, spent: number | null): { kind: 'near' | 'reached'; remaining: number; share: number } | null {
  if (cap === null || cap <= 0 || spent === null || spent < cap * 0.8) return null;
  return { kind: spent >= cap ? 'reached' : 'near', remaining: Math.max(0, cap - spent), share: spent / cap };
}
