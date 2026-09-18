// USAGE, COMPACTION AND MODELS (row M2-06). The things worth proving here are the ones that would
// quietly change a decision: what a dollar figure is actually made of, that a window draws every
// hour it claims, and that a route the daemon has not built yet reads as a named empty rather than
// as an empty catalog.
//
// CONTRACTS.md section 4: a .ts test holds no JSX (TS1161), so elements are built with
// createElement and asserted on the markup react-dom/server returns; and a BOARD takes its payload
// as a prop, because a static render only ever sees a zustand store's initial state.
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';

import { costOf, sum, within } from '../src/entities/economics';
import { slotTiers } from '../src/entities/model';
import type { HeadCatalog } from '../src/entities/model';
import { CompactFeed, edgeFor } from '../src/widgets/compact-feed';
import { byteRows, peakMax, peakOf, tokenRows, totalOf, toolRows, WINDOWS, windowHours } from '../src/widgets/scope-chart';
import { CompactionBoard } from '../src/pages/compaction';
import { fixtureCompact } from '../src/pages/compaction/fixtures/compaction';
import { ModelsBoard } from '../src/pages/models';
import { byProvider, findModel, PROVIDER_UNKNOWN } from '../src/pages/models/model';
import { fixtureCatalog } from '../src/pages/models/fixtures/models';
import { UsageBoard } from '../src/pages/usage';
import { fixtureEconomics, fixtureModels, FIXTURE_NOW } from '../src/pages/usage/fixtures/usage';
import { ratesFor, sortedHeads } from '../src/pages/usage/model';

/** The first element, or a named failure: the strict preset forbids a non-null assertion, and a
 *  test that silently read `undefined` would assert nothing at all. */
function first<T>(items: readonly T[]): T {
  const value = items[0];
  if (value === undefined) throw new Error('expected at least one element');
  return value;
}

const h = createElement;
const render = (element: Parameters<typeof renderToStaticMarkup>[0]): string => renderToStaticMarkup(element);

const HOUR_MS = 3_600_000;
const NOW = 1_787_400_000_000;

describe('economics: what a dollar figure is made of', () => {
  const rates = { input: 1.25, cache_read: 0.125, cache_write: 1.5, output: 10 };
  const totals = { ...sum([]), inTokens: 1_000_000, cachedTokens: 800_000, cacheWriteTokens: 100_000, outTokens: 50_000 };

  test('fresh input is the total minus both cache halves, and each half is priced by its own rate', () => {
    // 100k fresh at 1.25 + 800k read at 0.125 + 100k written at 1.5 + 50k out at 10, per million.
    expect(costOf(totals, rates)).toBeCloseTo(0.875, 10);
  });

  test('billing the total at the input rate is the double-charge this function exists to prevent', () => {
    // The naive version — in_tokens at the input rate, ON TOP of the two cache halves — reads 2.00
    // against this function's 0.875, i.e. 2.3x the real bill, at the most expensive rate in the card.
    const naive = (1_000_000 * rates.input + 800_000 * rates.cache_read + 100_000 * 1.5 + 50_000 * rates.output) / 1_000_000;
    expect(naive).toBeCloseTo(2, 10);
    expect(costOf(totals, rates)).toBeLessThan(naive);
  });

  test('a cache write with no declared write rate bills at the input rate, never at zero', () => {
    const noWrite = { ...totals, cacheWriteTokens: 100_000 };
    const withRate = costOf(noWrite, rates);
    const withoutRate = costOf(noWrite, { input: 1.25, cache_read: 0.125, output: 10 });
    expect(withRate).toBeCloseTo(0.875, 10);
    expect(withoutRate).toBeCloseTo(0.85, 10);
    expect(withoutRate).toBeGreaterThan(0);
  });

  test('a bucket that predates the cache-write field cannot bill negative fresh tokens', () => {
    const legacy = { ...sum([]), inTokens: 100, cachedTokens: 100, cacheWriteTokens: 0, outTokens: 0 };
    expect(costOf(legacy, rates)).toBeCloseTo((100 * 0.125) / 1_000_000, 12);
  });
});

