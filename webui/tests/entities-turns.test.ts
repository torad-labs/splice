// M2-D1: the data behind the turns, logs and models entities. Stores and derivations are tested
// directly (CONTRACTS.md 4) - no jsdom, no rendering, no live daemon. The two API-level cases stub
// global fetch, because the pending state a page renders IS a property of the response handling:
// "not built yet" and "the request failed" must not be the same shape in the store.
import { afterEach, describe, expect, test, vi } from 'vitest';
import type { GateSnapshot, HeadStatus, LogsPayload } from '../src/shared/api';
import type { PendingRoute, TurnRow, TurnsState } from '../src/entities/perf';
import { fetchPerfTurns, groupTurns, inflightFrom, marksOf, waterfall, UNATTRIBUTED, PENDING_TURNS } from '../src/entities/perf';
// The pending rule is ONE implementation (@shared/api, CONTRACTS.md 8): this file calls the shared
// two-argument form with the row name the slice binds, instead of the slice-local binding M2-D1
// used to export.
import { MgmtError, pendingOf } from '../src/shared/api';
import { perfTurnsStore } from '../src/entities/perf/model/store';
import { fetchModels } from '../src/entities/model';
import { modelsStore } from '../src/entities/model/model/store';
import { advance, applyFilter, headOf, headsPresent, levelOf, levelsPresent, tailOf, NO_FILTER } from '../src/entities/logs';
import type { LogTail } from '../src/entities/logs';

// ── fixtures ─────────────────────────────────────────────────────────────────

function turn(over: Partial<TurnRow> = {}): TurnRow {
  return { head: 'claudex', ts: 1_760_000_000_000, model: 'gpt-5.2', outcome: 'ok', compact: false, ...over };
}

function gate(over: Partial<GateSnapshot> = {}): GateSnapshot {
  return {
    inflight: 1,
    queued: 0,
    max: 8,
    acquired: 2,
    released: 1,
    waited: 1,
    avg_wait_ms: 3,
    stream_idle_ms: 300_000,
    live: [],
    ...over,
  };
}

function head(over: Partial<HeadStatus> & { key: string }): HeadStatus {
  return {
    key: over.key,
    label: over.label ?? over.key,
    name: over.name ?? over.key,
    port: over.port ?? 3096,
    authKind: over.authKind ?? 'chatgpt-oauth',
    wantVersion: over.wantVersion ?? '0.4.0',
    running: over.running ?? true,
    healthy: over.healthy ?? true,
    version: over.version ?? '0.4.0',
    versionMatch: over.versionMatch ?? true,
    mode: over.mode ?? null,
    gate: over.gate ?? null,
    maxInflight: over.maxInflight ?? 8,
    health: over.health ?? { localOriginErrors: 0, providerErrors: 0 },
    pids: over.pids ?? [1234],
  };
}

function logs(lines: string[], over: Partial<LogsPayload> = {}): LogsPayload {
  return { key: over.key ?? 'claudex', path: over.path ?? '/home/user/.claude-codex/logs/daemon.log', lines };
}

/** The store holds a union; a test that expects rows must fail loudly when it holds the pending
 *  state instead of silently reading undefined off it. */
function landed(state: TurnsState | PendingRoute | null): TurnsState {
  if (state === null || 'pending' in state) {
    throw new Error(`expected landed turns, got ${JSON.stringify(state)}`);
  }
  return state;
}

