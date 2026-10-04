// A wrong key, then the right one, without reloading: the reads the wrong key left in flight or failed must not decide what
// the right key's page shows.
import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { forgetRefusedReads } from '../src/app/forget-refused';

async function fresh() {
  vi.resetModules();
  return import('../src/api/client');
}
const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

beforeEach(() => {
  vi.stubGlobal('localStorage', undefined);
});
afterEach(() => {
  vi.unstubAllGlobals();
});

/** The page: a wrong key is stored, four reads go out, the first to answer is a 401 (which locks), and the others are still
 *  waiting on the daemon when the right key is pasted. `forget` is what the app does while it is locked. */
async function scenario(forget: boolean) {
  const { request, storeKey } = await fresh();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const waiting = new Map<string, (response: Response) => void>();
  const sent: { path: string; bearer: string }[] = [];
  vi.stubGlobal('fetch', vi.fn((path: string, init: RequestInit) => {
    sent.push({ path, bearer: (init.headers as Record<string, string>).Authorization ?? '' });
    if (path === '/api/status' && sent.at(-1)?.bearer === 'Bearer wrong') return Promise.resolve(reply(401, {}));
    if (sent.filter((each) => each.path === path).length === 1 && sent[0]?.bearer === 'Bearer wrong') {
      return new Promise<Response>((resolve) => waiting.set(path, resolve));
    }
    return Promise.resolve(reply(200, { path }));
  }));
  const read = (path: string) => client.fetchQuery({ queryKey: [path], queryFn: () => request<{ path: string }>(path), staleTime: 0 });

  storeKey('wrong');
  const inFlight = ['/api/auth', '/api/accounts', '/api/usage'].map((path) => read(path).catch(() => null));
  await read('/api/status').catch(() => null);
  if (forget) await forgetRefusedReads(client);
  await Promise.resolve();

  storeKey('right');
  const remounted = ['/api/status', '/api/auth', '/api/accounts', '/api/usage'].map((path) => read(path).catch(() => null));
  for (const resolve of waiting.values()) resolve(reply(401, {}));
  await Promise.all([...inFlight, ...remounted]);
  return { client, sent };
}

describe('unlocking after a refused key', () => {
  test('every read is answered under the new key, and none is left holding the old key\'s refusal', async () => {
    const { client, sent } = await scenario(true);
    for (const path of ['/api/status', '/api/auth', '/api/accounts', '/api/usage']) {
      expect(client.getQueryState([path])?.status, path).toBe('success');
      expect(sent.some((each) => each.path === path && each.bearer === 'Bearer right'), path).toBe(true);
    }
  });

  test('the same sequence without forgetting leaves the reads that were in flight as errors, so the gate can fail', async () => {
    const { client } = await scenario(false);
    const states = ['/api/auth', '/api/accounts', '/api/usage'].map((path) => client.getQueryState([path])?.status);
    expect(states).toContain('error');
  });
});