describe('chart mapping', () => {
  const bucket = (
    hour: number, inTokens: number, cached: number, write: number,
    reqBytes = 100, upstreamBytes = 80, tools = 1,
  ) => ({
    hour, turns: 1, in_tokens: inTokens, cached_tokens: cached, cache_write_tokens: write,
    out_tokens: 10, req_bytes: reqBytes, upstream_req_bytes: upstreamBytes,
    tools_eager: tools, tools_deferred: tools * 2,
    deferral_turns: 1, rate_limited: 0,
  });
  const end = Math.floor(NOW / HOUR_MS) * HOUR_MS;
  // The middle hour is deliberately missing: an idle hour must draw as a gap.
  const buckets = [
    bucket(end - 2 * HOUR_MS, 1000, 700, 100, 900, 800, 3),
    bucket(end, 500, 400, 0, 100, 80, 1),
  ];

  test('one row per hour of the window, oldest first, idle hours zero-filled', () => {
    const rows = tokenRows(buckets, 3, NOW);
    expect(rows.map((row) => row.at)).toEqual([end - 2 * HOUR_MS, end - HOUR_MS, end]);
    expect(rows[1]?.values).toEqual({ fresh: 0, cached: 0, write: 0, out: 0 });
  });

  test('the fresh segment subtracts both cache halves, so the stack never exceeds the total', () => {
    const rows = tokenRows(buckets, 3, NOW);
    expect(rows[0]?.values.fresh).toBe(200);
    expect(rows[2]?.values.fresh).toBe(100);
    expect(totalOf(rows, 'fresh') + totalOf(rows, 'cached') + totalOf(rows, 'write')).toBe(1500);
    expect(totalOf(rows, 'cached')).toBe(1100);
  });

  test('an all-zero window still has a scale to draw against', () => {
    expect(peakOf(tokenRows([], 24, NOW), ['fresh', 'cached', 'write'])).toBe(1);
    expect(peakOf(tokenRows(buckets, 3, NOW), ['fresh', 'cached', 'write'])).toBe(1000);
  });

  test('a stack scales to its total, a pair of readings scales to the larger reading', () => {
    // The stack's scale is the whole bar (1000 total), not its biggest segment.
    expect(peakOf(tokenRows(buckets, 3, NOW), ['fresh', 'cached', 'write'])).toBe(1000);
    // Request and upstream are two readings of the SAME traffic, so the scale is the taller one.
    // The hours are 900/800 and 100/80: the wrong stacked scale would have been 1700 and would have
    // drawn a volume neither number ever was.
    expect(peakMax(byteRows(buckets, 3, NOW), ['request', 'upstream'])).toBe(900);
    expect(peakOf(byteRows(buckets, 3, NOW), ['request', 'upstream'])).toBe(1700);
  });

  test('bytes and tools map to their own series', () => {
    // Oldest first, and the idle hour in the middle reports nothing rather than repeating a
    // neighbour: a gap in the bytes lane is the honest drawing of an hour with no turns.
    const bytes = byteRows(buckets, 3, NOW);
    expect(bytes[0]?.values).toEqual({ request: 900, upstream: 800 });
    expect(bytes[1]?.values).toEqual({ request: 0, upstream: 0 });
    expect(bytes[2]?.values).toEqual({ request: 100, upstream: 80 });
    const tools = toolRows(buckets, 3, NOW);
    expect(tools[0]?.values).toEqual({ eager: 3, deferred: 6 });
    expect(tools[2]?.values).toEqual({ eager: 1, deferred: 2 });
  });

  test('the three windows are the ones FEATURES asks for, and their labels come from the table', () => {
    expect(WINDOWS.map((entry) => entry.id)).toEqual(['1h', '24h', '7d']);
    expect(WINDOWS.map((entry) => entry.hours)).toEqual([1, 24, 168]);
    expect(WINDOWS.every((entry) => entry.label.split(/\s+/).length <= 3)).toBe(true);
  });
});

describe('usage page', () => {
  test('the rate card that prices a head is its pinned model, and nothing while the route is pending', () => {
    expect(ratesFor(fixtureModels, 'claudex')?.input).toBe(1.25);
    expect(ratesFor(fixtureModels, 'claude-deepseek')?.cache_write).toBeUndefined();
    expect(ratesFor(fixtureModels, 'claude-kimi')).toBeNull();
    expect(ratesFor({ pending: 'V4-127' }, 'claudex')).toBeNull();
    expect(ratesFor(null, 'claudex')).toBeNull();
  });

  test('heads are racked in key order', () => {
    expect(sortedHeads(fixtureEconomics.heads).map((head) => head.key)).toEqual([
      'claude-deepseek', 'claude-splice', 'claudex',
    ]);
  });

  test('the board draws the window tabs, the head rack and the opened head charts', () => {
    const markup = render(h(UsageBoard, { payload: fixtureEconomics, catalog: fixtureModels, now: FIXTURE_NOW }));
    for (const entry of WINDOWS) expect(markup).toContain(entry.label);
    for (const head of fixtureEconomics.heads) expect(markup).toContain(head.label);
    // The charts are scope insets and every one of them prints its basis.
    expect(markup).toContain('myx-scope')
    expect(markup).toContain('measured');
    expect(markup).toContain('estimated');
    // Cost is priced because the catalog carries the pinned model rates.
    expect(markup).not.toContain('no rates declared');
  });

  test('with no catalog there is no dollar figure, and the inset says so rather than printing zero', () => {
    const markup = render(h(UsageBoard, { payload: fixtureEconomics, catalog: { pending: 'V4-127' }, now: FIXTURE_NOW }));
    expect(markup).toContain('no rates declared');
    expect(markup).toContain('unavailable');
  });
});

