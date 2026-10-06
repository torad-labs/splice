// V4-444: the turns read carries the Requests filters to every head, and hands back the daemon's own count of the rows
// they match. Usage and Requests read that one count; neither rebuilds it from the clamped rows.
import { afterEach, describe, expect, test, vi } from 'vitest';
import { fetchTurns, mergeTurns, perfTurnsPath, rowFromWire } from '../src/api/turns';
import type { TurnsState, TurnUsageWire } from '../src/types/perf';

const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));

/** Two heads; `a` matched 2,362 rows and sent 1, `b` matched 3 and sent none. */
function daemon(urls: string[], countB: number | null = 3) {
  vi.stubGlobal('localStorage', undefined);
  vi.stubGlobal('fetch', (url: string) => {
    urls.push(url);
    if (url === '/api/heads') return json({ heads: [{ key: 'a', gate: null }, { key: 'b', gate: null }] });
    const head = new URL(url, 'http://x').searchParams.get('head');
    const row = { ts: 5, model: 'm', outcome: 'ok', compact: false, session: null, account: null, cache_cold: null, turn: null, session_id: null, response_message_id: null };
    const block = head === 'a'
      ? { key: 'a', label: 'a', count: 2362, returned: 1, truncated: true, rows: [row] }
      : { key: 'b', label: 'b', ...(countB === null ? {} : { count: countB }), returned: 0, truncated: false, rows: [] };
    return json({ since: 0, n: 200, heads: [block] });
  });
}

afterEach(() => vi.unstubAllGlobals());

