// USAGE AND ITS WINDOWS (row M2-06). The things worth proving here are the ones that would
// quietly change a decision: that a dollar figure is the daemon's and a turn it could not price is
// counted rather than priced at zero, that a window draws every hour it claims, and that a route the
// daemon has not built yet reads as a named empty rather than as an empty catalog. How one turn is
// priced is the daemon's (TurnPrice, EconomicsStoreTest); this page multiplies nothing.
//
// CONTRACTS.md section 4: a .ts test holds no JSX (TS1161), so elements are built with
// createElement and asserted on the markup react-dom/server returns; and a BOARD takes its payload
// as a prop, because a static render only ever sees a zustand store's initial state.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';

import { costOf, sum, within } from '../src/entities/economics';
import { CostChart } from '../src/widgets/scope-chart';
import { byteRows, peakMax, peakOf, tokenRows, totalOf, toolRows, WINDOWS, windowHours } from '../src/widgets/scope-chart';
import { UsageBoard } from '../src/pages/usage';
import { fixtureEconomics, fixtureModels, FIXTURE_NOW } from '../src/pages/usage/fixtures/usage';
import { fmtUsd, perHour, sortedHeads } from '../src/pages/usage/model';
import { NOT_REREAD } from '../src/entities/account';
import type { AccountRow } from '../src/entities/account';
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

