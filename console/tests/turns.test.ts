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
import type { InflightTurn, TurnRow } from '../src/entities/perf';
import { applyFilter, headOf, headsPresent, levelOf, levelsPresent, timeOf } from '../src/entities/logs';
import type { LogFilter } from '../src/entities/logs';
import { TurnsBoard } from '../src/pages/turns';
import { LogsBoard } from '../src/pages/logs';
import { itemsOf, selectionOf, windowOf } from '../src/pages/turns/select';
import { edgeOfInflight, fieldsOf, inflightFieldsOf } from '../src/pages/turns/strip';
import { barRows, totalOf } from '../src/widgets/waterfall/model';
import { CAPTURE_OFF, RequestDrawer, Waterfall } from '../src/widgets/waterfall';
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
      ['ingest', 8],
      ['queue', 30],
      ['upstream', 280],
      ['stream', 3680],
      ['finish', 10],
    ]);
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

  test('a row with no marks has no geometry to draw', () => {
    const bare = without(
      turn(), 'recv', 'parse', 'build', 'gate', 'headers', 'first_byte', 'first_frame',
      'first_delta', 'stream_end', 'finish', 'total',
    );
    expect(waterfall(bare)).toEqual([]);
    expect(barRows([], totalOf([]))).toEqual([]);
    expect(render(h(Waterfall, { row: bare }))).toContain('unavailable');
  });

  test('bar fractions are of the turn, so the rows share one axis', () => {
    const stages = waterfall(turn());
    const rows = barRows(stages, totalOf(stages));
    const flat = rows.flatMap((row) => row.bars);
    expect(flat.map((bar) => bar.key)).toEqual([
      'parse', 'build', 'gate', 'headers', 'first_byte', 'first_frame', 'first_delta', 'stream_end', 'finish',
    ]);
    expect(flat.every((bar) => bar.x >= 0 && bar.x + bar.w <= 1)).toBe(true);
  });

  test('the chart prints every row it draws, so the bar survives grayscale', () => {
    const out = render(h(Waterfall, { row: turn() }));
    expect(out).toContain('>queue<');
    expect(out).toContain('>upstream<');
    expect(out).toContain('>stream<');
    expect(out).toContain('30ms'); // the legend prints each row's span
    expect(out).toContain('myx-wf-svg');
  });
});

// ── the in-flight set ────────────────────────────────────────────────────────

describe('in-flight turns', () => {
  test('reads the gate snapshots and cocks a turn past its head idle threshold', () => {
    const live = inflightFrom([
      {
        key: 'claudex', label: 'claudex', name: 'claudex', port: 3096, authKind: 'chatgpt-oauth',
        wantVersion: '0.4.0', running: true, healthy: true, version: '0.4.0', versionMatch: true,
        mode: null, maxInflight: 8, health: { localOriginErrors: 0, providerErrors: 0 }, pids: [1],
        gate: {
          inflight: 2, queued: 0, max: 8, acquired: 2, released: 0, waited: 0, avg_wait_ms: 0,
          stream_idle_ms: 300_000,
          live: [
            { label: 'a', compact: false, phase: 'streaming', age_ms: 1000, idle_ms: 400 },
            { label: 'b', compact: true, phase: 'connect', age_ms: 90_000, idle_ms: 331_000 },
          ],
        },
      },
    ]);
    expect(live).toHaveLength(2);
    expect(edgeOfInflight(live[0])).toBe('green');
    expect(edgeOfInflight(live[1])).toBe('amber');
  });

  test('prints the phase and the idle time, and no outcome it does not have', () => {
    const fields = inflightFieldsOf(inflight(), ['session', 'phase', 'age', 'outcome']);
    expect(fields.map((f) => [f.label, f.basis])).toEqual([
      ['session', 'measured'],
      ['phase', 'measured'],
      ['age', 'measured'],
    ]);
  });
});

// ── the landed table ─────────────────────────────────────────────────────────

describe('turn strips', () => {
  test('a row that lost telemetry says so in words, never with smaller numbers', () => {
    expect(fieldsOf(turn({ async_io_drops: 3 }), ['dropped'])[0].value).toBe('telemetry dropped');
    expect(fieldsOf(turn({ async_io_drops: 0 }), ['dropped'])[0].value).toBe('n/r');
    expect(fieldsOf(turn(), ['dropped'])[0].value).toBe('n/r');
  });

  test('a column the row does not carry is absent and says so in one glyph', () => {
    // The glyph is the whole statement, so an absent cell carries no basis word (m1 design review
    // B8): `- unavailable` was one fact in two sentences and read as a typo.
    const fields = fieldsOf(without(turn(), 'total', 'in_tokens'), ['total', 'tokensIn', 'head']);
    expect(fields.map((f) => [f.value, f.basis])).toEqual([
      ['n/r', undefined],
      ['n/r', undefined],
      ['claudex', 'measured'],
    ]);
  });

  test('scales a duration and a token count so the column reads without a unit', () => {
    const fields = fieldsOf(turn({ total: 12_820, in_tokens: 453_608 }), ['total', 'tokensIn']);
    expect(fields.map((f) => f.value)).toEqual(['12.8s', '454k']);
  });
});

