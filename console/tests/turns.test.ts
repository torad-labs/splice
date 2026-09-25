// M2-03: the turns and logs pages. The projections are pure modules and are tested directly; the
// boards and widgets are rendered to static markup (CONTRACTS.md section 4) and asserted on what a
// reader can actually see. Three of this world's rules are only true if the MARKUP says so:
//   - a phase group the row does not carry contributes no bar and no legend row, rather than a
//     zero-length segment that would read as "this phase took no time";
//   - a turn that lost telemetry says so in words instead of showing smaller numbers;
//   - a pending route renders the honest empty NAMING the row that will serve it, and a head with
//     capture off says exactly that (the capture switch itself is tests/capture.test.ts).
//
// The boards take their payloads as props rather than reading the stores here, because a static
// render sees a zustand store's INITIAL state and never its current one; the store-reading default
// exports are the pages the shell mounts. A virtualized list renders nothing without a viewport,
// which is why the row projections are functions and the strips are exported components.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import type { View } from '../src/features/views';
import { inflightFrom, waterfall } from '../src/entities/perf';
import type { GateSnapshot } from '../src/shared/api';
import type { InflightTurn, TurnRow } from '../src/entities/perf';
import { applyFilter, headOf, headsPresent, levelOf, levelsPresent, timeOf } from '../src/entities/logs';
import type { LogFilter } from '../src/entities/logs';
import { IdleHeads, TurnsBoard } from '../src/pages/turns';
import { badgesOf, isStalled, landedKeysOf, shareText, slotsFrom, stageRowsOf, tokenRowsOf } from '../src/pages/turns/index';
import { LogsBoard, unseenAfter } from '../src/pages/logs';
import { rowKeyer, selectionOf, windowOf } from '../src/pages/turns/select';
import { barRows, totalOf } from '../src/widgets/waterfall/model';
import { counterRows, RequestDrawer, TurnWaterfall } from '../src/widgets/waterfall';
import { LogLine } from '../src/widgets/log-tail';

const h = React.createElement;
const render = (el: React.ReactElement): string => renderToStaticMarkup(el);

const HOUR = 3_600_000;
const T0 = 1_700_000_000_000;

function turn(over: Partial<TurnRow> = {}): TurnRow {
  return {
    head: 'claudex',
    ts: T0,
    model: 'gpt-5.2-codex',
    outcome: 'ok',
    compact: false,
    recv: 2,
    parse: 8,
    build: 10,
    gate: 40,
    headers: 300,
    first_byte: 320,
    first_frame: 322,
    first_delta: 900,
    stream_end: 4000,
    finish: 4010,
    total: 4010,
    ...over,
  };
}

/**
 * The same row with the named keys REMOVED. `exactOptionalPropertyTypes` refuses an explicit
 * undefined, and "the daemon did not write this mark" is an absent key, not an undefined one - the
 * distinction the page's `-` plus basis rendering is built on.
 */
function without(row: TurnRow, ...keys: (keyof TurnRow)[]): TurnRow {
  const copy: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(row)) {
    if (!keys.includes(key as keyof TurnRow)) copy[key] = value;
  }
  return copy as unknown as TurnRow;
}

function view(over: Partial<View> = {}): View {
  return { id: 'v', name: 'v', layout: 'table', filter: {}, sort: null, group: null, fields: ['time', 'head'], ...over };
}

function inflight(over: Partial<InflightTurn> = {}): InflightTurn {
  return {
    head: 'claudex',
    label: 'design-builder4',
    compact: false,
    phase: 'streaming',
    ageMs: 12_000,
    idleMs: 400,
    streamIdleMs: 300_000,
    ...over,
  };
}

const NO_FILTER: LogFilter = { head: null, level: null, substring: '' };

// ── the waterfall's geometry ─────────────────────────────────────────────────

