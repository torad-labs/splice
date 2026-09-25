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
import { slotTiers, windowSourceText } from '../src/entities/model';
import type { HeadCatalog } from '../src/entities/model';
import { byteRows, peakMax, peakOf, tokenRows, totalOf, toolRows, WINDOWS, windowHours } from '../src/widgets/scope-chart';
import { ModelsBoard } from '../src/pages/models';
import { byProvider, findModel, headWindows, PROVIDER_UNKNOWN } from '../src/pages/models/model';
import { fixtureCatalog } from '../src/pages/models/fixtures/models';
import { UsageBoard } from '../src/pages/usage';
import { fixtureEconomics, fixtureModels, FIXTURE_NOW } from '../src/pages/usage/fixtures/usage';
import { ratesFor, sortedHeads } from '../src/pages/usage/model';
import { PlanBay, planRows, readText, windowCells, windowTone } from '../src/pages/usage/plan';
import type { UsagePayload } from '../src/shared/api';

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
    expect(markup).not.toContain('no prices set');
  });

  test('with no catalog there is no dollar figure, and the inset says so rather than printing zero', () => {
    const markup = render(h(UsageBoard, { payload: fixtureEconomics, catalog: { pending: 'V4-127' }, now: FIXTURE_NOW }));
    expect(markup).toContain('no prices set');
    expect(markup).toContain('unavailable');
  });
});

describe('plan limits', () => {
  test('a failed usage read leaves no skeleton, and a sample renders no plan rack', () => {
    expect(render(h(PlanBay, { usage: null, now: 0 }))).toContain('myx-blank');
    expect(render(h(PlanBay, { usage: null, error: 'splice is not answering', now: 0 }))).toBe('');
    // behind a sample the rack is absent, not a skeleton waiting on a read that never comes
    expect(render(h(UsageBoard, { payload: fixtureEconomics, catalog: fixtureModels, now: FIXTURE_NOW, sample: 'usage' }))).not.toContain('myx-blank');
  });


  // The live shape of /api/usage on 2026-09-24, with times moved to NOW: `warn` said `none` for
  // every one of these heads while the plan windows carried the figures.
  const nowS = NOW / 1000;
  const quiet = { level: 'ok' as const, pct: 0, source: 'none', reset: null };
  const usage: UsagePayload = {
    window_hours: 5,
    warn_pct: 80,
    warn_tokens_5h: 0,
    heads: [
      { key: 'claude-splice', label: 'claude-splice', usage: { output_tokens_5h: 0, entries: 0, ratelimit: null, warn: quiet,
        quota: { five_hour: { used_pct: 65, resets_at: nowS + 3600, observed_at: nowS - 120 }, seven_day: { used_pct: 15, resets_at: nowS + 5 * 86400 } } } },
      { key: 'claude-muse', label: 'claude-muse', usage: { output_tokens_5h: 0, entries: 0, ratelimit: null, warn: quiet,
        quota: { five_hour: { used_pct: 0, resets_at: nowS - 6 * 86400 }, seven_day: { used_pct: 99, resets_at: nowS - 3 * 86400, observed_at: nowS - 4 * 86400 } } } },
      { key: 'claudex', label: 'claudex', usage: { output_tokens_5h: 0, entries: 0, ratelimit: null, warn: quiet,
        quota: { plan: 'pro', seven_day: { used_pct: 50, resets_at: nowS + 2.5 * 86400 } } } },
      { key: 'bonsai', label: 'bonsai', usage: { output_tokens_5h: 9, entries: 3, ratelimit: null, warn: quiet } },
    ],
  };

  test('only the heads that track a plan window get a row, fullest live window first', () => {
    expect(planRows(usage, NOW).map((row) => row.entry.key)).toEqual(['claude-splice', 'claudex', 'claude-muse']);
  });

  test('a window whose reset passed prints as reset, never as the figure from before it', () => {
    const muse = planRows(usage, NOW).find((row) => row.entry.key === 'claude-muse');
    expect(muse?.live).toBeNull();
    expect(windowCells(muse?.windows[1], NOW)).toEqual({ used: 'unknown', resets: 'already reset' });
    // the card prints the reset in words and draws an empty meter, never the 99% from before it
    const card = render(h(PlanBay, { usage: { ...usage, heads: usage.heads.filter((head) => head.key === 'claude-muse') }, now: NOW }));
    expect(card).toContain('already reset');
    expect(card).not.toContain('99%');
    expect(card).toContain('myx-plan-neutral');
  });

  test('a live window prints its figure and how long until it resets', () => {
    const splice = first(planRows(usage, NOW));
    expect(windowCells(splice.windows[0], NOW)).toEqual({ used: '65%', resets: 'in 1h 0m' });
    expect(windowCells(splice.windows[1], NOW)).toEqual({ used: '15%', resets: 'in 5d 0h' });
    expect(windowTone(65, 80)).toBe('neutral');
    expect(render(h(PlanBay, { usage, now: NOW }))).toContain('>65%<');
    expect(readText(splice.windows, NOW)).toBe('2m ago');
  });

  test('a window the head does not track, and a reading with no time, print the absence mark', () => {
    const claudex = planRows(usage, NOW).find((row) => row.entry.key === 'claudex');
    expect(windowCells(claudex?.windows.find((window) => window.window === '5h'), NOW)).toEqual({ used: '–', resets: '–' });
    expect(readText(claudex?.windows ?? [], NOW)).toBe('–');
  });

  test('the board draws the plan rack above the heads rack, with the plan name', () => {
    const markup = render(h(UsageBoard, { payload: fixtureEconomics, usage, catalog: fixtureModels, now: NOW }));
    expect(markup).toContain('plan limits');
    expect(markup).toContain('already reset');
    expect(markup).toContain('>pro<');
    expect(markup.indexOf('plan limits')).toBeLessThan(markup.indexOf('tokens used'));
  });

  test('no head with a plan window is a named empty, not a blank rack', () => {
    const none: UsagePayload = { ...usage, heads: usage.heads.filter((row) => row.key === 'bonsai') };
    expect(render(h(UsageBoard, { payload: fixtureEconomics, usage: none, catalog: fixtureModels, now: NOW }))).toContain('no head reports plan limits');
  });
});

