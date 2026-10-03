// V4-444: the turns read carries the Requests filters to every head, and hands back the daemon's own count of the rows
// they match. Usage and Requests read that one count; neither rebuilds it from the clamped rows.
import { afterEach, describe, expect, test, vi } from 'vitest';
import { fetchTurns } from '../src/api/turns';

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
  test('every head is asked the same window and filters, a rolling window from the moment of the read', async () => {
    const urls: string[] = [];
    daemon(urls);
    await fetchTurns({ last: 3_600_000, until: undefined, filter: { outcome: 'failed', model: 'gpt 6', local: false } }, () => 10_000_000);
    expect(urls.slice(1).sort()).toEqual([
      '/api/perf/turns?head=a&n=200&since=6400000&outcome=failed&model=gpt+6&local=0',
      '/api/perf/turns?head=b&n=200&since=6400000&outcome=failed&model=gpt+6&local=0',
    ]);
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