describe('compaction page', () => {
  test('an outcome earns its edge from the daemon name, and an unknown one is not a success', () => {
    expect(edgeFor('model_summary')).toBe('green');
    expect(edgeFor('empty_model')).toBe('red');
    expect(edgeFor('stream_error')).toBe('red');
    expect(edgeFor('truncated')).toBe('amber');
    expect(edgeFor('something_new')).toBe('amber');
  });

  test('the feed draws a strip per outcome and a strip per event', () => {
    const markup = render(h(CompactFeed, { payload: fixtureCompact }));
    for (const outcome of Object.keys(fixtureCompact.stats.by_outcome)) expect(markup).toContain(outcome);
    expect(markup).toContain(String(fixtureCompact.stats.total));
    expect(markup).toContain('myx-strip');
  });

  test('the page states the law that compaction runs on the session own model, behind a reveal', () => {
    // Behind a Reveal since m1 design review B16: the brief allows a paragraph on a page only as
    // an honest empty or a Doctor fix, so the sentence is one click away and is not in the DOM
    // until it is asked for (the primitive's own contract).
    const markup = render(h(CompactionBoard, { payload: fixtureCompact }));
    expect(markup).toContain('why no model');
    expect(markup).not.toContain('own model and effort by law');
  });
});

describe('models page', () => {
  test('a pending catalog names the row that will serve it, and nothing else is drawn', () => {
    const markup = render(h(ModelsBoard, { catalog: { pending: 'V4-127' } }));
    expect(markup).toContain('V4-127 serves /api/models');
    expect(markup).not.toContain('myx-strip');
  });

  test('with a catalog the board draws a strip per tier, and an undeclared tier is a struck one', () => {
    const markup = render(h(ModelsBoard, { catalog: fixtureCatalog }));
    for (const head of fixtureCatalog.heads) expect(markup).toContain(head.key);
    expect(markup).toContain('gpt-5.6-sol');
    // The struck tier's edge prints its state and its model cell prints the absence glyph: the
    // holder edge carries a state inside the contract's 6ch budget and the rack prints its column
    // names once on the bay head, so `not declared` is now `vacant` (CONTRACTS.md section 2, m1
    // design review B9/B10) with `n/r` where a model would be.
    expect(markup).toContain('vacant');
    expect(markup).toContain('n/r');
    expect(markup.split('myx-strip-struck').length - 1).toBeGreaterThan(0);
  });

  test('the tiers come from the daemon vocabulary, and an unfilled tier is a row and not a gap', () => {
    const tiers = slotTiers(first(fixtureCatalog.heads));
    expect(tiers.map((tier) => tier.slot)).toEqual(['opus', 'sonnet', 'haiku', 'fable']);
    expect(tiers[3]?.model).toBeNull();
    expect(tiers[0]?.model?.pinned).toBe(true);
  });

  test('the by-provider view groups on the reported provider and never guesses one', () => {
    const groups = byProvider(fixtureCatalog.heads);
    expect(groups.map((group) => group.provider)).toEqual(['api-key', 'chatgpt-oauth', 'kimi-oauth']);
    // Rebuilt field by field rather than spread with `provider: undefined`: under
    // exactOptionalPropertyTypes an explicit undefined is not the same as an absent key, and "the
    // payload does not carry this field" is exactly the case this view has to survive.
    const head = first(fixtureCatalog.heads);
    const withoutProvider: HeadCatalog = {
      key: head.key, label: head.label, discovery_prefix: head.discovery_prefix,
      pinned_model: head.pinned_model, context_window: head.context_window,
      default_context_window: head.default_context_window, models: head.models,
      extra_windows: head.extra_windows, window_rules: head.window_rules,
    };
    expect(byProvider([withoutProvider])[0]?.provider).toBe(PROVIDER_UNKNOWN);
  });

  test('the opened model is found anywhere in the catalog, and a pending payload finds nothing', () => {
    expect(findModel(fixtureCatalog, 'kimi-k2.5')?.head.key).toBe('claude-kimi');
    expect(findModel(fixtureCatalog, 'nope')).toBeNull();
    expect(findModel({ pending: 'V4-127' }, 'kimi-k2.5')).toBeNull();
  });
});

describe('economics windows', () => {
  test('a window is exactly its hours, so the 1h view is one bucket', () => {
    // `windowHours` is what the bars and the totals BOTH walk, so a number under a chart can never
    // describe a different window than the chart. `within` (the daemon-side helper) is inclusive at
    // its trailing edge and returns two buckets for an exactly-on-the-hour `now`, which is why the
    // charts do not use it to decide the window's shape.
    expect(windowHours(1, FIXTURE_NOW)).toHaveLength(1);
    expect(windowHours(24, FIXTURE_NOW)).toHaveLength(24);
    expect(windowHours(168, FIXTURE_NOW)).toHaveLength(168);
    expect(within(first(fixtureEconomics.heads).buckets, 168, FIXTURE_NOW)).toHaveLength(168);
    expect(sum(within(first(fixtureEconomics.heads).buckets, 168, FIXTURE_NOW)).inTokens).toBeGreaterThan(0);
  });
});
