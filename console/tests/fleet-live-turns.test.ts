// V4-319: the opened head's live turns and the operator's stop. What is pinned: each turn prints its
// session, model and age with a stop that arms before it fires; a turn already stopped says so
// instead of offering the key again; the reads name the head and the turn, and a refusal (a turn that
// ended) arrives in the daemon's words; the detail panel carries the section for a running head only;
// and the two routes carry this page's dispositions, the stop as its one editable action.
//
// A `.ts` test cannot hold JSX (TS1161), so elements are built with React.createElement and
// asserted against renderToStaticMarkup's string.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { fetchLiveTurns, stopTurn } from '../src/entities/heads';
import type { LiveTurn, LiveTurnsPayload } from '../src/entities/heads';
import { FleetBoard } from '../src/pages/fleet';
import type { FleetSources } from '../src/pages/fleet';
import { dispositions, job } from '../src/pages/fleet/coverage';
import { LiveTurnsView, sessionText } from '../src/pages/fleet/live-turns';
import type { HeadStatus } from '../src/shared/api';

const h = React.createElement;
const render = (el: React.ReactElement): string => renderToStaticMarkup(el);

/** The stop key itself, not the column's header that shares its word. */
const STOP_KEY = 'myx-key-label">Stop<';

function liveTurn(over: Partial<LiveTurn> = {}): LiveTurn {
  return {
    id: '0b6f9c4e-2d1a-4c8e-9f3a-7e5d1c2b4a60',
    session: 'a1b2c3d4-e5f6-4789-abcd-ef0123456789',
    model: 'gpt-5.6-sol',
    compact: false,
    age_ms: 4_200,
    stopped: false,
    ...over,
  };
}

function payload(turns: LiveTurn[]): LiveTurnsPayload {
  return { head: 'codex', turns };
}

function head(over: Partial<HeadStatus> = {}): HeadStatus {
  return {
    key: 'codex',
    label: 'claudex',
    name: 'claudex',
    port: 3099,
    authKind: 'chatgpt-oauth',
    wantVersion: '0.4.0',
    running: true,
    healthy: true,
    version: '0.4.0',
    versionMatch: true,
    mode: null,
    gate: { inflight: 1, queued: 0, max: 4, acquired: 1, released: 0, waited: 0, avg_wait_ms: 0, live: [], stream_idle_ms: 30000 },
    maxInflight: 4,
    health: { localOriginErrors: 0, providerErrors: 0 },
    pids: [1],
    ...over,
  };
}

const NO_SOURCES: FleetSources = {
  auth: null,
  usage: null,
  accounts: null,
  topology: null,
  catalogs: null,
  fieldsPending: false,
  topologyStale: false,
  landed: [],
  lastTs: new Map(),
  overrides: [],
};

describe('the opened head lists its live turns and stops one (V4-319)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  test('a turn prints its session, its model and its age, with the stop key', () => {
    const html = render(h(LiveTurnsView, { payload: payload([liveTurn()]) }));
    expect(html).toContain('a1b2c3d4<');
    expect(html).not.toContain('a1b2c3d4-e5f6');
    expect(html).toContain('gpt-5.6-sol');
    expect(html).toContain('4.2s');
    expect(html).toContain(STOP_KEY);
    expect(html).not.toContain('Compact');
    expect(html).not.toContain('Stopped');
  });

  test('a compaction is marked, a turn with no session says so, and a stopped turn offers no second stop', () => {
    const html = render(h(LiveTurnsView, {
      payload: payload([liveTurn({ compact: true }), liveTurn({ id: 'two', session: null, stopped: true })]),
    }));
    expect(html).toContain('Compact');
    expect(html).toContain('No session');
    expect(html).toContain('Stopped');
    expect(html.split(STOP_KEY).length - 1).toBe(1);
    expect(sessionText(liveTurn({ session: null }))).toBe('No session');
  });

  test('a head with no live turn says so', () => {
    expect(render(h(LiveTurnsView, { payload: payload([]) }))).toContain('No live turns');
  });

  test('the reads name the head and the turn, and a turn that ended is refused in the daemon\'s words', async () => {
    const asked: { url: string; method: string }[] = [];
    const ended = 'no live turn t 1 on head codex: it has ended';
    vi.stubGlobal('fetch', (input: unknown, init?: RequestInit): Promise<Response> => {
      asked.push({ url: String(input), method: init?.method ?? 'GET' });
      const body = asked.length === 1
        ? payload([liveTurn()])
        : asked.length === 2 ? { stopped: true, head: 'codex', session: 'a1b2' } : { error: ended };
      return Promise.resolve(new Response(JSON.stringify(body), { status: asked.length === 3 ? 404 : 200 }));
    });
    expect(await fetchLiveTurns('my head')).toEqual(payload([liveTurn()]));
    expect(await stopTurn('codex', 't 1')).toEqual({ stopped: true, head: 'codex', session: 'a1b2' });
    await expect(stopTurn('codex', 't 1')).rejects.toThrow(ended);
    expect(asked).toEqual([
      { url: '/api/heads/my%20head/turns/live', method: 'GET' },
      { url: '/api/heads/codex/turns/t%201/stop', method: 'POST' },
      { url: '/api/heads/codex/turns/t%201/stop', method: 'POST' },
    ]);
  });

  test('a running head\'s detail carries the section, and a stopped head\'s does not', () => {
    const running = render(h(FleetBoard, { heads: [head()], sources: NO_SOURCES, openKey: 'codex', onOpen: () => undefined, nowMs: 0 }));
    expect(running).toContain('Live turns');
    const down = render(h(FleetBoard, { heads: [head({ running: false })], sources: NO_SOURCES, openKey: 'codex', onOpen: () => undefined, nowMs: 0 }));
    expect(down).not.toContain('Live turns');
  });

  test('the list is read-only, the stop is the editable action, and the job names it', () => {
    const named = new Map(dispositions.map((row) => [row.name, row.disposition]));
    expect(named.get('/api/heads/{head}/turns/live')).toBe('read-only');
    expect(named.get('/api/heads/{head}/turns/{id}/stop')).toBe('editable');
    expect(job.actions.map((action) => action.name)).toContain('Stop a live turn');
  });
});
