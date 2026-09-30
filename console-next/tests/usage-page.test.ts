// The Usage page's arithmetic: the windows the retention allows, a plan against its limit, the pace sentence, idle plans.
import { describe, expect, test } from 'vitest';
import { orderPlans, paceText, planUsage, splitIdle, totalsOf, usageLede, windowChoices } from '../src/lib/usage-page';
import type { PlanUsage } from '../src/lib/usage-page';
import type { EconomicsBucket, HeadEconomics } from '../src/types/economics';

const HOUR = 3_600_000;
const NOW = 1_000 * HOUR + 1000;
const bucket = (agoHours: number, over: Partial<EconomicsBucket> = {}): EconomicsBucket => ({
  hour: 1_000 * HOUR - agoHours * HOUR, turns: 10, in_tokens: 1_000_000, cached_tokens: 900_000, cache_write_tokens: 0, out_tokens: 10_000,
  req_bytes: 0, upstream_req_bytes: 0, tools_eager: 0, tools_deferred: 0, deferral_turns: 0, rate_limited: 0, cost_usd: 2, unpriced_turns: 0, ...over,
});
const head = (key: string, buckets: EconomicsBucket[], ceiling: number | null = null): HeadEconomics => ({ key, label: key, ceiling_tokens: ceiling, buckets });
const plan = (over: Partial<PlanUsage>): PlanUsage => ({
  key: 'a', label: 'A', colour: 'none', pct: null, full: false, reset: null, pace: null, turns: 1, inTokens: 1, cache: null, cost: null, spark: [], spentToday: null, ...over,
});

describe('windows', () => {
  test('a day always, a week and a month only when the daemon keeps them', () => {
    expect(windowChoices(24).map(([id]) => id)).toEqual(['24']);
    expect(windowChoices(168).map(([id]) => id)).toEqual(['24', '168']);
    expect(windowChoices(720).map(([id]) => id)).toEqual(['24', '168', '720']);
  });
});

describe('pace', () => {
  test('null when nothing can be projected, never a guess', () => {
    expect(paceText(null)).toBeNull();
    expect(paceText(Infinity)).toBeNull();
  });
  test('hours under two days, days after, and less than an hour', () => {
    expect(paceText(0.4)).toBe('less than an hour left at this pace');
    expect(paceText(9)).toBe('about 9 hours left at this pace');
    expect(paceText(72)).toBe('about 3 days left at this pace');
  });
});

describe('a plan', () => {
  test('with no turns in the window carries no cost, not $0', () => {
    const idle = planUsage(head('a', []), 'A', 'none', null, 24, NOW);
    expect(idle.cost).toBeNull();
    expect(idle.turns).toBe(0);
  });
  test('its sums cover the window, and the cost is null when no turn was priced', () => {
    const unpriced = planUsage(head('a', [bucket(1, { cost_usd: null })]), 'A', 'none', null, 24, NOW);
    expect(unpriced.inTokens).toBe(1_000_000);
    expect(unpriced.cost).toBeNull();
    const priced = planUsage(head('a', [bucket(1), bucket(30)]), 'A', 'none', null, 24, NOW);
    expect(priced.cost).toBe(2);
    expect(priced.cache).toBeCloseTo(0.9);
  });
  test('the sparkline is the last 24 hours, idle hours as zeros', () => {
    const spark = planUsage(head('a', [bucket(1)]), 'A', 'none', null, 168, NOW).spark;
    expect(spark).toHaveLength(24);
    expect(spark.filter((n) => n > 0)).toHaveLength(1);
  });
  test('the fullest limit goes first, plans that report none last', () => {
    const order = orderPlans([plan({ key: 'a', pct: null, inTokens: 9 }), plan({ key: 'b', pct: 90 }), plan({ key: 'c', pct: 20 })]);
    expect(order.map((p) => p.key)).toEqual(['b', 'c', 'a']);
  });
  test('an idle plan with no limit is one sentence, a plan at a limit stays a row', () => {
    const split = splitIdle([plan({ key: 'a', turns: 0 }), plan({ key: 'b', turns: 0, pct: 100 }), plan({ key: 'c', turns: 4 })]);
    expect(split.idle.map((p) => p.key)).toEqual(['a']);
    expect(split.active.map((p) => p.key)).toEqual(['b', 'c']);
  });
});

describe('the lede', () => {
  test('names the cost, and the nearest plan only when it is close', () => {
    const totals = totalsOf([head('a', [bucket(1)])], 24, NOW);
    expect(usageLede(totals, [plan({ pct: 40 })], 24)).toBe('About $2.00 of API cost in the last 24 hours.');
    expect(usageLede(totals, [plan({ label: 'ChatGPT', pct: 100, full: true })], 24)).toContain('ChatGPT is out of quota.');
    expect(usageLede(totals, [plan({ label: 'Kimi', pct: 82.4 })], 24)).toContain('Kimi is at 82% of its limit.');
  });
  test('says none priced rather than $0 when no turn has a price', () => {
    const totals = totalsOf([head('a', [bucket(1, { cost_usd: null })])], 24, NOW);
    expect(usageLede(totals, [], 24)).toBe('10 turns in the last 24 hours, none priced.');
  });
  test('an empty window says so', () => {
    expect(usageLede(totalsOf([], 24, NOW), [], 168)).toBe('No turns in the last 7 days.');
  });
});