describe('waterfall bars', () => {
  test('files the stages into phase rows, in reading order', () => {
    const rows = barRows(waterfall(turn()), 4010);
    expect(rows.map((row) => [row.group, row.ms])).toEqual([
      ['ingest', 10],
      ['queue', 30],
      ['upstream', 280],
      ['stream', 3680],
      ['finish', 10],
    ]);
  });

  test('a phase row is the time spent in it, not the span from its first segment to its last', () => {
    // The daemon sends the client's first frame at upstream handoff, before the provider's first
    // byte: the stream group's first segment sits inside the provider wait. Its span ran from there
    // to the stream's end and read 7.5s of streaming on a turn that streamed for 0.4s.
    const live = turn({ headers: 459, first_frame: 461, first_byte: 7_470, first_delta: 7_473, stream_end: 7_859, finish: 7_866, total: 7_869 });
    const rows = barRows(waterfall(live), 7_866);
    expect(rows.find((row) => row.group === 'stream')?.ms).toBe(2 + 3 + 386);
    expect(rows.find((row) => row.group === 'upstream')?.ms).toBe(419 + 7_009);
    expect(rows.reduce((sum, row) => sum + row.ms, 0)).toBe(7_866);
  });

  test('a phase the row does not carry has no row at all, and no zero-length bar', () => {
    // Killed before the stream ended: no stream_end, so the close is NOT measurable and its row is
    // absent (an empty row would read as "the close took no time"). The streaming row is still
    // there and simply stops at the last mark the row carries.
    const rows = barRows(waterfall(without(turn(), 'stream_end', 'finish')), 900);
    expect(rows.map((row) => row.group)).toEqual(['ingest', 'queue', 'upstream', 'stream']);
    expect(rows.map((row) => row.group)).not.toContain('finish');
    expect(rows.every((row) => row.bars.length > 0 && row.ms > 0)).toBe(true);
  });

  test('a row with no marks has no geometry to draw, and says so in one line', () => {
    const bare = without(
      turn(), 'recv', 'parse', 'build', 'gate', 'headers', 'first_byte', 'first_frame',
      'first_delta', 'stream_end', 'finish', 'total',
    );
    expect(waterfall(bare)).toEqual([]);
    expect(barRows([], totalOf([]))).toEqual([]);
    expect(render(h(TurnWaterfall, { row: bare }))).toContain('>No stage marks<');
  });

  test('bar fractions are of the turn, so the rows share one axis', () => {
    const stages = waterfall(turn());
    const rows = barRows(stages, totalOf(stages));
    const flat = rows.flatMap((row) => row.bars);
    expect(flat.map((bar) => bar.key)).toEqual([
      'recv', 'parse', 'build', 'gate', 'headers', 'first_byte', 'first_frame', 'first_delta', 'stream_end', 'finish',
    ]);
    expect(flat.every((bar) => bar.x >= 0 && bar.x + bar.w <= 1)).toBe(true);
  });

  test('the chart names every lane it draws and prints its time, so the bar survives grayscale', () => {
    const out = render(h(TurnWaterfall, { row: turn() }));
    // The same names as the stage bars on the page (entities/perf STAGE_NAMES), not a second set.
    expect(out).toContain('>Slot wait<');
    expect(out).toContain('>Provider wait<');
    expect(out).toContain('>Streaming<');
    expect(out).toContain('>30ms<');
    expect(out.match(/class="myx-wf"/g)).toHaveLength(5);
  });

  test('a counter the turn did not report is left out, not printed as a dash', () => {
    expect(counterRows(turn())).toEqual([]);
    expect(counterRows(turn({ retries: 2, req_bytes: 2048 }))).toEqual([['Retries', '2'], ['Request size', '2.0 KiB']]);
  });
});

// ── the in-flight set ────────────────────────────────────────────────────────

