// The console with the daemon gone (S7 of the 2026-09-24 walkthrough). Three things were wrong on
// every page, and each is pinned here at the layer that owns it:
//   - the TRANSPORT: a request that got no response surfaced the browser's own "Failed to fetch",
//     which every page printed verbatim. The shared client now says one plain sentence;
//   - the ROWS: a failed read keeps what the last good read returned (a blank page tells the
//     operator less than old rows), but those rows kept every `ok` edge they were read with. A Fault
//     handed the store's `lastUpdated` now prints their age on the `stale` basis;
//   - the PAGES that hid the fault once anything had loaded (turns, named in the walkthrough, and
//     sessions, projects, logs and teams by the same `error !== null && data === null` gate), and the
//     rule, whose one status read at mount kept printing `daemon degraded` for a dead daemon.
// Boards are rendered from props (a static render only ever sees a store's initial state); the
// client and the stores are driven through a stubbed fetch, so the real mapping is what is proven.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';
import type { TurnRow } from '../src/entities/perf';

const fetchMock = vi.fn();
vi.stubGlobal('fetch', fetchMock);

const { MgmtError, request, storeKey } = await import('../src/shared/api');
const { fetchHeads } = await import('../src/entities/heads');
const { headsStore } = await import('../src/entities/heads/model/store');
const { startControlStatusPolling } = await import('../src/entities/control-status');
const { controlStatusStore } = await import('../src/entities/control-status/model/store');
const { Fault } = await import('../src/shared/controls');
const { TurnsBoard } = await import('../src/pages/turns');
const { SessionsBoard } = await import('../src/pages/sessions');
const { ProjectsBoard } = await import('../src/pages/projects');
const { LogsBoard } = await import('../src/pages/logs');
const { teamsBodyFor } = await import('../src/pages/teams');
const { sampleBoard } = await import('../src/pages/teams/fixtures/hero');
const { healthOf } = await import('../src/widgets/rule');

const h = React.createElement;
const render = (el: React.ReactElement): string => renderToStaticMarkup(el);

const DOWN = 'Splice is not answering.';
/** What a Figure prints for a stale basis: the word in its own basis span. */
const STALE_MARK = '<span class="myx-fig-basis">Stale</span>';

/** What a browser's fetch throws for a refused connection (Chrome's words). */
const refused = () => new TypeError('Failed to fetch');
const ok = (body: unknown) => ({ ok: true, status: 200, json: () => Promise.resolve(body) });

const turn: TurnRow = {
  head: 'claudex', ts: 1_700_000_000_000, model: 'gpt-5.2-codex', outcome: 'ok', compact: false,
  recv: 2, parse: 8, build: 10, gate: 40, headers: 300, first_byte: 320, first_frame: 322,
  first_delta: 900, stream_end: 4000, finish: 4010, total: 4010,
};

afterEach(() => {
  fetchMock.mockReset();
  vi.useRealTimers();
});

describe('the shared client', () => {
  test('a daemon that does not answer is one plain sentence, never the browser\'s words', async () => {
    storeKey('k');
    fetchMock.mockRejectedValueOnce(refused());
    const err = await request('/api/heads').catch((caught: unknown) => caught);
    expect(err).toBeInstanceOf(MgmtError);
    expect((err as InstanceType<typeof MgmtError>).message).toBe(DOWN);
    expect((err as InstanceType<typeof MgmtError>).status).toBe(0);
  });

  test('a key a header cannot carry reopens the gate, and is never read as the daemon being down', async () => {
    // a zero-width space and a smart quote, as a paste from rich text brings them
    storeKey('k\u200bey\u201d');
    const err = await request('/api/heads').catch((caught: unknown) => caught);
    expect(fetchMock, 'nothing was sent').not.toHaveBeenCalled();
    expect((err as InstanceType<typeof MgmtError>).status).toBe(401);
    expect((err as InstanceType<typeof MgmtError>).message).not.toBe(DOWN);
    storeKey('k');
  });

  test('a request its caller aborted stays an abort', async () => {
    storeKey('k');
    const controller = new AbortController();
    controller.abort();
    const abort = new DOMException('The operation was aborted.', 'AbortError');
    fetchMock.mockRejectedValueOnce(abort);
    await expect(request('/api/heads', { signal: controller.signal })).rejects.toBe(abort);
  });

  test('a poll that fails after one landed keeps its rows and when they were read', async () => {
    storeKey('k');
    fetchMock.mockResolvedValueOnce(ok({ heads: [{ key: 'claudex' }] }));
    await fetchHeads();
    const read = headsStore.get().lastUpdated;
    expect(read).not.toBeNull();

    fetchMock.mockRejectedValueOnce(refused());
    await fetchHeads();
    expect(headsStore.get()).toMatchObject({ error: DOWN, data: [{ key: 'claudex' }], lastUpdated: read });
  });
});

