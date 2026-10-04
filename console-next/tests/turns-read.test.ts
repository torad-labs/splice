// V4-444: the turns read carries the Requests filters to every head, and hands back the daemon's own count of the rows
// they match. Usage and Requests read that one count; neither rebuilds it from the clamped rows.
import { afterEach, describe, expect, test, vi } from 'vitest';
import { fetchTurns, mergeTurns, perfTurnsPath } from '../src/api/turns';
import type { TurnUsageWire } from '../src/types/perf';

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
  test('unread records remain explicit even when the available aggregates are complete', () => {
    const merged = mergeTurns([{ since: 100, n: 1, heads: [{ key: 'a', label: 'a', count: 2362, rows: [], skipped_lines: 2 }] }]);
    expect(merged.unread).toEqual([{ head: 'a', reason: '2 request records could not be read.' }]);
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

  test('a head that did not say its count leaves the total unknown, never short', async () => {
    daemon([], null);
    const read = await fetchTurns({ since: 0 });
    if ('pending' in read) throw new Error('not a pending route');
    expect(read.matched).toBeNull();
    expect(read.matchedBy).toEqual({ a: 2362 });
  });
});