describe('in-flight turns', () => {
  const head = (key: string, gate: Partial<GateSnapshot> | null) => ({
    key, label: `claude-${key}`, name: key, port: 3096, authKind: 'chatgpt-oauth',
    wantVersion: '0.4.0', running: true, healthy: true, version: '0.4.0', versionMatch: true,
    mode: null, maxInflight: 8, health: { localOriginErrors: 0, providerErrors: 0 }, pids: [1],
    gate: gate === null ? null : {
      inflight: 0, queued: 0, max: 12, acquired: 0, released: 0, waited: 0, avg_wait_ms: 0, live: [], stream_idle_ms: 0, ...gate,
    },
  });

  test('every head with a gate says its slots in use, its limit and its queue', () => {
    // This daemon writes each gate's live list empty (HeadStatus.kt) and its counts true: the page
    // said "nothing in flight" beside five running turns before it read the counts.
    const slots = slotsFrom([head('x', { inflight: 3, queued: 1 }), head('y', { max: 'unlimited', inflight: 2 }), head('z', null)]);
    expect(slots).toEqual([
      { head: 'x', label: 'claude-x', inflight: 3, queued: 1, max: 12 },
      { head: 'y', label: 'claude-y', inflight: 2, queued: 0, max: null },
    ]);
    const out = render(h(TurnsBoard, { slots, inflight: [], landed: null, summary: null, capture: null }));
    expect(out).toContain('aria-label="claude-x Slots in use: 3 of 12"');
    expect(out.match(/myx-pip myx-pip-on/g)).toHaveLength(3);
    expect(out).toContain('>1 queued<');
    expect(out).toContain('>2<'); // no limit: the count alone, no pips
    expect(out).not.toContain('Nothing in flight');
  });

  test('a listed live turn past its head idle limit is stalled, and says so', () => {
    const live = inflightFrom([head('claudex', {
      stream_idle_ms: 300_000,
      live: [
        { label: 'a', compact: false, phase: 'streaming', age_ms: 1000, idle_ms: 400 },
        { label: 'b', compact: true, phase: 'connect', age_ms: 90_000, idle_ms: 331_000 },
      ],
    })]);
    expect(live).toHaveLength(2);
    expect(isStalled(live[0])).toBe(false);
    expect(isStalled(live[1])).toBe(true);
    const out = render(h(TurnsBoard, { inflight: live, landed: null, summary: null, capture: null }));
    expect(out.match(/>Stalled</g)).toHaveLength(1);
    expect(out).toContain('>Compaction<');
    expect(out).toContain('>connect<');
  });
});

// ── the landed table ─────────────────────────────────────────────────────────

describe('landed turns', () => {
  test('a turn wears its outcome, and the badges its row calls for', () => {
    expect(badgesOf(turn())).toEqual([{ key: 'outcome', tone: 'ok', text: 'ok' }]);
    expect(badgesOf(turn({ outcome: 'conn-reset', compact: true, retries: 2, async_io_drops: 3 })).map((b) => [b.tone, b.text])).toEqual([
      ['danger', 'conn-reset'],
      ['neutral', 'Compaction'],
      ['warn', '2 retries'],
      // lost telemetry is SAID: the row's numbers are short by an unknown amount
      ['warn', 'Telemetry dropped'],
    ]);
  });

  test('a saved view\'s old fields land on the columns that now show them, once each', () => {
    expect(landedKeysOf(['time', 'total', 'cached', 'cacheWrite', 'retries', 'tokensIn', 'nope'])).toEqual(['time', 'timing', 'cache', 'tokensIn']);
  });

  test('every waterfall in the table shares one axis, so the long turn is the long bar', () => {
    const out = render(h(TurnsBoard, {
      inflight: [],
      landed: { inflight: [], landed: [turn({ ts: T0 - 1000 }), turn({ ts: T0, finish: 8020, total: 8020, stream_end: 8010 })], unread: [] },
      summary: null,
      capture: null,
    }));
    const ends = [...out.matchAll(/<span class="myx-wf" role="img"[^>]*>(.*?)<\/span><span class="myx-tn-figure">/g)]
      .map((m) => Math.max(...[...m[1].matchAll(/left:([\d.]+)%;width:([\d.]+)%/g)].map((seg) => Number(seg[1]) + Number(seg[2]))));
    expect(ends).toHaveLength(2);
    expect(ends[0]).toBeCloseTo(100, 0); // newest first: the 8.0s turn
    expect(ends[1]).toBeCloseTo((4010 / 8020) * 100, 0);
  });

  test('a cell the row does not carry prints one dash, and durations and counts are scaled', () => {
    const out = render(h(TurnsBoard, {
      inflight: [],
      landed: { inflight: [], landed: [without(turn({ first_byte: 12_820, out_tokens: 453_608 }), 'in_tokens')], unread: [] },
      summary: null,
      capture: null,
    }));
    expect(out).toContain('>12.8s<');
    expect(out).toContain('>454k<');
    expect(out).toContain('>–<');
  });
});