describe('the filtered read', () => {
  test.each([undefined, null, 'synthetic-request'])('request ownership %s never creates trace availability', turn_id => {
    const wire = { ts: 100, model: 'm', outcome: 'ok', compact: false, session: null, account: null, cache_cold: null, turn: null, session_id: null, response_message_id: null, ...(turn_id === undefined ? {} : { turn_id }) };
    const row = rowFromWire('synthetic', wire);
    expect(row).not.toHaveProperty('turn');
    if (turn_id == null) expect(row).not.toHaveProperty('turn_id');
    else expect(row.turn_id).toBe(turn_id);
  });

  test('unread records remain explicit even when the available aggregates are complete', () => {
    const merged = mergeTurns([{ since: 100, n: 1, heads: [{ key: 'a', label: 'a', count: 2362, rows: [], skipped_lines: 2 }] }]);
    expect(merged.unread).toEqual([{ head: 'a', reason: '2 request records could not be read.' }]);
  });

  test('one unread request record uses singular words without hiding its count', () => {
    const merged = mergeTurns([{ since: 100, n: 1, heads: [{ key: 'synthetic', label: 'Synthetic', count: 0, rows: [], skipped_lines: 1 }] }]);
    expect(merged.unread).toEqual([{ head: 'synthetic', reason: '1 request record could not be read.' }]);
  });

  test.each([
    { cost_reason: 'future-unknown-reason' },
    { reasoning_tokens: '417' },
    { reasoning_tokens: -1 },
    { reasoning_tokens: 0.5 },
  ])('malformed row evidence is unread, not an invented thinking or plan claim: %j', async evidence => {
    vi.stubGlobal('fetch', (url: string) => {
      if (url === '/api/heads') return json({ heads: [{ key: 'synthetic', gate: null }] });
      return json({ since: 100, n: 1, heads: [{ key: 'synthetic', label: 'Synthetic', count: 1, rows: [{ ts: 100, model: 'synthetic', outcome: 'empty_message', compact: false, ...evidence }] }] });
    });
    await expect(fetchTurns({ since: 100, until: 200 })).rejects.toThrow('The daemon returned an unreadable request history.');
  });

  test('the viewer zone reaches the daemon without changing the captured interval', () => {
    const query = new URL(perfTurnsPath('a', 1, { since: 100, until: 200, timeZone: 'America/Los_Angeles', filter: { local: false } }), 'http://synthetic.invalid').searchParams;
    expect(query.get('time_zone')).toBe('America/Los_Angeles');
    expect(query.get('since')).toBe('100');
    expect(query.get('until')).toBe('200');
    expect(query.get('local')).toBe('0');
  });

  test('complete-window aggregates stay per command despite a tiny display slice', () => {
    const usage: TurnUsageWire = { totals: { requests: 2362, input_tokens: 2300000, cached_tokens: 2000000, output_tokens: 100000, cost_usd: 12, cache_share: 20 / 23, unpriced_requests: 0, missing_input_requests: 0, missing_output_requests: 0, missing_cache_requests: 0 }, models: [], accounts: [], days: [] };
    const merged = mergeTurns([{ since: 100, n: 1, heads: [{ key: 'a', label: 'a', count: 2362, rows: [], usage }] }]);
    expect(merged.usageBy).toEqual({ a: usage });
    expect(merged.landed).toEqual([]);
    expect(mergeTurns([{ since: 100, n: 1, heads: [{ key: 'a', label: 'a', count: 2362, rows: [] }] }]).usageBy).toEqual({});
  });

  test.each(['unpriced_local_requests', 'unanswered_requests'])('a malformed %s cause is an unread history, not a silent spend gap', async field => {
    const totals = { requests: 1, input_tokens: null, cached_tokens: null, output_tokens: null, cost_usd: null, cache_share: null, unpriced_requests: 1, missing_input_requests: 1, missing_output_requests: 1, missing_cache_requests: 0 };
    vi.stubGlobal('localStorage', undefined);
    vi.stubGlobal('fetch', (url: string) => {
      if (url === '/api/heads') return json({ heads: [{ key: 'readable', gate: null }, { key: 'synthetic', gate: null }] });
      const key = new URL(url, 'http://synthetic.invalid').searchParams.get('head');
      return json({ since: 100, n: 1, heads: [{ key, label: key, count: 1, rows: [], ...(key === 'synthetic' ? { usage: { totals: { ...totals, [field]: 'not a count' }, models: [], accounts: [], days: [] } } : {}) }] });
    });
    const read = await fetchTurns({ since: 0 });
    if ('pending' in read) throw new Error('not a pending route');
    expect(read.unread).toEqual([{ head: 'synthetic', reason: 'The daemon returned an unreadable request history.' }]);
    expect(read.usageBy).toEqual({});
  });

  test('every head is asked the same window and filters, a rolling window pinned to the moment of the read', async () => {
    const urls: string[] = [];
    daemon(urls);
    await fetchTurns({ last: 3_600_000, until: undefined, filter: { outcome: 'failed', model: 'gpt 6', local: false } }, () => 10_000_000);
    expect(urls.slice(1).sort()).toEqual([
      '/api/perf/turns?head=a&n=200&since=6400000&until=10000000&outcome=failed&model=gpt+6&local=0',
      '/api/perf/turns?head=b&n=200&since=6400000&until=10000000&outcome=failed&model=gpt+6&local=0',
    ]);
  });

  test('the state says the window it read: a rolling one pinned to its one instant, a span as asked, a tail none', async () => {
    daemon([]);
    let clock = 10_000_000;
    const read = await fetchTurns({ last: 3_600_000 }, () => (clock += 1));
    if ('pending' in read) throw new Error('not a pending route');
    expect(read.window).toEqual({ since: 6_400_001, until: 10_000_001 });
    const span = await fetchTurns({ since: 1000, until: 2000 });
    expect('window' in span ? span.window : null).toEqual({ since: 1000, until: 2000 });
    const open = await fetchTurns({ since: 1000 });
    expect('window' in open ? open.window : null).toEqual({ since: 1000, until: null });
    const tail = await fetchTurns({});
    expect('window' in tail).toBe(false);
  });

  test('the matching count is the daemon\'s per head and summed, however few rows came back', async () => {
    daemon([]);
    const read = await fetchTurns({ since: 0 });
    if ('pending' in read) throw new Error('not a pending route');
    expect(read.matched).toBe(2365);
    expect(read.matchedBy).toEqual({ a: 2362, b: 3 });
    expect(read.landed).toHaveLength(1);
  });

  test('a completed head publishes before a pending sibling and every snapshot keeps one window', async () => {
    let release!: (value: Response) => void;
    const slow = new Promise<Response>(resolve => { release = resolve; });
    vi.stubGlobal('localStorage', undefined);
    vi.stubGlobal('fetch', (url: string) => {
      if (url === '/api/heads') return json({ heads: [{ key: 'a', gate: null }, { key: 'b', gate: null }] });
      const key = new URL(url, 'http://synthetic.invalid').searchParams.get('head');
      return key === 'b' ? slow : json({ since: 100, n: 1, heads: [{ key: 'a', label: 'A', count: 2362, rows: [] }] });
    });
    const published: TurnsState[] = [];
    const completed = fetchTurns({ last: 3_600_000 }, () => 10_000_000, state => { published.push(state); });
    try {
      await vi.waitFor(() => expect(published.some(state => state.matchedBy.a === 2362)).toBe(true));
      const first = published.find(state => state.matchedBy.a === 2362);
      expect(first?.pendingHeads).toEqual(['b']);
      expect(first?.window).toEqual({ since: 6_400_000, until: 10_000_000 });
      expect(first?.unread).toEqual([]);
    } finally {
      release(new Response(JSON.stringify({ since: 100, n: 1, heads: [{ key: 'b', label: 'B', count: 3, rows: [] }] })));
      await completed;
    }
    expect(published.at(-1)?.matched).toBe(2365);
    expect(published.at(-1)?.pendingHeads).toEqual([]);
  });

  test('cancelling a window aborts its reads and prevents late publication', async () => {
    const controller = new AbortController();
    let release!: (response: Response) => void;
    const delayed = new Promise<Response>(resolve => { release = resolve; });
    let started!: () => void;
    const began = new Promise<void>(resolve => { started = resolve; });
    const signals: (AbortSignal | null | undefined)[] = [];
    vi.stubGlobal('localStorage', undefined);
    vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
      signals.push(init?.signal);
      if (url === '/api/heads') return json({ heads: [{ key: 'a', gate: null }] });
      started();
      return delayed;
    });
    const published: TurnsState[] = [];
    const cancelled = fetchTurns({ since: 100, until: 200 }, Date.now, state => { published.push(state); }, controller.signal);
    const rejected = expect(cancelled).rejects.toMatchObject({ name: 'AbortError' });
    await began;
    controller.abort();
    release(new Response(JSON.stringify({ since: 100, n: 1, heads: [{ key: 'a', label: 'A', count: 7, rows: [] }] })));
    await rejected;
    expect(signals).toEqual([controller.signal, controller.signal]);
    expect(published).toEqual([]);
  });

  test('a malformed successful history names that head without discarding a readable sibling', async () => {
    vi.stubGlobal('localStorage', undefined);
    vi.stubGlobal('fetch', (url: string) => {
      if (url === '/api/heads') return json({ heads: [{ key: 'a', gate: null }, { key: 'b', gate: null }] });
      const key = new URL(url, 'http://synthetic.invalid').searchParams.get('head');
      return key === 'b'
        ? Promise.resolve(new Response('not JSON', { status: 200 }))
        : json({ since: 100, n: 1, heads: [{ key: 'a', label: 'A', count: 2362, rows: [] }] });
    });
    const read = await fetchTurns({ since: 0 });
    if ('pending' in read) throw new Error('not a pending route');
    expect(read.matchedBy.a).toBe(2362);
    expect(read.unread).toEqual([{ head: 'b', reason: 'The daemon returned an unreadable request history.' }]);
  });

  test.each([
    { usage: null },
    { usage: { totals: {}, models: null } },
    { rows: [null] },
  ])('a malformed nested history is isolated to its command: %j', async invalid => {
    vi.stubGlobal('localStorage', undefined);
    vi.stubGlobal('fetch', (url: string) => {
      if (url === '/api/heads') return json({ heads: [{ key: 'a', gate: null }, { key: 'b', gate: null }] });
      const key = new URL(url, 'http://synthetic.invalid').searchParams.get('head');
      return json({ since: 100, n: 1, heads: [{ key, label: key, count: 7, rows: [], ...(key === 'b' ? invalid : {}) }] });
    });
    const read = await fetchTurns({ since: 0 });
    if ('pending' in read) throw new Error('not a pending route');
    expect(read.matchedBy).toEqual({ a: 7 });
    expect(read.unread).toEqual([{ head: 'b', reason: 'The daemon returned an unreadable request history.' }]);
  });

  test('a head that did not say its count leaves the total unknown, never short', async () => {
    daemon([], null);
    const read = await fetchTurns({ since: 0 });
    if ('pending' in read) throw new Error('not a pending route');
    expect(read.matched).toBeNull();
    expect(read.matchedBy).toEqual({ a: 2362 });
  });
});
