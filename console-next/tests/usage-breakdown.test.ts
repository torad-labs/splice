import { describe, expect, test } from 'vitest';
import { hasCompleteUsage, fullWindowUsage, fullUsageBreakdown, mergeWindowStats, budgetWarning, reportedWindowUsage, reportedRequestCount, priceGaps, priceGapLines, cutLines } from '../src/lib/usage-breakdown';
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
    expect(hasCompleteUsage(data())).toBe(true);
    expect(fullWindowUsage(data())).toEqual(stats());
    expect(fullUsageBreakdown(data(), 'model')).toMatchObject([{ key: 'actual-model', turns: 2502, input: 2501000, cost: 1.47559 }]);
  });
  test('an older answering command makes whole-window facts unavailable instead of substituting its slice', () => {
    expect(fullWindowUsage({ ...data(), usageBy: {} })).toBeNull();
    expect(fullWindowUsage({ ...data(), matchedBy: { synthetic: 2502, older: 1 } })).toBeNull();
    expect(fullWindowUsage({ ...data(), matched: null })).toBeNull();
  });
  test('reported aggregates survive a sibling without counts or aggregates without claiming complete coverage', () => {
    const missingCount = { ...data(), matched: null };
    expect(reportedWindowUsage(missingCount)).toEqual(stats());
    expect(reportedRequestCount(missingCount)).toBe(2502);
    const missingAggregate = { ...data(), matchedBy: { synthetic: 2502, older: 1 }, matched: 2503 };
    expect(reportedWindowUsage(missingAggregate)).toEqual(stats());
    expect(reportedRequestCount(missingAggregate)).toBe(2503);
    expect(fullWindowUsage(missingAggregate)).toBeNull();
    expect(reportedWindowUsage({ ...data(), usageBy: {} })).toBeNull();
    expect(reportedRequestCount({ ...data(), usageBy: {}, matchedBy: {}, matched: null })).toBeNull();
    expect(reportedRequestCount({ ...data(), matchedBy: {}, matched: null })).toBe(2502);
  });
  test('pending or unread commands cannot make settled aggregates a complete window', () => {
    for (const requests of [0, 2502]) {
      const settled = { ...data({ synthetic: usage({ totals: stats({ requests }), models: [] }) }), matched: requests, matchedBy: { synthetic: requests } };
      const pending = { ...settled, matched: requests, pendingHeads: ['other'] };
      expect(fullWindowUsage(pending)).toBeNull();
      expect(fullUsageBreakdown(pending, 'model')).toEqual([]);
      expect(reportedWindowUsage(pending)?.requests).toBe(requests);
      expect(fullWindowUsage({ ...pending, pendingHeads: [] })?.requests).toBe(requests);
      expect(fullWindowUsage({ ...settled, unread: [{ head: 'other', reason: 'Synthetic unavailable history' }] })).toBeNull();
      expect(fullWindowUsage({ ...settled, unread: [{ head: 'synthetic', reason: 'One record could not be read' }] })).toBeNull();
    }
  });
  test('unread coverage keeps each reported dimension without becoming an exact total', () => {
    const partial = { ...data(), matched: null, unread: [{ head: 'other', reason: 'Synthetic old record unreadable' }] };
    expect(fullWindowUsage(partial)).toBeNull();
    for (const by of ['model', 'account', 'day'] as const) {
      expect(fullUsageBreakdown(partial, by)).toEqual(fullUsageBreakdown(data(), by));
    }
    expect(fullUsageBreakdown({ ...partial, usageBy: {} }, 'model')).toBeNull();
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

// Marlin's pass 5: one sentence, "no recorded price", stood for three causes. The daemon now names each.
describe('why a request has no price', () => {
  const causes = { unpriced_requests: 6, unpriced_uncounted_requests: 3, unpriced_plan_requests: 2, unpriced_undeclared_requests: 1 };
  test('each cause keeps its own count, and an older daemon leaves its count unexplained', () => {
    expect(priceGaps(stats(causes))).toEqual({ uncounted: 3, plan: 2, undeclared: 1, unknown: 0 });
    expect(priceGaps(stats({ unpriced_requests: 4 }))).toEqual({ uncounted: 0, plan: 0, undeclared: 0, unknown: 4 });
  });
  test('merged commands add each cause, and a command without causes adds only to the unexplained count', () => {
    const merged = mergeWindowStats([stats(causes), stats({ unpriced_requests: 4 })]);
    expect(merged).toMatchObject({ unpriced_requests: 10, unpriced_uncounted_requests: 3, unpriced_plan_requests: 2, unpriced_undeclared_requests: 1 });
    expect(priceGaps(merged)).toEqual({ uncounted: 3, plan: 2, undeclared: 1, unknown: 4 });
    expect(mergeWindowStats([stats()])).toEqual(stats());
  });
  test('a breakdown row carries its causes', () => {
    const reading = data({ synthetic: usage({ models: [{ key: 'deepseek-v4-pro', ...stats({ unpriced_requests: 15, unpriced_uncounted_requests: 15, unpriced_plan_requests: 0, unpriced_undeclared_requests: 0 }) }] }) });
    expect(fullUsageBreakdown(reading, 'model')?.[0]?.gaps).toEqual({ uncounted: 15, plan: 0, undeclared: 0, unknown: 0 });
  });
  test('only a price that was never declared reads as no recorded price', () => {
    expect(priceGapLines({ uncounted: 15, plan: 0, undeclared: 0, unknown: 0 })).toEqual(['15 requests have no token count, so they cannot be priced.']);
    expect(priceGapLines({ uncounted: 0, plan: 1, undeclared: 0, unknown: 0 })).toEqual(['1 request is covered by a plan, so it has no price.']);
    expect(priceGapLines({ uncounted: 0, plan: 0, undeclared: 2, unknown: 0 })).toEqual(['2 requests have no recorded price.']);
    expect(priceGapLines({ uncounted: 0, plan: 0, undeclared: 0, unknown: 1200 })).toEqual(['1,200 requests have no price.']);
    expect(priceGapLines({ uncounted: 1, plan: 2, undeclared: 3, unknown: 0 })).toHaveLength(3);
    expect(priceGapLines({ uncounted: 0, plan: 0, undeclared: 0, unknown: 0 })).toEqual([]);
  });
});

test('local and failed-unanswered causes survive command merges and every breakdown without becoming spend gaps', () => {
  const reported = { ...stats({ requests: 4, unpriced_requests: 2, unpriced_plan_requests: 1 }), unpriced_local_requests: 1, unanswered_requests: 1 };
  const merged = mergeWindowStats([reported, stats({ unpriced_requests: 3 })]);
  expect(merged).toMatchObject({ unpriced_requests: 5, unpriced_local_requests: 1, unanswered_requests: 1 });
  expect(priceGaps(merged)).toMatchObject({ local: 1, unanswered: 1, plan: 1, unknown: 3 });
  const groups = { totals: reported, models: [{ key: 'synthetic-model', ...reported }], accounts: [{ key: 'synthetic-account', ...reported }], days: [{ key: '2026-10-02', ...reported }] };
  for (const by of ['model', 'account', 'day'] as const) {
    const shown = fullUsageBreakdown(data({ synthetic: groups }), by)?.[0];
    expect(shown?.gaps).toMatchObject({ local: 1, unanswered: 1, plan: 1, unknown: 0 });
    if (shown === undefined) throw new Error('daemon group must be present');
    expect(priceGapLines(shown.gaps)).toContain('1 request ran its model on this computer, so it has no provider price.');
    expect(priceGapLines(shown.gaps)).toContain('1 request failed without a recorded answer or token usage.');
    expect(priceGapLines(shown.gaps).join(' ')).not.toContain('missing-spend estimate');
  }
});

// A reply cut off by a new message while it streamed was billed upstream and never reported; the daemon counts it.
describe('replies cut off by a new message', () => {
  test('merged commands add the count, and no command reporting it leaves it absent', () => {
    expect(mergeWindowStats([stats({ cut_source_rounds: 2 }), stats(), stats({ cut_source_rounds: 1 })]).cut_source_rounds).toBe(3);
    expect(mergeWindowStats([stats()])).toEqual(stats());
  });
  test('a breakdown row carries its count, and one sentence says what it means', () => {
    const reading = data({ synthetic: usage({ models: [{ key: 'gpt-5.6-sol', ...stats({ cut_source_rounds: 2 }) }] }) });
    expect(fullUsageBreakdown(reading, 'model')?.[0]?.cut).toBe(2);
    expect(fullUsageBreakdown(data(), 'model')?.[0]?.cut).toBe(0);
    expect(cutLines(1)).toEqual(['1 reply was cut off by a new message, so its tokens are not reported.']);
    expect(cutLines(2)).toEqual(['2 replies were cut off by a new message, so their tokens are not reported.']);
    expect(cutLines(0)).toEqual([]);
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