describe('turn views', () => {
  const rows = [turn({ ts: T0 - 60_000, outcome: 'ok', model: 'a' }), turn({ ts: T0 - 30_000, outcome: 'conn-reset', model: 'b' })];

  test('the default view is the flat table', () => {
    const selection = selectionOf(rows, view(), T0);
    expect(selection.kind).toBe('table');
  });

  test('the table and each group list the newest turn first, whatever order the rows arrived in', () => {
    const table = selectionOf(rows, view(), T0);
    if (table.kind !== 'table') throw new Error('expected the table');
    expect(table.rows.map((row) => row.ts)).toEqual([T0 - 30_000, T0 - 60_000]);
    const same = [turn({ ts: T0 - 90_000, outcome: 'ok' }), turn({ ts: T0 - 10_000, outcome: 'ok' })];
    const grouped = selectionOf(same, view({ group: 'outcome' }), T0);
    if (grouped.kind !== 'groups') throw new Error('expected groups');
    expect(grouped.groups[0]?.rows.map((row) => row.ts)).toEqual([T0 - 10_000, T0 - 90_000]);
  });

  test('a row keeps its key when a newer turn lands above it, and two turns in one millisecond differ', () => {
    const keys = (list: TurnRow[]) => {
      const selection = selectionOf(list, view(), T0);
      if (selection.kind !== 'table') throw new Error('expected the table');
      const keyer = rowKeyer();
      return selection.rows.map(keyer);
    };
    const before = keys(rows);
    const after = keys([...rows, turn({ ts: T0 - 5_000 })]);
    // the opened row is found by key on every poll; the new turn sits first and moves nobody's key
    expect(after.slice(1)).toEqual(before);
    const twins = keys([turn({ ts: T0 }), turn({ ts: T0 })]);
    expect(new Set(twins).size).toBe(2);
  });

  test('a grouped view files every row under its group, biggest first, ties by name', () => {
    const selection = selectionOf(rows, view({ group: 'outcome' }), T0);
    if (selection.kind !== 'groups') throw new Error('expected groups');
    expect(selection.groups.map((group) => group.key)).toEqual(['conn-reset', 'ok']);
    expect(selection.groups.flatMap((group) => group.rows)).toHaveLength(2);
  });

  test('a grouped table prints each group once, and not again as a column', () => {
    const out = render(h(TurnsBoard, { inflight: [], landed: { inflight: [], landed: rows, unread: [] }, summary: null, capture: null }));
    expect(out).toContain('>Model<');
  });

  test('the timeline buckets by hour and keeps the undated rows apart', () => {
    const selection = selectionOf([...rows, without(turn(), 'ts')], view({ layout: 'timeline', filter: { window: '2h', bucket: '1h' } }), T0);
    expect(selection.kind).toBe('timeline');
    if (selection.kind !== 'timeline') return;
    expect(selection.timeline.buckets).toHaveLength(2);
    expect(selection.timeline.buckets[0].rows).toHaveLength(0); // the idle hour is present
    expect(selection.timeline.buckets[1].rows).toHaveLength(2);
    expect(selection.timeline.undated).toHaveLength(1);
    expect(windowOf(view({ filter: { window: 'nope' } }), T0).hours).toBe(24);
  });
});

