// The turns page's arithmetic: the waterfall, the grouping and the in-flight set. Ported from the pure blocks of
// console/tests/entities-turns.test.ts ('waterfall', 'groupTurns', 'inflightFrom').
import { describe, expect, test } from 'vitest';
import { groupTurns, inflightFrom, marksOf, UNATTRIBUTED, waterfall } from '../src/lib/perf';
import type { GateSnapshot, HeadStatus } from '../src/types/core';
import type { TurnRow } from '../src/types/perf';

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

describe('waterfall', () => {
  test('splits a turn into consecutive segments, the first from arrival', () => {
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
      ['recv', 2],
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
    expect(keys).toEqual(['recv', 'parse', 'build', 'gate', 'headers', 'first_byte', 'first_frame', 'first_delta']);
    expect(keys).not.toContain('stream_end');
    expect(keys).not.toContain('finish');
  });

  test('follows the clock, not the key order: a live row keeps its queue wait', () => {
    // A line the demo daemon wrote on 2026-09-25. Admission stamps gate as the turn opens, before
    // parse and build (AdmissionTelemetry.kt), and the first frame goes out at upstream handoff,
    // before the provider's first byte (ClientChannel.kt). Read in key order, build(18) -> gate(1)
    // ran backwards and the queue wait was dropped from every real turn.
    const row = turn({ recv: 2, parse: 11, build: 18, gate: 1, headers: 459, first_byte: 470, first_frame: 461, first_delta: 473, stream_end: 859, finish: 866 });
    const segments = waterfall(row);
    expect(segments.map((s) => [s.key, s.start, s.end])).toEqual([
      ['gate', 0, 1],
      ['recv', 1, 2],
      ['parse', 2, 11],
      ['build', 11, 18],
      ['headers', 18, 459],
      ['first_frame', 459, 461],
      ['first_byte', 461, 470],
      ['first_delta', 470, 473],
      ['stream_end', 473, 859],
      ['finish', 859, 866],
    ]);
    // contiguous, never negative, and ending where the turn did
    expect(segments.every((s) => s.ms >= 0)).toBe(true);
    expect(segments.every((s, at) => at === 0 || s.start === segments[at - 1]?.end)).toBe(true);
    expect(segments.map((s) => s.group)).toContain('queue');
  });

  test('a negative mark is a defect in the row and ends no segment', () => {
    expect(waterfall(turn({ recv: 2, parse: -5, build: 8 })).map((s) => s.key)).toEqual(['recv', 'build']);
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
      { head: 'claudex', label: 'sess-a', compact: false, phase: 'streaming', ageMs: 12_000, idleMs: 400 },
      { head: 'claudex', label: 'sess-b', compact: true, phase: 'connect', ageMs: 900, idleMs: 900 },
    ]);
  });

  test('a stopped fleet is idle, not an error', () => {
    expect(inflightFrom([head({ key: 'claudex', running: false, gate: gate() })])).toEqual([]);
  });
});
