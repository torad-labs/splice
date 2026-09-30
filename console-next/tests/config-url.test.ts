// The successor home of the old client's config-URL guarantee (JW-06): which URL the configuration read asks the daemon for.
import { QueryClient, QueryObserver } from '@tanstack/react-query';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { configOptions } from '../src/api/queries';

/** The URL the read really fetches, through a query observer, with the network stubbed. */
async function requested(head?: string): Promise<string> {
  const urls: string[] = [];
  vi.stubGlobal('fetch', (url: string) => {
    urls.push(url);
    return Promise.resolve(new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }));
  });
  vi.stubGlobal('localStorage', undefined);
  const unsubscribe = new QueryObserver(new QueryClient(), configOptions(head)).subscribe(() => undefined);
  await vi.waitFor(() => expect(urls).toHaveLength(1));
  unsubscribe();
  return urls[0] ?? '';
}

afterEach(() => vi.unstubAllGlobals());

describe('the configuration read’s URL', () => {
  test('a selected head reaches /api/config?head=<key>', async () => {
    expect(await requested('claude-grok')).toBe('/api/config?head=claude-grok');
  });
  test('no head asks the bare /api/config, with no query', async () => {
    expect(await requested()).toBe('/api/config');
  });
  test('a key with a slash or a question mark is percent-encoded, never spliced into the path or the query', async () => {
    expect(await requested('a/b?c=d&e')).toBe('/api/config?head=a%2Fb%3Fc%3Dd%26e');
  });
});