/** Routes not named here answer 404, the way an unbuilt daemon route does. */
function stubRoutes(routes: Record<string, unknown>): void {
  vi.stubGlobal('fetch', (input: unknown): Promise<Response> => {
    const path = String(input).split('?')[0];
    if (!(path in routes)) return Promise.resolve(new Response('not found', { status: 404 }));
    return Promise.resolve(
      new Response(JSON.stringify(routes[path]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

// ── waterfall ────────────────────────────────────────────────────────────────

describe('waterfall', () => {
  test('splits a turn into consecutive mark pairs in pipeline order', () => {
    const row = turn({
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
    });
    expect(waterfall(row).map((s) => [s.key, s.ms])).toEqual([
      ['parse', 6],
      ['build', 2],
      ['gate', 30],
      ['headers', 260],
      ['first_byte', 20],
      ['first_frame', 2],
      ['first_delta', 578],
      ['stream_end', 3100],
      ['finish', 10],
    ]);
    // The three groups FEATURES.md 4.3 wants visibly separate.
    expect(waterfall(row).filter((s) => s.group === 'queue').map((s) => s.key)).toEqual(['gate']);
    expect(waterfall(row).filter((s) => s.group === 'upstream').map((s) => s.key)).toEqual(['headers', 'first_byte']);
    expect(waterfall(row).filter((s) => s.group === 'stream').map((s) => s.key)).toEqual([
      'first_frame',
      'first_delta',
      'stream_end',
    ]);
  });

  test('omits a segment whose marks are not both present, never as a zero', () => {
    // A turn that died mid-stream: no stream_end, so neither it nor finish can be measured.
    const row = turn({ recv: 1, parse: 3, build: 4, gate: 5, headers: 90, first_byte: 95, first_frame: 96, first_delta: 200 });
    const keys = waterfall(row).map((s) => s.key);
    expect(keys).toEqual(['parse', 'build', 'gate', 'headers', 'first_byte', 'first_frame', 'first_delta']);
    expect(keys).not.toContain('stream_end');
    expect(keys).not.toContain('finish');
  });

  test('drops a mark pair that runs backwards rather than drawing a negative duration', () => {
    const row = turn({ recv: 1, parse: 2, build: 3, gate: 4, headers: 300, first_byte: 100, first_frame: 101 });
    const segments = waterfall(row);
    expect(segments.map((s) => s.key)).not.toContain('first_byte'); // headers(300) -> first_byte(100)
    expect(segments.map((s) => s.key)).toContain('headers');
    expect(segments.every((s) => s.ms >= 0)).toBe(true);
  });

  test('a row with no marks has no segments', () => {
    expect(waterfall(turn())).toEqual([]);
    expect(marksOf(turn())).toEqual([]);
  });

  test('marksOf lists only the marks the row carries, in pipeline order', () => {
    expect(marksOf(turn({ finish: 9, recv: 1, gate: 4 }))).toEqual(['recv', 'gate', 'finish']);
  });
});

// ── grouping ─────────────────────────────────────────────────────────────────

describe('groupTurns', () => {
  const rows: TurnRow[] = [
    turn({ outcome: 'ok', model: 'a', session: 'aaaaaaaa' }),
    turn({ outcome: 'ok', model: 'a', session: 'aaaaaaaa' }),
    turn({ outcome: 'conn-reset', model: 'b', session: 'bbbbbbbb' }),
    turn({ outcome: 'conn-reset', model: 'b' }),
    turn({ outcome: 'conn-reset', model: 'b' }),
  ];

  test('files by session and keeps unattributed turns in the total', () => {
    const groups = groupTurns(rows, 'session');
    expect(groups.map((g) => [g.key, g.count])).toEqual([
      ['aaaaaaaa', 2],
      [UNATTRIBUTED, 2],
      ['bbbbbbbb', 1],
    ]);
    expect(groups.reduce((n, g) => n + g.count, 0)).toBe(rows.length);
  });

  test('files by outcome, biggest first, ties by key', () => {
    expect(groupTurns(rows, 'outcome').map((g) => [g.key, g.count])).toEqual([
      ['conn-reset', 3],
      ['ok', 2],
    ]);
  });

  test('files by head and by model', () => {
    expect(groupTurns([...rows, turn({ head: 'claude-grok', model: 'a' })], 'head').map((g) => [g.key, g.count])).toEqual([
      ['claudex', 5],
      ['claude-grok', 1],
    ]);
    expect(groupTurns(rows, 'model').map((g) => [g.key, g.count])).toEqual([
      ['b', 3],
      ['a', 2],
    ]);
  });

  test('a row with an empty session tag is unattributed, not a group named ""', () => {
    expect(groupTurns([turn({ session: '' })], 'session').map((g) => g.key)).toEqual([UNATTRIBUTED]);
  });
});

// ── the in-flight set ────────────────────────────────────────────────────────

describe('inflightFrom', () => {
  test('reads the gate snapshots and carries the head and its idle threshold', () => {
    const live = inflightFrom([
      head({
        key: 'claudex',
        gate: gate({
          stream_idle_ms: 120_000,
          live: [
            { label: 'sess-a', compact: false, phase: 'streaming', age_ms: 12_000, idle_ms: 400 },
            { label: 'sess-b', compact: true, phase: 'connect', age_ms: 900, idle_ms: 900 },
          ],
        }),
      }),
      head({ key: 'claude-grok' }), // no gate snapshot: contributes nothing, not an empty turn
    ]);
    expect(live).toEqual([
      { head: 'claudex', label: 'sess-a', compact: false, phase: 'streaming', ageMs: 12_000, idleMs: 400, streamIdleMs: 120_000 },
      { head: 'claudex', label: 'sess-b', compact: true, phase: 'connect', ageMs: 900, idleMs: 900, streamIdleMs: 120_000 },
    ]);
  });

  test('a stopped fleet is idle, not an error', () => {
    expect(inflightFrom([head({ key: 'claudex', running: false, gate: gate() })])).toEqual([]);
  });
});

// ── the pending routes ───────────────────────────────────────────────────────

describe('pending routes', () => {
  test('a 404 on /api/perf/turns resolves the store to the pending state, not to rows', async () => {
    stubRoutes({});
    await fetchPerfTurns();
    expect(perfTurnsStore.get().data).toEqual({ pending: 'V4-127' });
    expect(perfTurnsStore.get().error).toBeNull();
  });

  test('a 404 on /api/models resolves the models store to the pending state', async () => {
    stubRoutes({});
    await fetchModels();
    expect(modelsStore.get().data).toEqual({ pending: 'V4-127' });
    expect(modelsStore.get().error).toBeNull();
  });

  test('a real failure stays an error and is never dressed up as pending', async () => {
    vi.stubGlobal('fetch', (): Promise<Response> => Promise.resolve(new Response('boom', { status: 500 })));
    await fetchPerfTurns();
    const state = perfTurnsStore.get();
    expect(state.error).toBe('HTTP 500');
    expect(state.loading).toBe(false);
  });

  test('pendingOf maps an absent route and nothing else', () => {
    expect(pendingOf(new MgmtError(404, 'HTTP 404'), PENDING_TURNS)).toEqual({ pending: 'V4-127' });
    expect(pendingOf(new MgmtError(400, 'unknown route /api/perf/turns'), PENDING_TURNS)).toEqual({ pending: 'V4-127' });
    expect(pendingOf(new MgmtError(500, 'HTTP 500'), PENDING_TURNS)).toBeNull();
    expect(pendingOf(new MgmtError(401, 'management key required'), PENDING_TURNS)).toBeNull();
    expect(pendingOf(new Error('network down'), PENDING_TURNS)).toBeNull();
  });

  test('landed rows and the live set arrive together on the happy path', async () => {
    const row = turn({ session: 'aaaaaaaa', total: 4010 });
    stubRoutes({
      '/api/perf/turns': { turns: [row] },
      '/api/heads': {
        heads: [
          head({
            key: 'claudex',
            gate: gate({ live: [{ label: 'sess-a', compact: false, phase: 'streaming', age_ms: 12_000, idle_ms: 400 }] }),
          }),
        ],
      },
    });
    await fetchPerfTurns();
    const state = landed(perfTurnsStore.get().data);
    expect(state.landed).toEqual([row]);
    expect(state.inflight.map((t) => [t.head, t.label])).toEqual([['claudex', 'sess-a']]);
  });
});

// ── the log tail cursor ──────────────────────────────────────────────────────

describe('log tail cursor', () => {
  const line = (n: number): string => `[2026-09-18 01:14:0${n % 10}] [claudex] turn ${n}`;

  test('the first read appends everything and is not a reset', () => {
    const first = advance(null, logs([line(1), line(2)]));
    expect(first.appended).toEqual([line(1), line(2)]);
    expect(first.reset).toBe(false);
    expect(first.tail).toEqual(tailOf(logs([line(1), line(2)])));
  });

  test('a growing tail appends only the new lines', () => {
    const prev: LogTail = tailOf(logs([line(1), line(2)]));
    const next = advance(prev, logs([line(1), line(2), line(3)]));
    expect(next.appended).toEqual([line(3)]);
    expect(next.reset).toBe(false);
  });

  test('a window that slid forwards still appends only what is new', () => {
    const prev: LogTail = tailOf(logs([line(1), line(2), line(3)]));
    const next = advance(prev, logs([line(2), line(3), line(4)]));
    expect(next.appended).toEqual([line(4)]);
    expect(next.reset).toBe(false);
  });

  test('an unchanged tail appends nothing', () => {
    const prev: LogTail = tailOf(logs([line(1), line(2)]));
    expect(advance(prev, logs([line(1), line(2)])).appended).toEqual([]);
  });

  test('a rotated log resets instead of claiming the whole window is new', () => {
    const prev: LogTail = tailOf(logs([line(1), line(2), line(3)]));
    const next = advance(prev, logs(['[2026-09-18 02:00:00] [claudex] daemon restarted']));
    expect(next.reset).toBe(true);
    expect(next.appended).toEqual(['[2026-09-18 02:00:00] [claudex] daemon restarted']);
  });

  test('switching head resets even when the lines happen to match', () => {
    const prev: LogTail = tailOf(logs([line(1)], { key: 'claudex' }));
    const next = advance(prev, logs([line(1)], { key: 'claude-grok' }));
    expect(next.reset).toBe(true);
    expect(next.tail.key).toBe('claude-grok');
  });

  test('an empty first read leaves nothing to reset from', () => {
    const prev: LogTail = tailOf(logs([]));
    expect(advance(prev, logs([])).reset).toBe(false);
    expect(advance(prev, logs([line(1)])).reset).toBe(false);
    expect(advance(prev, logs([line(1)])).appended).toEqual([line(1)]);
  });
});

// ── the log filter ───────────────────────────────────────────────────────────

describe('log filter', () => {
  const error = '[2026-09-18 00:27:10] [claudex] turn ERROR conn-reset compact=false latency=900031ms';
  const ok = '[2026-09-18 01:14:01] [claudex] cache: input=182346 cached=181248 hit=99% output=252';
  const subsystem = '[2026-09-18 00:13:52] [shadow-compact] compact=false has_marker=false tool_count=177';
  const lines = [error, ok, subsystem];

  test('reads the daemon tag and only an explicitly marked severity', () => {
    expect(headOf(error)).toBe('claudex');
    expect(headOf(subsystem)).toBe('shadow-compact');
    expect(headOf('no brackets here')).toBeNull();
    expect(levelOf(error)).toBe('error');
    expect(levelOf(ok)).toBeNull(); // unmarked is null, never a guessed level
    expect(levelOf('[2026-09-18 01:00:00] [claudex] retries=3 failed=true')).toBeNull();
  });

  test('filters by tag, by level and by substring', () => {
    expect(applyFilter(lines, { ...NO_FILTER, head: 'claudex' })).toEqual([error, ok]);
    expect(applyFilter(lines, { ...NO_FILTER, level: 'error' })).toEqual([error]);
    expect(applyFilter(lines, { ...NO_FILTER, substring: 'HIT=99%' })).toEqual([ok]);
    expect(applyFilter(lines, { ...NO_FILTER, head: 'claudex', level: 'error', substring: 'conn-reset' })).toEqual([error]);
    expect(applyFilter(lines, NO_FILTER)).toEqual(lines);
    expect(applyFilter(lines, { ...NO_FILTER, substring: '   ' })).toEqual(lines);
  });

  test('offers only the tags and levels the tail actually holds', () => {
    expect(headsPresent(lines)).toEqual(['claudex', 'shadow-compact']);
    expect(levelsPresent(lines)).toEqual(['error']);
    expect(levelsPresent([ok, subsystem])).toEqual([]);
  });
});
