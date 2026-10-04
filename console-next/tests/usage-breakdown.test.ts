import { describe, expect, test } from 'vitest';
import { fullWindowUsage, fullUsageBreakdown, mergeWindowStats, budgetWarning } from '../src/lib/usage-breakdown';
import type { TurnsState, TurnUsageStats, TurnUsageWire } from '../src/types/perf';

const stats = (over: Partial<TurnUsageStats> = {}): TurnUsageStats => ({
  requests: 2502, input_tokens: 2501000, cached_tokens: 2250900, output_tokens: 250100,
  cost_usd: 1.47559, cache_share: 0.9, unpriced_requests: 1, missing_input_requests: 1,
  missing_output_requests: 1, missing_cache_requests: 0, ...over,
});
const usage = (over: Partial<TurnUsageWire> = {}): TurnUsageWire => ({
  totals: stats(), models: [{ key: 'actual-model', ...stats() }], accounts: [{ key: 'work', ...stats() }],
  days: [{ key: '2026-10-02', ...stats() }], ...over,
});
const data = (usageBy: Record<string, TurnUsageWire> = { synthetic: usage() }): TurnsState => ({
  landed: [{ head: 'synthetic', ts: 100, model: 'slice-only', compact: false, outcome: 'ok', in_tokens: 1, cost_usd: 99 }],
  inflight: [], unread: [], truncated: [{ head: 'synthetic', count: 2502, returned: 1 }], matched: 2502,
  matchedBy: { synthetic: 2502 }, usageBy, window: { since: 100, until: 200 }, completeFrom: 150,
});

describe('daemon-backed usage', () => {
  test('a capped slice cannot shrink complete-window tokens, spend or groups', () => {
    expect(fullWindowUsage(data())).toEqual(stats());
    expect(fullUsageBreakdown(data(), 'model')).toMatchObject([{ key: 'actual-model', turns: 2502, input: 2501000, cost: 1.47559 }]);
  });
  test('an older answering command makes whole-window facts unavailable instead of substituting its slice', () => {
    expect(fullWindowUsage({ ...data(), usageBy: {} })).toBeNull();
    expect(fullWindowUsage({ ...data(), matchedBy: { synthetic: 2502, older: 1 } })).toBeNull();
    expect(fullWindowUsage({ ...data(), matched: null })).toBeNull();
  });
  test('a refusal without counters or prices remains a request, not zero usage', () => {
    const refused = stats({ requests: 1, input_tokens: null, output_tokens: null, cached_tokens: null, cost_usd: null, cache_share: null });
    expect(mergeWindowStats([refused])).toMatchObject({ requests: 1, input_tokens: null, output_tokens: null, cost_usd: null, unpriced_requests: 1 });
    expect(mergeWindowStats([stats(), refused])).toMatchObject({ requests: 2503, input_tokens: 2501000, cost_usd: 1.47559, unpriced_requests: 2 });
  });
  test('cache share is token-weighted across commands and unknown cache counters invalidate it', () => {
    expect(mergeWindowStats([stats({ input_tokens: 100, cached_tokens: 90 }), stats({ input_tokens: 900, cached_tokens: 0 })]).cache_share).toBe(0.09);
    expect(mergeWindowStats([stats(), stats({ missing_cache_requests: 1 })]).cache_share).toBeNull();
  });
  test('model totals combine while equal account names on separate commands stay separate', () => {
    const both = { ...data({ synthetic: usage(), other: usage() }), matchedBy: { synthetic: 2502, other: 2502 } };
    expect(fullUsageBreakdown(both, 'model')).toMatchObject([{ key: 'actual-model', turns: 5004 }]);
    expect(fullUsageBreakdown(both, 'account')?.map(row => row.head)).toEqual(['synthetic', 'other']);
  });
  test('unattributed groups and viewer days keep the daemon keys without browser reclassification', () => {
    const reading = data({ synthetic: usage({ models: [{ key: null, ...stats() }], days: [{ key: '2026-10-02', ...stats() }, { key: '2026-10-03', ...stats() }] }) });
    expect(fullUsageBreakdown(reading, 'model')).toMatchObject([{ key: null }]);
    expect(fullUsageBreakdown(reading, 'day')?.map(row => row.key)).toEqual(['2026-10-03', '2026-10-02']);
  });
});

describe('early budget warnings', () => {
  test('no cap or unknown spend is not a made-up remaining balance', () => {
    expect(budgetWarning(null, 5)).toBeNull();
    expect(budgetWarning(10, null)).toBeNull();
    expect(budgetWarning(10, 0)).toBeNull();
  });
  test('warns before exhaustion and distinguishes a recorded overrun', () => {
    expect(budgetWarning(10, 7.9)).toBeNull();
    expect(budgetWarning(10, 8)).toEqual({ kind: 'near', remaining: 2, share: 0.8 });
    expect(budgetWarning(10, 12)).toEqual({ kind: 'reached', remaining: 0, share: 1.2 });
  });
});
