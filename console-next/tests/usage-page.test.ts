// The Usage page's arithmetic: the windows the retention allows, a plan against its limit, the pace sentence, idle plans.
import { describe, expect, test } from 'vitest';
import { orderPlans, paceText, planUsage, splitIdle, totalsOf, usageLede, windowChoices } from '../src/lib/usage-page';
import type { PlanUsage } from '../src/lib/usage-page';
import type { EconomicsBucket, HeadEconomics } from '../src/types/economics';
import type { HeadStatus, UsagePayload } from '../src/types/core';

const HOUR = 3_600_000;
const NOW = 1_000 * HOUR + 1000;
const bucket = (agoHours: number, over: Partial<EconomicsBucket> = {}): EconomicsBucket => ({
  hour: 1_000 * HOUR - agoHours * HOUR, turns: 10, in_tokens: 1_000_000, cached_tokens: 900_000, cache_write_tokens: 0, out_tokens: 10_000,
  req_bytes: 0, upstream_req_bytes: 0, tools_eager: 0, tools_deferred: 0, deferral_turns: 0, rate_limited: 0, cost_usd: 2, unpriced_turns: 0, ...over,
});
const head = (key: string, buckets: EconomicsBucket[], ceiling: number | null = null): HeadEconomics => ({ key, label: key, ceiling_tokens: ceiling, buckets });
const plan = (over: Partial<PlanUsage>): PlanUsage => ({
  key: 'a', label: 'A', colour: 'none', pct: null, full: false, reset: null, limitWindow: null, reading: null, pace: null, turns: 1, inTokens: 1, cache: null, cost: null, spark: [], spentToday: null, ...over,
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
  test('a header-only reading keeps its age without presenting a stale token limit as a current percentage', () => {
    const reading: UsagePayload = { window_hours: 5, warn_pct: 80, warn_tokens_5h: 0, heads: [{ key: 'a', label: 'A', usage: {
      output_tokens_5h: 0, entries: 0, ratelimit: { limit_tokens: 53_000_000, remaining_tokens: 53_000_000, reset_tokens: '1h', observed_at: NOW / 1000 - 43 * 3600 },
      warn: { pct: 0, level: 'ok', source: 'none', reset: null },
    } }] };
    const held = planUsage(head('a', []), 'A', 'grok', reading, 24, NOW);
    expect(held.pct).toBeNull();
    expect(held.reading).toBe('Last rate-limit reading 43h ago');
    expect(splitIdle([held]).active).toEqual([held]);
  });
  test('the winning quota keeps its own window name and viewer-zone reset instant', () => {
    const reset = NOW / 1000 + 3600;
    const reading: UsagePayload = { window_hours: 5, warn_pct: 80, warn_tokens_5h: 0, heads: [{ key: 'a', label: 'A', usage: {
      output_tokens_5h: 0, entries: 0, ratelimit: null, warn: { pct: 0, level: 'ok', source: 'none', reset: null },
      quota: { five_hour: { used_pct: 79, resets_at: reset }, seven_day: { used_pct: 30, resets_at: reset + 86400 } },
    } }] };
    const held = planUsage(head('a', []), 'A', 'gpt', reading, 24, NOW);
    expect(held).toMatchObject({ pct: 79, limitWindow: '5h' });
    expect(held.reset).toBe(new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZoneName: 'short' }).format(new Date(reset * 1000)));
  });

  test('a command without turns is not repeated in the no-turns sentence when its limit needs a row', () => {
    const held = plan({ key: 'grok', turns: 0, pct: 79 });
    const split = splitIdle([held, plan({ key: 'busy', turns: 1 })]);
    expect(split.active).toContain(held);
    expect(split.idle).not.toContain(held);
  });

  test('a missing request count remains visible and is never called idle', () => {
    const unknown = plan({ turns: null });
    expect(splitIdle([unknown]).active).toContain(unknown);
    expect(splitIdle([unknown]).idle).toEqual([]);
  });

  test('with no turns in the window carries no cost, not $0', () => {
    const idle = planUsage(head('a', []), 'A', 'none', null, 24, NOW);
    expect(idle.cost).toBeNull();
    expect(idle.turns).toBe(0);
  });
  test('a full reading is not a refusal, and only a future held refusal makes its gauge full', () => {
    const reading: UsagePayload = {
      window_hours: 24, warn_pct: 80, warn_tokens_5h: 0,
      heads: [{ key: 'a', label: 'A', usage: { output_tokens_5h: 0, entries: 0, ratelimit: null, warn: { pct: 0, level: 'ok', source: 'none', reset: null }, quota: { five_hour: { used_pct: 100, resets_at: NOW / 1000 + 3600 } } } }],
    };
    const status = (until?: number) => ({ key: 'a', quotaResetAtEpochSeconds: until }) as HeadStatus;
    expect(planUsage(head('a', []), 'A', 'gpt', reading, 24, NOW).full).toBe(false);
    expect(planUsage(head('a', []), 'A', 'gpt', reading, 24, NOW, status(NOW / 1000 + 60)).full).toBe(true);
    expect(planUsage(head('a', []), 'A', 'gpt', reading, 24, NOW, status(NOW / 1000)).full).toBe(false);
  });
  test('an idle refused command without a reading stays visible, not buried among idle plans', () => {
    const held = plan({ key: 'held', turns: 0, pct: null, full: true });
    const split = splitIdle([held, plan({ key: 'idle', turns: 0 })]);
    expect(split.active).toEqual([held]);
    expect(split.idle.map((item) => item.key)).toEqual(['idle']);
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
    expect(usageLede(totals, [plan({ pct: 40 })], 24)).toBe('About $2.00 of recorded API cost in the last 24 hours.');
    expect(usageLede(totals, [plan({ label: 'ChatGPT', pct: 100, full: false, limitWindow: '5h' })], 24)).toContain('ChatGPT is at 100% of its 5-hour limit.');
    expect(usageLede(totals, [plan({ label: 'ChatGPT', pct: 33, full: true })], 24)).toContain('ChatGPT is out of quota.');
    expect(usageLede(totals, [plan({ label: 'ChatGPT', pct: null, full: true })], 24)).toContain('ChatGPT is out of quota.');
    expect(usageLede(totals, [plan({ label: 'Reading', pct: 100 }), plan({ label: 'Held', pct: null, full: true })], 24)).toContain('Held is out of quota.');
    expect(usageLede(totals, [plan({ label: 'Kimi', pct: 82.4, limitWindow: '7d' })], 24)).toContain('Kimi is at 82% of its weekly limit.');
  });
  test('says none priced rather than $0 when no turn has a price', () => {
    const totals = totalsOf([head('a', [bucket(1, { cost_usd: null })])], 24, NOW);
    expect(usageLede(totals, [], 24)).toBe('10 requests in the last 24 hours. API cost is not reported.');
  });
  test('the matched request count includes refusals without economics and never claims no requests while loading', () => {
    const emptyEconomics = totalsOf([], 24, NOW);
    expect(usageLede(emptyEconomics, [], 24, 7)).toContain('7 requests');
    expect(usageLede(emptyEconomics, [], 24, null)).not.toContain('No requests');
  });
  test('incomplete price coverage names the known amount as a lower bound', () => {
    expect(usageLede(totalsOf([], 24, NOW), [], 24, 2502, 1.47559, true)).toContain('At least $1.48');
  });
  test('an empty window says so', () => {
    expect(usageLede(totalsOf([], 24, NOW), [], 168)).toBe('No requests in the last 7 days.');
  });
});
