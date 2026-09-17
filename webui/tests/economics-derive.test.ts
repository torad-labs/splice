// WALLS for the burn page's arithmetic. These numbers decide whether an operator keeps working
// or stops, so each derivation is pinned directly rather than eyeballed on screen.
//
// The load-bearing one is `burn uses TOTAL input, not uncached`. That is not a style preference:
// on 2026-08-01 two independent exhaustion windows were measured, and total input predicted the
// plan meter while uncached ordered the wrong way round (571M sat at 97%, a SMALLER 390M hit
// 100%). A gauge built on uncached tokens reads comfortable at the exact moment the quota dies.
import { describe, expect, test } from 'vitest';
import {
  sum, within, hitRate, writeRate, amplification, perTurn, toolSurface, wireDelta, burn, hourly,
} from '../src/entities/economics/model/derive';
import type { EconomicsBucket, HeadEconomics } from '../src/shared/api';

const HOUR = 3_600_000;
const NOW = 1_700_000_000_000;

function bucket(hoursAgo: number, over: Partial<EconomicsBucket> = {}): EconomicsBucket {
  return {
    hour: Math.floor((NOW - hoursAgo * HOUR) / HOUR) * HOUR,
    turns: 0, in_tokens: 0, cached_tokens: 0, cache_write_tokens: 0, out_tokens: 0,
    req_bytes: 0, upstream_req_bytes: 0,
    tools_eager: 0, tools_deferred: 0, deferral_turns: 0, rate_limited: 0,
    ...over,
  };
}

function head(buckets: EconomicsBucket[], ceiling: number | null = null): HeadEconomics {
  return { key: 'claudex', label: 'claudex', ceiling_tokens: ceiling, buckets };
}

describe('the metered quantity', () => {
  /** THE ONE THAT MATTERS. A 90%-cached prompt bills in full. */
  test('burn spends TOTAL input, never input minus cached', () => {
    const b = burn(head([bucket(1, { turns: 1, in_tokens: 1_000_000, cached_tokens: 900_000 })], 2_000_000), NOW);
    expect(b.spent).toBe(1_000_000);
    expect(b.spent).not.toBe(100_000); // the uncached figure, which would read as 5% not 50%
    expect(b.fraction).toBeCloseTo(0.5, 5);
  });

  test('a high hit rate does not reduce the burn', () => {
    const cold = burn(head([bucket(1, { in_tokens: 1e6, cached_tokens: 0 })], 1e6), NOW);
    const warm = burn(head([bucket(1, { in_tokens: 1e6, cached_tokens: 999_999 })], 1e6), NOW);
    expect(warm.spent).toBe(cold.spent);
    expect(warm.fraction).toBe(cold.fraction);
  });
});

// V4-86. The daemon now ships a THIRD token bucket per hour: the cache-WRITE half of the metered
// input, disjoint from the cache-READ half. It arrives here because it is the one bucket a vendor
// charges a different rate for (Anthropic's Sonnet card: input 3.00, cache_write 3.75, cache_read
// 0.30 per million), so an operator reading "89% hit" needs to know whether the remaining input
// was a plain miss or a rebuild. NOTE FOR ANYONE EXTENDING THIS FILE: there is no dollar figure on
// the burn page and no rate on this side of the wire at all — the rollup is keyed per head per
// hour with NO model dimension, so no single rate can be correctly applied to a bucket. These
// tests therefore pin the SUM and the SHARE, which is everything this side computes.
describe('the cache-write bucket', () => {
  test('sum() accumulates cache writes as their own bucket, beside input and cached', () => {
    const t = sum([
      bucket(1, { turns: 1, in_tokens: 60_000, cached_tokens: 40_000, cache_write_tokens: 12_000 }),
      bucket(2, { turns: 1, in_tokens: 30_000, cached_tokens: 0, cache_write_tokens: 30_000 }),
    ]);
    expect(t.cacheWriteTokens).toBe(42_000);
    expect(t.inTokens).toBe(90_000); // still the METERED total, both cache buckets inside it
    expect(t.cachedTokens).toBe(40_000); // the read half is untouched by the write half
  });

  /** Computed exactly as hitRate is, over the same denominator: the two are halves of one total
   * and must be comparable at a glance in the ledger's adjacent columns. */
  test('writeRate is the cache-write share of TOTAL input, like hitRate is for the read half', () => {
    const t = sum([bucket(1, {
      turns: 1, in_tokens: 100_000, cached_tokens: 60_000, cache_write_tokens: 25_000,
    })]);
    expect(writeRate(t)).toBeCloseTo(0.25, 5);
    expect(hitRate(t)).toBeCloseTo(0.6, 5);
    // NOT the share of the non-cached remainder (25k/40k = 0.625), and NOT the share of the two
    // cache buckets together (25k/85k). The denominator is the billed total, same as the hit rate.
    expect(writeRate(t)).not.toBeCloseTo(0.625, 3);
  });

  test('a dialect that reports no cache-creation bucket reads 0, not null', () => {
    const t = sum([bucket(1, { turns: 1, in_tokens: 1_000, cached_tokens: 200 })]);
    expect(t.cacheWriteTokens).toBe(0);
    expect(writeRate(t)).toBe(0); // "this head wrote no cache" is a finding, and it is not absent
  });

  test('no input at all yields null rather than NaN or a confident zero', () => {
    expect(writeRate(sum([]))).toBeNull();
  });

  /** The burn gauge must not move. Cache writes were ALREADY inside in_tokens (the daemon keeps
   * inputTokens inclusive of both cache buckets), so surfacing them separately changes what the
   * page can SAY and not one digit of what it meters. */
  test('surfacing the write bucket does not change the burn gauge', () => {
    const plain = burn(head([bucket(1, { turns: 1, in_tokens: 1_000_000 })], 2_000_000), NOW);
    const written = burn(
      head([bucket(1, { turns: 1, in_tokens: 1_000_000, cache_write_tokens: 400_000 })], 2_000_000),
      NOW,
    );
    expect(written.spent).toBe(plain.spent);
    expect(written.fraction).toBe(plain.fraction);
    expect(written.ratePerHour).toBe(plain.ratePerHour);
  });
});