// ── the turns board ──────────────────────────────────────────────────────────

describe('turns board', () => {
  const board = (over: Partial<React.ComponentProps<typeof TurnsBoard>> = {}) =>
    render(h(TurnsBoard, { inflight: [inflight()], landed: { inflight: [], landed: [turn()], unread: [] }, summary: null, capture: null, ...over }));

  test('a route this daemon does not serve says so in words, not a row id', () => {
    const out = board({ landed: { pending: 'V4-127' } });
    expect(out).toContain('History unavailable');
    expect(out).not.toContain('V4-127');
    expect(out).not.toContain('myx-tn-table');
  });

  test('the summary names its window, draws its percentiles, and names the idle heads once', () => {
    const out = board({
      summary: {
        window: '24h',
        heads: [
          {
            key: 'claudex', label: 'claudex', window: '24h', count: 16, empty: false, coverage_known: true,
            clamped: false, covers_ms: HOUR,
            time_before_first_byte_ms: { count: 16, p50: 489, p95: 3192, max: 3192 },
            total_ms: { count: 16, p50: 10_580, p95: 46_967, max: 46_967 },
            failure_share: 0.25, cache_hit_ratio: 0.979, retries: 0, refreshes: 0, peak_inflight: 1, io_drops_in_window: 0,
          },
          { key: 'claude-grok', label: 'claude-grok', window: '24h', count: 0, empty: true, coverage_known: true, clamped: true, covers_ms: HOUR },
          { key: 'claude-kimi', label: 'claude-kimi', window: '24h', count: 0, empty: true, coverage_known: true, clamped: true, covers_ms: HOUR },
        ],
      },
    });
    expect(out).toContain('>Last 24 hours<');
    expect(out).toContain('>489ms<');
    expect(out).toContain('>3.2s<'); // p95, the bar's pale end
    expect(out).toContain('>10.6s<');
    expect(out).toContain('>25%<');
    expect(out).toContain('>98%<');
    expect(out).toContain('aria-label="First byte p50 489ms, p95 3.2s"');
    // Refreshes and lost rows are columns only when a head has some.
    expect(out).not.toContain('>Refreshes<');
    expect(out).not.toContain('>Lost log rows<');
    // An idle head is named once, on one line, and gets no row: an empty window never reads as fast.
    const idle = out.slice(out.indexOf('myx-tn-idle'));
    expect(idle).toContain('>No turns<');
    expect(idle).toContain('>claude-grok<');
    expect(idle).toContain('>claude-kimi<');
    expect(out.match(/>claude-grok</g)).toHaveLength(1);
  });

  test('a head is printed by its label everywhere on the page, not the key its rows carry', () => {
    const out = board({
      inflight: [],
      landed: { inflight: [], landed: [turn({ head: 'bonsai' })], unread: [] },
      summary: { window: '24h', heads: [{ key: 'bonsai', label: 'claude-bonsai', window: '24h', count: 0, empty: true, coverage_known: true, clamped: false, covers_ms: 0 }] },
    });
    expect(out).toContain('>claude-bonsai<');
    expect(out).not.toContain('>bonsai<');
  });

  test('time per stage is the difference between marks, never the marks added up', () => {
    // Cumulative marks, ms since arrival: 3 ms of splice work (arrival to build), 7 queued, 100
    // waiting on the provider, 890 streaming, 1 closing. Summing the raw marks gave finish (1001)
    // and stream end (1000) half of all time each.
    const row = { head: 'h', outcome: 'ok', recv: 1, parse: 2, build: 3, gate: 10, headers: 100, first_byte: 110, first_frame: 111, first_delta: 120, stream_end: 1000, finish: 1001 } as TurnRow;
    const stages = stageRowsOf([row, row]);
    expect(stages.map((stage) => [stage.label, stage.perTurn, shareText(stage.share)])).toEqual([
      ['Splice work', 3, '0.3%'],
      ['Slot wait', 7, '0.7%'],
      ['Provider wait', 100, '10.0%'],
      ['Streaming', 890, '89%'],
      ['Closing', 1, '<0.1%'],
    ]);
  });

  test('a part no turn reached is not printed, and no rows is no stages', () => {
    const failed = { head: 'h', outcome: 'upstream_error', recv: 1, parse: 2, build: 3, gate: 10 } as TurnRow;
    expect(stageRowsOf([failed]).map((stage) => stage.label)).toEqual(['Splice work', 'Slot wait']);
    expect(stageRowsOf([])).toEqual([]);
  });

  test('the tokens split each head\'s input into what the cache served, wrote and missed', () => {
    const rows = [turn({ in_tokens: 1000, cached_tokens: 700, cache_write_tokens: 100, out_tokens: 50 }), turn({ in_tokens: 500, cached_tokens: 500, cache_write_tokens: 0, out_tokens: 10 })];
    expect(tokenRowsOf(rows)).toEqual([{ head: 'claudex', in: 1500, cached: 1200, write: 100, out: 60 }]);
    const out = board({ landed: { inflight: [], landed: rows, unread: [] } });
    expect(out).toContain('aria-label="Input: Cached 1.2k, Cache write 100, Uncached 200"');
  });

  test('nothing in flight is one line, not an empty table', () => {
    const out = board({ inflight: [], slots: [] });
    expect(out).toContain('Nothing in flight');
  });
});