describe('the fault marks held rows stale', () => {
  test('with the time of the last good read, the fault prints its age on the stale basis', () => {
    const out = render(h(Fault, { message: DOWN, lastRead: Date.now() - 42_000 }));
    expect(out).toContain(DOWN);
    expect(out).toContain('>Last read<');
    expect(out).toContain('42s ago');
    expect(out).toContain(STALE_MARK);
    expect(out).toContain(`aria-label="${DOWN}, Last read 42s ago, stale"`);
  });

  test('with nothing held, the fault says only what failed', () => {
    const out = render(h(Fault, { message: DOWN }));
    expect(out).not.toContain('Last read');
    expect(out).not.toContain(STALE_MARK);
  });
});

describe('a page keeps its rows, shows the fault and marks the rows stale', () => {
  const lastRead = Date.now() - 30_000;

  test('turns, which printed no fault at all once it had loaded', () => {
    const out = render(h(TurnsBoard, {
      inflight: [],
      landed: { inflight: [], landed: [turn], unread: [] },
      summary: null,
      capture: null,
      error: DOWN,
      lastRead,
    }));
    expect(out).toContain(DOWN);
    expect(out).toContain(STALE_MARK);
    expect(out, 'the rows it held are still drawn').toContain('myx-tn-table');
  });

  test('sessions, projects, logs and teams, which hid it by the same gate', () => {
    const session = {
      pid: 100, session_id: 'sid-held', name: 'held-session', kind: 'interactive', version: '2.1.257',
      cwd: '/home/user/dev/atlas', status: 'busy', status_updated_at: 0, started_at: 0, updated_at: 0,
      address: null, head: 'claudex', availability: 'live',
    } as const;
    const boards = {
      sessions: render(h(SessionsBoard, { payload: { note: '', sessions: [session] }, error: DOWN, lastRead })),
      projects: render(h(ProjectsBoard, { payload: { projects: [] }, error: DOWN, lastRead })),
      logs: render(h(LogsBoard, {
        payload: { key: 'claudex', path: '/home/user/.splice/logs/daemon.log', lines: ['held line'] },
        filter: { head: null, level: null, substring: '' },
        follow: true, appended: 0, reset: false, tags: [], levels: [], head: 'claudex', tail: 200,
        heads: [{ key: 'claudex', label: 'claudex' }],
        error: DOWN,
        lastRead,
      })),
      teams: render(teamsBodyFor({ view: { layout: 'table', group: 'head' }, teams: { teams: [] }, board: sampleBoard, error: DOWN, lastRead })),
    };
    for (const [page, out] of Object.entries(boards)) {
      expect(out, page).toContain(DOWN);
      expect(out, page).toContain(STALE_MARK);
    }
    expect(boards.sessions, 'the held session is still drawn').toContain('held-session');
    expect(boards.teams, 'the held board is still drawn').toContain('storefront-api');
  });
});

describe('the rule', () => {
  test('re-reads the status, so a daemon that dies after the console loaded reads unreachable', async () => {
    vi.useFakeTimers();
    storeKey('k');
    fetchMock.mockResolvedValueOnce(ok({ server: 'control', version: '1', heads: [], registry: [] }));
    const stop = startControlStatusPolling(10_000);
    await vi.advanceTimersByTimeAsync(0);
    expect(healthOf(controlStatusStore.get().error !== null, false, false)).toBe('green');

    fetchMock.mockRejectedValue(refused());
    await vi.advanceTimersByTimeAsync(10_000);
    expect(controlStatusStore.get().error).toBe(DOWN);
    expect(healthOf(controlStatusStore.get().error !== null, false, false)).toBe('red');
    stop();
  });
});