describe('turn views', () => {
  const rows = [turn({ ts: T0 - 60_000, outcome: 'ok', model: 'a' }), turn({ ts: T0 - 30_000, outcome: 'conn-reset', model: 'b' })];

  test('the default view is the flat table', () => {
    const selection = selectionOf(rows, view(), T0);
    expect(selection.kind).toBe('table');
    if (selection.kind !== 'table') return;
    expect(itemsOf(selection).map((item) => item.kind)).toEqual(['row', 'row']);
  });

  test('a grouped view puts a band before each group, and no band for an empty one', () => {
    const selection = selectionOf(rows, view({ group: 'outcome' }), T0);
    const items = itemsOf(selection);
    expect(items[0].kind).toBe('band');
    // Ties are broken by key, so the two single-row groups land in alphabetical order.
    expect(items.filter((item) => item.kind === 'band').map((item) => item.label)).toEqual(['conn-reset', 'ok']);
    expect(items.filter((item) => item.kind === 'row')).toHaveLength(2);
  });

  test('the timeline bands by hour and reports the undated rows', () => {
    const selection = selectionOf([...rows, without(turn(), 'ts')], view({ layout: 'timeline', filter: { window: '2h', bucket: '1h' } }), T0);
    expect(selection.kind).toBe('timeline');
    if (selection.kind !== 'timeline') return;
    expect(selection.timeline.buckets).toHaveLength(2);
    expect(selection.timeline.buckets[0].rows).toHaveLength(0); // the idle hour is present
    expect(selection.timeline.buckets[1].rows).toHaveLength(2);
    expect(selection.timeline.undated).toHaveLength(1);
    const items = itemsOf(selection);
    expect(items[0].kind).toBe('band'); // only the bucket that holds rows bands
    expect(items.filter((item) => item.kind === 'row')).toHaveLength(3);
    expect(windowOf(view({ filter: { window: 'nope' } }), T0).hours).toBe(24);
  });
});

// ── the turns board ──────────────────────────────────────────────────────────

describe('turns board', () => {
  const board = (over: Partial<React.ComponentProps<typeof TurnsBoard>> = {}) =>
    render(h(TurnsBoard, { inflight: [inflight()], landed: { inflight: [], landed: [turn()], unread: [] }, summary: null, capture: null, ...over }));

  test('a route that does not exist renders the empty that names its row', () => {
    const out = board({ landed: { pending: 'V4-127' } });
    expect(out).toContain('row V4-127');
    expect(out).not.toContain('myx-tn-scroll');
  });

  test('the summary bay renders what exists today, and an empty window says so', () => {
    const out = board({
      summary: {
        window: '24h',
        heads: [
          {
            key: 'claudex', label: 'claudex', window: '24h', count: 0, empty: true, coverage_known: true,
            clamped: true, covers_ms: HOUR,
          },
        ],
      },
    });
    expect(out).toContain('no turns in window'); // the whole sentence, in the strip's aria-label
    expect(out).toContain('>n/r<'); // no percentile was computed for an empty window
    expect(out).toContain('>empty<'); // and the edge says the window has no rows
  });

  test('nothing in flight is an honest empty, not an empty bay', () => {
    expect(board({ inflight: [] })).toContain('nothing in flight');
  });
});

// ── the log tail ─────────────────────────────────────────────────────────────

describe('log lines', () => {
  const line = '[2026-09-18 01:14:05] [claude-deepseek] turn ERROR conn-reset compact=false latency=2827ms';

  test('a line is a strip of time, tag, level and the line itself', () => {
    const out = render(h(LogLine, { line }));
    expect(out).toContain('01:14:05');
    expect(out).toContain('claude-deepseek');
    expect(out).toContain('>error<');
    expect(out).toContain('myx-edge-red'); // the severity is also the edge, and the word is printed
  });

  test('an unmarked line is grey and says it was not marked, never `info`', () => {
    const out = render(h(LogLine, { line: '[2026-09-18 01:14:01] [claudex] turn latency=3052ms ok' }));
    expect(out).toContain('myx-edge-grey');
    expect(out).toContain('>-<');
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
    expect(out).toContain('no lines in this tail');
    expect(out).toContain('/home/user/.splice/logs/daemon.log');
  });

  test('the drawer prints the tailed head\'s capture, and never another head\'s', () => {
    const capture = (head: string) => ({
      running: { head, enabled: false, retention_days: 7, max_body_chars: 4_194_304, restart_required: true },
      written: null,
      refused: null,
      writing: false,
    });
    expect(render(h(RequestDrawer, { capture: capture('claudex') }))).toContain(CAPTURE_OFF);
    expect(board({ capture: capture('claudex') })).toContain(CAPTURE_OFF);
    expect(board({ capture: capture('other-head') })).not.toContain(CAPTURE_OFF);
  });

  test('a rotated tail says it restarted', () => {
    expect(board({ reset: true })).toContain('rotated');
  });
});
