import { describe, expect, test } from 'vitest';
import { usageBreakdown, usageTotals, budgetWarning } from '../src/lib/usage-breakdown';
import type { TurnRow } from '../src/types/perf';

const row = (over: Partial<TurnRow> = {}): TurnRow => ({
  head: 'test-head', ts: Date.parse('2026-10-03T05:30:00Z'), model: 'model-a', compact: false,
  outcome: 'ok', in_tokens: 100, out_tokens: 10, cost_usd: 0.5, ...over,
});

describe('request-backed usage', () => {
  test('refusals without tokens remain requests but never become zero spending or tokens', () => {
    const failed = row({ outcome: 'quota-refused', cost_usd: null });
    delete failed.in_tokens;
    delete failed.out_tokens;
    expect(usageTotals([failed])).toEqual({ requests: 1, cost: null, input: null, output: null, unpriced: 1, missingInput: 1, missingOutput: 1 });
    expect(usageTotals([failed, row(), row({ local_step: 1 })])).toMatchObject({ requests: 2, input: 100, output: 10, cost: 0.5, unpriced: 1 });
  });
  test('models and accounts use request attribution, never the head selected now', () => {
    const rows = [row({ account: 'work' }), row({ model: 'model-b', account: 'spare', cost_usd: 1 }), row()];
    expect(usageBreakdown(rows, 'model').map(item => [item.key, item.turns, item.cost])).toEqual([['model-a', 2, 1], ['model-b', 1, 1]]);
    expect(usageBreakdown(rows, 'account').map(item => [item.key, item.head, item.turns])).toEqual([['spare', 'test-head', 1], ['work', 'test-head', 1], [null, 'test-head', 1]]);
  });
  test('equal account labels on different heads stay separate', () => {
    expect(usageBreakdown([row({ account: 'work' }), row({ head: 'other', account: 'work' })], 'account')).toHaveLength(2);
  });
  test('local code-mode steps are not billed again as independent requests', () => {
    const result = usageBreakdown([row(), row({ local_step: 1, cost_usd: 9 })], 'model');
    expect(result[0]).toMatchObject({ turns: 1, cost: 0.5, input: 100, output: 10 });
  });
  test('missing costs or token facts remain missing, while partial priced amounts retain their gap count', () => {
    const unreported = row({ cost_usd: null });
    delete unreported.in_tokens;
    delete unreported.out_tokens;
    const none = usageBreakdown([unreported], 'model')[0];
    expect(none).toMatchObject({ cost: null, input: null, output: null, unpriced: 1, missingInput: 1, missingOutput: 1 });
    expect(usageBreakdown([row(), row({ cost_usd: null })], 'model')[0]).toMatchObject({ cost: 0.5, unpriced: 1, turns: 2 });
  });
  test('calendar days use the viewer zone at its own midnight', () => {
    const before = new Date(2026, 9, 2, 23, 59).getTime();
    const after = new Date(2026, 9, 3, 0, 1).getTime();
    expect(usageBreakdown([row({ ts: before }), row({ ts: after })], 'day').map(item => item.key)).toEqual(['2026-10-03', '2026-10-02']);
    const autumn = [row({ ts: new Date(2026, 10, 1, 1, 30).getTime() }), row({ ts: new Date(2026, 10, 1, 2, 30).getTime() })];
    expect(usageBreakdown(autumn, 'day')).toHaveLength(1);
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