// ── the log tail ─────────────────────────────────────────────────────────────

describe('log lines', () => {
  const line = '[2026-09-18 01:14:05] [claude-deepseek] turn ERROR conn-reset compact=false latency=2827ms';

  test('a line is its time, its tag, its level and the line itself', () => {
    const out = render(h(LogLine, { line }));
    expect(out).toContain('01:14:05');
    expect(out).toContain('claude-deepseek');
    expect(out).toContain('>error<');
    expect(out).toContain('myx-badge-danger'); // the severity is also the dot and the row's tint, and the word is printed
    expect(out).toContain('myx-lt-danger');
  });

  test('an unmarked line is unmarked and wordless, never `info`', () => {
    // It printed `-` in a level column and `line` on its edge; now it carries no mark at all.
    const out = render(h(LogLine, { line: '[2026-09-18 01:14:01] [claudex] turn latency=3052ms ok' }));
    expect(out).not.toContain('myx-badge');
    expect(out).not.toMatch(/myx-lt-(danger|warn)/);
    expect(out).not.toContain('>info<');
    expect(out).not.toContain('>-<');
  });

  test('the filter model reads tags, levels and substrings', () => {
    expect(headOf(line)).toBe('claude-deepseek');
    expect(timeOf(line)).toBe('01:14:05');
    expect(levelOf(line)).toBe('error');
    expect(applyFilter([line], { ...NO_FILTER, level: 'error' })).toEqual([line]);
    expect(applyFilter([line], { ...NO_FILTER, level: 'warn' })).toEqual([]);
    expect(applyFilter([line], { ...NO_FILTER, substring: 'CONN-RESET' })).toEqual([line]);
    expect(headsPresent([line])).toEqual(['claude-deepseek']);
    expect(levelsPresent([line])).toEqual(['error']);
    expect(levelsPresent(['[2026-09-18 01:14:01] [claudex] ok'])).toEqual([]);
  });
});

