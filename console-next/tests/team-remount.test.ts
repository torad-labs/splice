// A team page left and re-entered within the app's 5 s staleTime must read today's chat and activity again at once:
// the cached answer may be from before the hand-off landed. An older day stays a single read.
import { QueryClient, QueryObserver } from '@tanstack/react-query';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { teamActivityOptions, teamChatOptions } from '../src/api/teams';

const DAY = { from: 1, to: 2 } as never;

async function mountReads(options: (live: boolean) => ReturnType<typeof teamChatOptions>, live: boolean): Promise<number> {
  const fetched = vi.fn(() => Promise.resolve(new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } })));
  vi.stubGlobal('fetch', fetched);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 5_000 } } });
  const { queryKey } = options(live);
  client.setQueryData(queryKey, { messages: [] });
  const unsubscribe = new QueryObserver(client, options(live)).subscribe(() => undefined);
  await new Promise((resolve) => setTimeout(resolve, 20));
  unsubscribe();
  return fetched.mock.calls.length;
}

afterEach(() => vi.unstubAllGlobals());

describe('re-entering a team page', () => {
  test('today’s chat and activity are read again on mount even when the cache is fresh', async () => {
    expect(await mountReads((live) => teamChatOptions('t', DAY, live), true)).toBe(1);
    expect(await mountReads((live) => teamActivityOptions('t', DAY, live) as never, true)).toBe(1);
  });
  test('an older day stays one read: a fresh cache is not read again', async () => {
    expect(await mountReads((live) => teamChatOptions('t', DAY, live), false)).toBe(0);
    expect(await mountReads((live) => teamActivityOptions('t', DAY, live) as never, false)).toBe(0);
  });
});