describe('the ceiling is never invented', () => {
  test('no provider ceiling means no fraction and no projection', () => {
    const b = burn(head([bucket(1, { turns: 5, in_tokens: 500_000 })], null), NOW);
    expect(b.ceiling).toBeNull();
    expect(b.fraction).toBeNull();
    expect(b.hoursToExhaustion).toBeNull();
    expect(b.exhaustsAt).toBeNull();
    expect(b.spent).toBe(500_000); // the measured figure still shows
  });

  test('an idle head with a ceiling never exhausts', () => {
    const b = burn(head([bucket(100, { in_tokens: 10 })], 1e9), NOW);
    expect(b.ratePerHour).toBe(0);
    expect(b.hoursToExhaustion).toBe(Infinity);
    expect(b.exhaustsAt).toBeNull(); // not projectable, so no fabricated wall-clock time
  });
});

describe('projection tracks the RAMP, not the week average', () => {
  /** The failure being instrumented doubled turn count and drained a week in twelve hours. A
   *  week-averaged rate lags that ramp by days and projects safety throughout. */
  test('a recent burst dominates a quiet week', () => {
    const quiet = Array.from({ length: 160 }, (_, i) => bucket(i + 8, { turns: 1, in_tokens: 1_000 }));
    const burst = Array.from({ length: 6 }, (_, i) => bucket(i, { turns: 100, in_tokens: 10_000_000 }));
    const b = burn(head([...quiet, ...burst], 1_000_000_000), NOW);
    expect(b.ratePerHour).toBe(10_000_000); // 60M over the trailing 6h
    expect(b.hoursToExhaustion).toBeLessThan(168); // alarms
    expect(b.exhaustsAt).toBeGreaterThan(NOW);
  });

  test('going idle relaxes the projection instead of freezing it at the last busy hour', () => {
    const busyThenIdle = [bucket(20, { turns: 100, in_tokens: 60_000_000 })];
    const b = burn(head(busyThenIdle, 1e9), NOW);
    expect(b.ratePerHour).toBe(0); // the trailing 6h are empty, counted as zero rather than skipped
  });
});

describe('absence is a finding, not a zero', () => {
  test('a dialect that cannot defer yields null, never 0/0 tools', () => {
    const t = sum([bucket(1, { turns: 10, tools_eager: 0, tools_deferred: 0, deferral_turns: 0 })]);
    expect(toolSurface(t)).toBeNull();
  });

  test('a deferring head averages over the turns that actually reported a partition', () => {
    const t = sum([
      bucket(1, { turns: 2, tools_eager: 28, tools_deferred: 48, deferral_turns: 2 }),
      bucket(2, { turns: 1, tools_eager: 0, tools_deferred: 0, deferral_turns: 0 }), // a non-deferring turn
    ]);
    const s = toolSurface(t);
    expect(s).not.toBeNull();
    expect(s?.eager).toBe(14); // 28 over the 2 reporting turns, undiluted by the third
    expect(s?.total).toBe(38);
  });

  test('empty totals yield null rates rather than NaN or zero', () => {
    const t = sum([]);
    expect(hitRate(t)).toBeNull();
    expect(amplification(t)).toBeNull();
    expect(perTurn(t)).toBeNull();
    expect(wireDelta(t)).toBeNull();
  });
});

describe('derived rates', () => {
  const t = sum([bucket(1, {
    turns: 4, in_tokens: 400_000, cached_tokens: 360_000, out_tokens: 1_000,
    req_bytes: 400_000, upstream_req_bytes: 440_000,
  })]);

  test('hit rate, amplification and per-turn read off the sums', () => {
    expect(hitRate(t)).toBeCloseTo(0.9, 5);
    expect(amplification(t)).toBe(400); // 400k input per 1k output
    expect(perTurn(t)).toBe(100_000);
  });

  test('wire delta is signed: positive means splice ADDS bytes upstream', () => {
    expect(wireDelta(t)).toBe(10_000); // +40k over 4 turns
    const cut = sum([bucket(1, { turns: 2, req_bytes: 200_000, upstream_req_bytes: 180_000 })]);
    expect(wireDelta(cut)).toBe(-10_000);
  });
});

describe('windowing', () => {
  test('within() excludes buckets older than the window', () => {
    const all = [bucket(1), bucket(10), bucket(200)];
    expect(within(all, 168, NOW)).toHaveLength(2);
    expect(within(all, 6, NOW)).toHaveLength(1);
  });

  test('hourly() reports idle hours as gaps, not by closing them up', () => {
    const series = hourly([bucket(0, { in_tokens: 5 }), bucket(3, { in_tokens: 9 })], 4, NOW);
    expect(series).toEqual([9, 0, 0, 5]); // oldest first: 3h ago, two idle hours, then now
  });
});