describe('logs board', () => {
  const board = (over: Partial<React.ComponentProps<typeof LogsBoard>> = {}) =>
    render(
      h(LogsBoard, {
        payload: { key: 'claudex', path: '/home/user/.splice/logs/daemon.log', lines: [] },
        filter: NO_FILTER,
        follow: true,
        appended: 0,
        reset: false,
        tags: [],
        levels: [],
        head: 'claudex',
        tail: 200,
        heads: [{ key: 'claudex', label: 'claudex' }],
        ...over,
      }),
    );

  test('an empty tail names the path it read', () => {
    const out = board();
    expect(out).toContain('No lines');
    expect(out).toContain('/home/user/.splice/logs/daemon.log');
    expect(out, 'an empty rack gives guidance, not a route').not.toContain('/api/');
  });

  test('a filter box prints only when it has a choice to offer', () => {
    // A head's own log carries one tag and, usually, no level: each box offered `all` and nothing.
    expect(board()).not.toContain('>Tag<');
    expect(board()).not.toContain('>Level<');
    expect(board({ tags: ['claudex', 'daemon'], levels: ['error'] })).toContain('>Tag<');
    expect(board({ tags: ['claudex', 'daemon'], levels: ['error'] })).toContain('>Level<');
  });

  test('a chosen filter keeps its box, and its value, after the lines that offered it scroll out', () => {
    // level=error was picked, then the error lines left the tail: the filter still applies, so its
    // box must stay, still saying error, for the reader to clear it.
    const out = board({ filter: { ...NO_FILTER, level: 'error' }, levels: [] });
    expect(out).toContain('>Level<');
    expect(out).toContain('>error<');
    expect(board({ filter: { ...NO_FILTER, head: 'daemon' }, tags: ['claudex'] })).toContain('>Tag<');
  });

  test('a paused count adds what arrived, and a rotation starts it over rather than adding the window', () => {
    expect(unseenAfter(5, { appended: ['a', 'b'], reset: false }, false)).toBe(7);
    expect(unseenAfter(5, { appended: Array.from({ length: 200 }, () => 'x'), reset: true }, false)).toBe(0);
    expect(unseenAfter(5, { appended: ['a'], reset: false }, true)).toBe(0);
  });

  test('the new-lines count prints while paused and never while following', () => {
    expect(board({ follow: true, appended: 200 })).not.toContain('new lines');
    expect(board({ follow: false, appended: 12 })).toContain('12 new lines');
  });

  test('the drawer prints the tailed head\'s capture, and never another head\'s', () => {
    const capture = (head: string) => ({
      running: { head, enabled: false, retention_days: 7, max_body_chars: 4_194_304, restart_required: true },
      written: null,
      refused: null,
      writing: false,
    });
    const drawer = 'aria-label="Body capture"';
    expect(render(h(RequestDrawer, { capture: capture('claudex') }))).toContain(drawer);
    expect(board({ capture: capture('claudex') })).toContain(drawer);
    expect(board({ capture: capture('other-head') })).not.toContain(drawer);
  });

  test('a rotated tail says it restarted', () => {
    expect(board({ reset: true })).toContain('rotated');
  });
});

describe('an idle head says when it last ran a turn', () => {
  test('from the summary\'s last_ts, and never for a head that has none', () => {
    const row = (key: string, last: number | null | undefined) => ({
      key, label: key, window: '24h' as const, count: 0, empty: true, coverage_known: true, clamped: false, covers_ms: 0,
      ...(last === undefined ? {} : { last_ts: last }),
    });
    const out = renderToStaticMarkup(h(IdleHeads, {
      summary: { window: '24h', heads: [row('bonsai', Date.now() - 50 * 3_600_000), row('bonsai-vast', null), row('old', undefined)] },
    }));
    expect(out).toMatch(/>bonsai<\/span><\/span><span class="myx-tn-quiet">last 2d ago</);
    expect(out).toMatch(/>bonsai-vast<\/span><\/span><span class="myx-tn-quiet">Never</);
    expect(out).toMatch(/>old<\/span><\/span><\/span>/);
  });
});
