// Legacy marks, grouping and the in-flight set. Measured request timing is tested in turns-page.test.ts.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, test } from 'vitest';
import { TurnPage } from '../src/pages/turns/TurnPage';
import { groupTurns, inflightFrom, marksOf, UNATTRIBUTED } from '../src/lib/perf';
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

describe('marks', () => {
  test('a row with no marks has no marks', () => {
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

// Render the actual request page with synthetic cached rows, without any HTTP reads.
describe('request token tiles', () => {
  const page = (row: TurnRow): string => {
    const client = new QueryClient();
    client.setQueryData(['perf-window', row.head, 2000, row.ts, null, row.ts + 1, {}], { landed: [row], matched: 1, truncated: [] });
    client.setQueryData(['heads', '/api/heads'], { heads: [] });
    client.setQueryData(['status', '/api/status'], { registry: [] });
    client.setQueryData(['sessions', '/api/sessions'], { sessions: [] });
    try {
      return renderToStaticMarkup(createElement(QueryClientProvider, { client },
        createElement(MemoryRouter, { initialEntries: [`/requests/${row.head}/${row.ts}`] },
          createElement(Routes, null, createElement(Route, {
            path: '/requests/:head/:ts', element: createElement(TurnPage),
          })))));
    } finally {
      client.clear();
    }
  };

  test('a posted row without usage keeps both tiles and explains the unknown price', () => {
    const shown = page(turn({ upstream_req_bytes: 320, cost_usd: null }));
    expect(shown).toContain('<div class="n">Not reported</div><h3>Read in</h3>');
    expect(shown).toContain('<div class="n">Not reported</div><h3>Written out</h3>');
    expect(shown).toContain('The price is unknown because token counts were not reported.');
    expect(shown).not.toContain('This step sent no request to the model.');
  });

  test('a posted row with measured counts retains its numbers and price explanation', () => {
    const shown = page(turn({ upstream_req_bytes: 320, in_tokens: 100, out_tokens: 7, cost_usd: 0.002 }));
    expect(shown).toContain('<div class="n">100</div><h3>Read in</h3>');
    expect(shown).toContain('<div class="n">7</div><h3>Written out</h3>');
    expect(shown).toContain('Estimated at this command’s declared token prices.');
    expect(shown).not.toContain('public prices');
    expect(shown).not.toContain('Not reported');
  });

  test('a plan-covered request uses the exact Usage sentence instead of an unknown price', () => {
    const shown = page(turn({ upstream_req_bytes: 320, in_tokens: 100, out_tokens: 417, cost_usd: null, cost_reason: 'plan', reasoning_tokens: 417 }));
    expect(shown).toContain('1 request is covered by a plan, so it has no price.');
    expect(shown).not.toContain('This request was not priced.');
    expect(shown).not.toContain('The price is unknown because token counts were not reported.');
    expect(shown).toContain('<div class="n">417</div><h3>Written out</h3>');
    expect(shown).not.toContain('<div class="n">834</div>');
    expect(shown).toContain('417 of the written tokens were reported as thinking.');
  });

  test('a true local-only step keeps zero counts and its no-request explanation', () => {
    const shown = page(turn({ local_step: 1, upstream_req_bytes: 0, in_tokens: 0, out_tokens: 0 }));
    expect(shown).toContain('<div class="n">0</div><h3>Read in</h3>');
    expect(shown).toContain('<div class="n">0</div><h3>Written out</h3>');
    expect(shown).toContain('This step sent no request to the model.');
    expect(shown).not.toContain('Not reported');
    expect(shown).not.toContain('The price is unknown because token counts were not reported.');
  });

  test.each([{ in_tokens: 100 }, { out_tokens: 7 }])('a partial bill keeps the known count and marks only the missing one: %j', counts => {
    const shown = page(turn({ upstream_req_bytes: 320, ...counts }));
    expect(shown.match(/<div class="n">Not reported<\/div>/g)).toHaveLength(1);
    expect(shown).toContain('The price is unknown because token counts were not reported.');
  });

  test('a row with no upstream post does not claim that model token counts were missing', () => {
    const shown = page(turn({ outcome: 'error:admission', upstream_req_bytes: 0 }));
    expect(shown).not.toContain('<h3>Read in</h3>');
    expect(shown).not.toContain('<h3>Written out</h3>');
    expect(shown).not.toContain('Not reported');
    expect(shown).toContain('This request was not priced.');
  });

  test('measured zero on a posted row is not missing usage', () => {
    const shown = page(turn({ upstream_req_bytes: 320, in_tokens: 0, out_tokens: 0, cost_usd: 0 }));
    expect(shown).toContain('<div class="n">0</div><h3>Read in</h3>');
    expect(shown).toContain('<div class="n">0</div><h3>Written out</h3>');
    expect(shown).not.toContain('Not reported');
    expect(shown).not.toContain('This step sent no request to the model.');
  });
});