describe('models page', () => {
  test('a catalog this daemon does not serve says so without a row id, and nothing else is drawn', () => {
    const markup = render(h(ModelsBoard, { catalog: { pending: 'V4-127' } }));
    expect(markup).toContain('catalog unavailable');
    expect(markup).not.toContain('V4-127');
    expect(markup).not.toContain('myx-strip');
  });

  test('the window source reads as words, whatever label the daemon sends', () => {
    // ModelsRoute.kt WINDOW_FROM_*, plus `head` from splice-lead's S15 change.
    expect(['model', 'head', 'rule', 'extra-window', 'default', 'unknown'].map(windowSourceText)).toEqual([
      'model catalog', 'head setting', 'prefix rule', 'extra window', 'provider default', 'unknown',
    ]);
    expect(windowSourceText('some-new-label')).toBe('some new label');
    expect(render(h(ModelsBoard, { catalog: fixtureCatalog }))).not.toContain('extra-window');
  });

  test('with a catalog the board draws a strip per filled tier, and names the tier no model fills', () => {
    const markup = render(h(ModelsBoard, { catalog: fixtureCatalog }));
    for (const head of fixtureCatalog.heads) expect(markup).toContain(head.head);
    expect(markup).toContain('gpt-5.6-sol');
    // The vacant tier is named, never dropped, and nothing is struck: a strike is the verdict on an
    // excluded or disabled row.
    expect(markup).toContain('no model fills the fable tier');
    expect(markup.split('myx-strip-struck').length - 1).toBe(0);
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
    // An empty provider is grouped under the honest label, never under the head key.
    const withoutProvider: HeadCatalog = { ...first(fixtureCatalog.heads), provider: '' };
    expect(byProvider([withoutProvider])[0]?.provider).toBe(PROVIDER_UNKNOWN);
  });

  test('a head\'s windows come from its topology, joined on the head and provider keys', () => {
    const head = first(fixtureCatalog.heads);
    const topology = {
      heads: { [head.head]: { provider: head.provider, context_window: 400_000 } },
      providers: { [head.provider]: { default_context_window: 200_000, extra_windows: [{ id: 'x', context_window: 1 }], window_rules: [] } },
    };
    expect(headWindows(topology, head)).toEqual({ headWindow: 400_000, defaultWindow: 200_000, extraWindows: 1, windowRules: 0 });
    // A window the topology does not set is an absence, never the daemon's internal zero.
    const bare = { heads: { [head.head]: {} }, providers: { [head.provider]: { default_context_window: 0 } } };
    expect(headWindows(bare, head)).toEqual({ headWindow: null, defaultWindow: null, extraWindows: 0, windowRules: 0 });
    expect(headWindows(null, head)).toBeNull();
    expect(headWindows({ heads: {}, providers: {} }, head)).toBeNull();
  });

  test('the opened model is found under the head it was opened on, and a pending payload finds nothing', () => {
    expect(findModel(fixtureCatalog, { head: 'claude-kimi', id: 'kimi-k2.5' })?.head.head).toBe('claude-kimi');
    expect(findModel(fixtureCatalog, { head: 'claude-kimi', id: 'nope' })).toBeNull();
    expect(findModel(fixtureCatalog, { head: 'no-such-head', id: 'kimi-k2.5' })).toBeNull();
    expect(findModel({ pending: 'V4-127' }, { head: 'claude-kimi', id: 'kimi-k2.5' })).toBeNull();
  });

  test('two heads serving one model id each open their own, so the detail reads the right head windows', () => {
    const kimi = fixtureCatalog.heads.find((head) => head.head === 'claude-kimi');
    if (kimi === undefined) throw new Error('the fixture catalog lost claude-kimi');
    const twin = { ...kimi, head: 'claude-kimi-twin' };
    const catalog = { heads: [kimi, twin] };
    expect(findModel(catalog, { head: 'claude-kimi-twin', id: 'kimi-k2.5' })?.head.head).toBe('claude-kimi-twin');
    expect(findModel(catalog, { head: 'claude-kimi', id: 'kimi-k2.5' })?.head.head).toBe('claude-kimi');
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

// ---------------------------------------------------------------------------------------------
// M2-32: A RACK OF LIKE ROWS PRINTS ITS COLUMN NAMES ONCE.
//
// m1 design review B9 says a bay whose rows share one shape prints the names on the RACK and not
// on every slip, and `Bay` has shipped the `fields` row for it since m1 -- yet usage's heads bay,
// usage's model bays and both of compaction's bays printed a label on every cell. Nothing in this
// file could have caught it: 38 tests were green while three racks repeated their column names on
// every row, which is what a wall is for.
//
// MEASURED at 1536 dark before the change: every strip on both pages stood 63.8px and 42px of it
// was the values, so 21.8px of every row -- a third of it -- went on reprinting the names. Sixteen
// rows across the two pages; the bays came down 332->285, 715->581 and 620->507px.
//
// THE ASSERTION IS THE ONE THAT CAN DRIFT. Removing the labels is visible in a capture; the names
// silently sliding off their columns is not. `strip-field.tsx` sets flexGrow to each cell's OWN ch
// (M1-73), so a name row fixed at `w ch` walks away from the column under it -- further the more
// slack the rack has, and compaction's outcomes rack renders 26ch as 847px. So this compares the
// RENDERED ch of the name against the RENDERED ch of every cell beneath it, column by column, and
// a bay that prints names at all must print no labels in its rows.
interface Rack { label: string; names: { w: number; text: string }[]; rows: number[][]; labels: number }

/** Every bay in a rendered board, as the three things B9 is about: the names row, the cell widths
 *  under it, and how many per-cell labels survive. A span cell states one value across several
 *  tracks and is excluded BY DECLARATION (M1-73), never by happening not to look. */
export function racks(markup: string): Rack[] {
  // a bay's class list may carry a modifier (a compact rack is `myx-bay myx-bay-compact`)
  return markup.split(/<section class="myx-bay[ "]/).slice(1).map((part) => {
    const head = /<span class="myx-bay-label">([^<]*)</.exec(part);
    const fields = /<div class="myx-bay-fields">([\s\S]*?)<\/div><div class="myx-bay-rows">/.exec(part);
    const rowsAt = part.indexOf('<div class="myx-bay-rows">');
    // BOUNDED AT THE BAY'S OWN CLOSE, and the first cut was not: usage's heads bay is the last
    // section in the board, so a region running to the end of the markup swept in the nine 14ch
    // StripFields of the scope-chart legends below it and reported a rack of seventeen columns.
    // A measurement that reads past its object is this campaign's most frequent defect and the
    // reason M2-30 counted a logs line against the wrong box.
    const end = part.indexOf('</section>');
    const region = rowsAt < 0 ? '' : part.slice(rowsAt, end < 0 ? undefined : end);
    const rows = region.split('<div class="myx-strip"').slice(1)
      .filter((strip) => !strip.includes('data-span'))
      .map((strip) => [...strip.matchAll(/class="myx-sfield" style="width:(\d+)ch/g)].map((m) => Number(m[1])));
    return {
      label: head === null ? '?' : head[1],
      names: fields === null ? [] : [...fields[1].matchAll(/width:(\d+)ch[^>]*>([^<]*)</g)]
        .map((m) => ({ w: Number(m[1]), text: m[2] })),
      rows,
      labels: (region.match(/myx-sfield-label/g) ?? []).length,
    };
  });
}

describe('a rack of like rows prints its column names once', () => {
  // THE GAP, DECLARED RATHER THAN LEFT SILENT (§24: every item gets a disposition). Usage's third
  // rack -- the model bays of the `by model` view -- is NOT covered here, because reaching it means
  // `useViews` reading a stored view out of localStorage and a node render only ever sees the
  // default. It was measured in the browser instead, at 1536 dark with the tab clicked: two bays,
  // six names each printed once, zero per-cell labels, strips 42px where they stood 63.8px. The
  // instrument is .impeccable/review/compose/compose.mjs and the capture is beside it.
  test('usage\'s heads table prints its column names once, in its head row, and in no cell', () => {
    const markup = render(h(UsageBoard, { payload: fixtureEconomics, catalog: fixtureModels, now: FIXTURE_NOW }));
    const table = /<table[^>]*aria-label="heads"[^>]*>([\s\S]*?)<\/table>/.exec(markup);
    expect(table).not.toBeNull();
    const [head, body] = (table?.[1] ?? '').split('</thead>');
    const names = [...(head ?? '').matchAll(/<th scope="col"[^>]*>([^<]*)</g)].map((m) => m[1]);
    expect(names).toEqual(['head', 'share', 'turns', 'in tokens', 'out tokens', 'tokens used', 'limit', 'runs out in', 'rate limited']);
    const rows = (body ?? '').split('<tr').slice(1);
    expect(rows.length).toBeGreaterThan(0); // the denominator: a table with no rows would pass vacuously
    for (const row of rows) {
      expect(row).not.toContain('myx-sfield-label');
      expect([...row.matchAll(/<td/g)].length).toBe(names.length);
    }
  });

  test('the wall can fail: a bay that prints names AND labels is reported by name', () => {
    // The planted violation is the state this file was green in an hour ago -- a names row over
    // rows that still carry their own labels -- because a wall nobody has seen go red on the very
    // defect it was written for is not yet a wall.
    const planted = '<section class="myx-bay"><header class="myx-bay-head"><span class="myx-bay-label">planted</span>'
      + '</header><div class="myx-bay-fields"><span style="width:9ch;flex-grow:9">turns</span></div>'
      + '<div class="myx-bay-rows"><div class="myx-strip"><div class="myx-sfield" style="width:9ch">'
      + '<span class="myx-sfield-label">turns</span></div></div></div></section>';
    const rack = first(racks(planted));
    expect(rack.label).toBe('planted');
    expect(rack.labels).toBe(1);
  });
});