describe('chart mapping', () => {
  const bucket = (
    hour: number, inTokens: number, cached: number, write: number,
    reqBytes = 100, upstreamBytes = 80, tools = 1,
  ) => ({
    hour, turns: 1, in_tokens: inTokens, cached_tokens: cached, cache_write_tokens: write,
    out_tokens: 10, req_bytes: reqBytes, upstream_req_bytes: upstreamBytes,
    tools_eager: tools, tools_deferred: tools * 2,
    deferral_turns: 1, rate_limited: 0, cost_usd: 0, unpriced_turns: 0,
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
  test('heads are racked in key order', () => {
    expect(sortedHeads(fixtureEconomics.heads).map((head) => head.key)).toEqual([
      'claude-deepseek', 'claude-splice', 'claudex',
    ]);
  });

  test('the board draws the window tabs, the head rack and the opened head charts', () => {
    const markup = render(h(UsageBoard, { payload: fixtureEconomics, catalog: fixtureModels, now: FIXTURE_NOW }));
    for (const entry of WINDOWS) expect(markup).toContain(entry.label);
    for (const head of fixtureEconomics.heads) expect(markup).toContain(head.label);
    // The charts are scope insets. A measured one says nothing of its basis; the estimated one
    // says so, because its dollars are tokens times a declared card, not a vendor's invoice.
    expect(markup).toContain('myx-scope');
    expect(markup).not.toContain('>Measured<');
    expect(markup).toContain('<span class="myx-scope-basis">Estimated</span>');
    // Cost is drawn hour by hour, because the opened head's turns were priced.
    expect(markup).not.toContain('No priced turns');
    expect(markup).toContain('aria-label="Hourly API cost"');
    // The legend names the dollars as the inset does: an estimate, never money spent.
    expect(markup).toMatch(/myx-swatch-spent"[^>]*><\/span><span>API cost<\/span>/);
    expect(markup).not.toContain('>Spent<');
  });

  test('a window with turns and none priced has no dollar figure, and the inset says so rather than printing zero', () => {
    const unpriced = fixtureEconomics.heads.filter((head) => head.key === 'claude-splice');
    const markup = render(h(UsageBoard, { payload: { ...fixtureEconomics, heads: unpriced }, catalog: fixtureModels, now: FIXTURE_NOW }));
    expect(markup).toContain('No priced turns');
    expect(markup).toContain('<span class="myx-scope-basis">Unavailable</span>');
    // and the totals row prints the absence glyph, not $0.000
    expect(markup).not.toContain('$0.000');
  });

  // V4-269: the stat printed "1 turns unpriced" for a single turn.
  test('one turn the board could not price is counted in the singular', () => {
    const end = Math.floor(FIXTURE_NOW / HOUR_MS) * HOUR_MS;
    const priced = fixtureEconomics.heads.filter((head) => head.key !== 'claude-splice');
    const lone = {
      ...priced[0], key: 'lone', label: 'lone',
      buckets: [{
        hour: end, turns: 1, in_tokens: 1000, cached_tokens: 0, cache_write_tokens: 0, out_tokens: 10, req_bytes: 0,
        upstream_req_bytes: 0, tools_eager: 0, tools_deferred: 0, deferral_turns: 0, rate_limited: 0,
        cost_usd: null, unpriced_turns: 1,
      }],
    };
    const markup = render(h(UsageBoard, { payload: { ...fixtureEconomics, heads: [...priced, lone] }, catalog: fixtureModels, now: FIXTURE_NOW }));
    expect(markup).toContain('1 turn unpriced');
    expect(markup).not.toContain('1 turns unpriced');
  });

  test('the cost inset counts the turns it could not price beside the dollars', () => {
    const end = Math.floor(FIXTURE_NOW / HOUR_MS) * HOUR_MS;
    const hour = (at: number, turns: number, cost: number | null, unpriced: number) => ({
      hour: at, turns, in_tokens: 1000, cached_tokens: 0, cache_write_tokens: 0, out_tokens: 10, req_bytes: 0,
      upstream_req_bytes: 0, tools_eager: 0, tools_deferred: 0, deferral_turns: 0, rate_limited: 0,
      cost_usd: cost, unpriced_turns: unpriced,
    });
    const markup = render(h(CostChart, {
      buckets: [hour(end - HOUR_MS, 3, null, 0), hour(end, 5, 0.012, 2)],
      window: first(WINDOWS),
      now: FIXTURE_NOW,
    }));
    expect(markup).toMatch(/<span>API cost<\/span><span class="myx-schart-value">\$0\.0120<\/span>/);
    expect(markup).toMatch(/<span>Unpriced turns<\/span><span class="myx-schart-value">2<\/span>/);
  });
});

describe('the totals row', () => {
  const end = Math.floor(NOW / HOUR_MS) * HOUR_MS;
  const bucket = (hour: number, turns: number) => ({
    hour, turns, in_tokens: 100 * turns, cached_tokens: 0, cache_write_tokens: 0, out_tokens: 10 * turns,
    req_bytes: 0, upstream_req_bytes: 0, tools_eager: 0, tools_deferred: 0, deferral_turns: 0, rate_limited: 0,
    cost_usd: 0, unpriced_turns: 0,
  });

  test('a trend is one figure per hour, oldest first, summed across heads, an idle hour at zero', () => {
    const heads = [
      { key: 'a', label: 'a', ceiling_tokens: null, buckets: [bucket(end - 2 * HOUR_MS, 3), bucket(end, 1)] },
      { key: 'b', label: 'b', ceiling_tokens: null, buckets: [bucket(end, 4), bucket(end - 9 * HOUR_MS, 7)] },
    ];
    expect(perHour(heads, 3, NOW, (_, totals) => totals.turns)).toEqual([3, 0, 5]);
  });

  const markup = render(h(UsageBoard, { payload: fixtureEconomics, catalog: fixtureModels, now: FIXTURE_NOW }));

  test('cost is the daemon\'s dollars summed across heads, and the turns it could not price are counted, never priced at zero', () => {
    // claude-splice's model has no rate card: every one of its turns is unpriced
    const day = sum(fixtureEconomics.heads.flatMap((head) => within(head.buckets, 24, FIXTURE_NOW)));
    const splice = sum(within(fixtureEconomics.heads.find((head) => head.key === 'claude-splice')?.buckets ?? [], 24, FIXTURE_NOW));
    expect(splice.turns).toBeGreaterThan(0);
    expect(day.unpricedTurns).toBe(splice.turns);
    const cost = costOf(day);
    expect(cost).toBeGreaterThan(0);
    expect(markup).toContain(`>${fmtUsd(cost ?? 0)}<`);
    expect(markup).toContain(`${splice.turns} turns unpriced`);
  });

  test('each figure carries its shape: the in and out split, a day of trend, the cache ring', () => {
    expect(markup).toMatch(/role="img" aria-label="Tokens: Input [^"]+, Output [^"]+"/);
    expect(markup).toMatch(/aria-label="Turns, last 24h: [^"]+ last, [^"]+ peak"/);
    expect(markup).toMatch(/aria-label="Estimated API cost, last 24h: \$[^"]+"/);
    expect(markup).toMatch(/aria-label="Cache read \d+%"/);
    // each head's own trend, in its hue
    for (const head of fixtureEconomics.heads) expect(markup).toContain(`aria-label="${head.label} Turns per hour, last 24h:`);
  });
});

describe('plan limits', () => {
  test('a failed usage read leaves no skeleton, and a sample renders no plan rack', () => {
    expect(render(h(PlanBay, { usage: null, now: 0 }))).toContain('myx-blank');
    expect(render(h(PlanBay, { usage: null, error: 'Splice is not answering.', now: 0 }))).toBe('');
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
    expect(planRows(usage, [], NOW).map((row) => row.key)).toEqual(['claude-splice', 'claudex', 'claude-muse']);
  });

  // splice-lead on the 2026-09-25 second pass: claudex work's 5h window printed a hero "Unknown", a
  // word in the figure slot. A window that reset since it was read is unmeasured: the absence mark,
  // an empty meter and a badge, never its old figure, and never a zero, since nobody measured one.
  test('a window whose reset passed draws unmeasured, never the figure from before it and never a zero', () => {
    const muse = planRows(usage, [], NOW).find((row) => row.key === 'claude-muse');
    expect(muse?.live).toBeNull();
    expect(windowCells(muse?.windows[1], NOW)).toEqual({ used: '–', resets: 'Reset, not re-read' });
    // both of muse's windows reset since they were read: its 7d held 99% and its 5h held 0%
    const card = render(h(PlanBay, { usage: { ...usage, heads: usage.heads.filter((head) => head.key === 'claude-muse') }, now: NOW }));
    const figures = [...card.matchAll(/class="myx-plan-pct">([^<]*)</g)].map((m) => m[1]);
    expect(figures).toEqual(['–', '–']);
    const badges = [...card.matchAll(/class="myx-badge-word">([^<]*)</g)].map((m) => m[1]);
    expect(badges).toEqual(['Reset, not re-read', 'Reset, not re-read']);
    expect(card).not.toContain('99%');
    expect(card).not.toContain('>0%<');
    expect(card).not.toContain('Unknown');
    // the meters draw empty and announce no value: a meter at 0 would tell a screen reader "0"
    expect(card).not.toContain('role="meter"');
    expect(card).not.toContain('aria-valuenow');
    expect([...card.matchAll(/class="myx-meter myx-meter-neutral" aria-hidden="true"/g)]).toHaveLength(2);
    expect(card).toContain('myx-plan-neutral');
  });

  test('a reset window says the same words on the plan card as on the accounts table', () => {
    expect(windowCells(planRows(usage, [], NOW).find((row) => row.key === 'claude-muse')?.windows[1], NOW).resets).toBe(NOT_REREAD);
    expect(NOT_REREAD).toBe('Reset, not re-read');
  });

  test('a live window prints its figure and how long until it resets', () => {
    const splice = first(planRows(usage, [], NOW));
    expect(windowCells(splice.windows[0], NOW)).toEqual({ used: '65%', resets: 'in 1h 0m' });
    expect(windowCells(splice.windows[1], NOW)).toEqual({ used: '15%', resets: 'in 5d 0h' });
    expect(windowTone(65, 80)).toBe('neutral');
    expect(render(h(PlanBay, { usage, now: NOW }))).toContain('>65%<');
    expect(readText(splice.windows, NOW)).toBe('2m ago');
  });

  test('a window the head does not track, and a reading with no time, print the absence mark', () => {
    const claudex = planRows(usage, [], NOW).find((row) => row.key === 'claudex');
    expect(windowCells(claudex?.windows.find((window) => window.window === '5h'), NOW)).toEqual({ used: '–', resets: '–' });
    expect(readText(claudex?.windows ?? [], NOW)).toBe('–');
  });

  // THE SAME SOURCE RULE AS THE STATUS STRIP (features/nearest-limit): /api/usage reports a pooled
  // head's SELECTED account only, so the demo stack printed claudex 21% here under a strip naming
  // `work` at 64%. A head that rides account rows reads every account from them, each named.
  test('a pooled head is one card per account, read from the rows and named, never its selected login alone', () => {
    const login = (label: string, fiveHour: number, sevenDay: number): AccountRow => ({
      kind: 'chatgpt-oauth', label, single_login: false, credential_path: null, plan: 'pro', primary: label === 'primary',
      selected: false, available: true, pinned: false, next_target: false, credential_present: true,
      observed_at_epoch_seconds: nowS - 60, heads: ['claudex'],
      windows: [
        { seconds: 18_000, used_percent: fiveHour, reset_epoch_seconds: nowS + 3600 },
        { seconds: 604_800, used_percent: sevenDay, reset_epoch_seconds: nowS + 3 * 86400 },
      ],
    });
    const rows = planRows(usage, [login('primary', 38, 21), login('work', 12, 64)], NOW);
    const claudex = rows.filter((row) => row.head === 'claudex');
    expect(claudex.map((row) => [row.account, row.live?.pct])).toEqual([['work', 64], ['primary', 38]]);
    // the head's own /api/usage card (its selected login's 50%) is not drawn beside the rows
    expect(rows.some((row) => row.key === 'claudex')).toBe(false);
    const markup = render(h(PlanBay, { usage, accounts: [login('work', 12, 64)], now: NOW }));
    expect(markup).toContain('aria-label="Plan limits claudex work"');
    expect(markup).toContain('>64%<');
  });

  test('the board draws the plan rack above the heads rack, with the plan name', () => {
    const markup = render(h(UsageBoard, { payload: fixtureEconomics, usage, catalog: fixtureModels, now: NOW }));
    expect(markup).toContain('Plan limits');
    expect(markup).toContain('Reset, not re-read');
    expect(markup).toContain('>pro<');
    expect(markup.indexOf('Plan limits')).toBeLessThan(markup.indexOf('Tokens used'));
  });

  test('no head with a plan window is a named empty, not a blank rack', () => {
    const none: UsagePayload = { ...usage, heads: usage.heads.filter((row) => row.key === 'bonsai') };
    expect(render(h(UsageBoard, { payload: fixtureEconomics, usage: none, catalog: fixtureModels, now: NOW }))).toContain('No plan limits');
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
    const table = /<table[^>]*aria-label="Heads"[^>]*>([\s\S]*?)<\/table>/.exec(markup);
    expect(table).not.toBeNull();
    const [head, body] = (table?.[1] ?? '').split('</thead>');
    const names = [...(head ?? '').matchAll(/<th scope="col"[^>]*>([^<]*)</g)].map((m) => m[1]);
    expect(names).toEqual(['Head', 'Share', 'Turns per hour', 'Turns', 'Input', 'Output', 'Tokens used', 'Limit', 'Runs out in', 'Rate limited']);
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

describe('a column of dashes is not drawn', () => {
  // splice-lead at 2560, 2026-09-25: Limit and Runs out in were a dash on every row when no head has
  // a token ceiling, and the plan cards filled only the left half of their row.
  const names = (markup: string): string[] => {
    const table = /<table[^>]*aria-label="Heads"[^>]*>([\s\S]*?)<\/thead>/.exec(markup)?.[1] ?? '';
    return [...table.matchAll(/<th scope="col"[^>]*>([^<]*)</g)].map((m) => m[1]);
  };

  test('with no head ceilinged, the heads table drops Limit and Runs out in; with one, it keeps both', () => {
    const open = { ...fixtureEconomics, heads: fixtureEconomics.heads.map((head) => ({ ...head, ceiling_tokens: null })) };
    const bare = names(renderToStaticMarkup(createElement(UsageBoard, { payload: open, catalog: fixtureModels, now: FIXTURE_NOW })));
    expect(bare.length).toBeGreaterThan(0); // the denominator: a table that did not render would pass vacuously
    expect(bare).not.toContain('Limit');
    expect(bare).not.toContain('Runs out in');
    const bounded = names(renderToStaticMarkup(createElement(UsageBoard, { payload: fixtureEconomics, catalog: fixtureModels, now: FIXTURE_NOW })));
    expect(bounded).toEqual(expect.arrayContaining(['Limit', 'Runs out in']));
  });

  test('the plan cards share their row rather than holding empty tracks beside them', () => {
    const sheet = readFileSync(fileURLToPath(new URL('../src/pages/usage/usage.css', import.meta.url)), 'utf8');
    expect(/\.myx-plans \{[^}]*grid-template-columns: repeat\((auto-f[a-z]+),/.exec(sheet)?.[1]).toBe('auto-fit');
  });
});
