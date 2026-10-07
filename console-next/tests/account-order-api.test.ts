import { afterEach, beforeEach, describe, expect, test as runTest, vi } from 'vitest';

let active: { signal: AbortSignal; work: Promise<void> } | null = null;

/** Vitest ends its timeout race before the body settles; keep that body's globals until it finishes. */
function test(name: string, body: () => Promise<void>, timeout?: number): void {
  runTest(name, ({ signal }) => {
    const scope = { signal, work: Promise.resolve().then(body) };
    active = scope;
    return scope.work;
  }, timeout);
}

const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
async function fresh() {
  const signal = active?.signal;
  signal?.throwIfAborted();
  vi.resetModules();
  const client = await import('../src/api/client');
  signal?.throwIfAborted();
  client.storeKey('synthetic');
  const api = await import('../src/api/account-order');
  signal?.throwIfAborted();
  return api;
}
beforeEach(({ signal }) => {
  active = { signal, work: Promise.resolve() };
  vi.stubGlobal('localStorage', undefined);
});
afterEach(async () => {
  const scope = active;
  try {
    await scope?.work.catch(() => undefined);
  } finally {
    if (active === scope) {
      active = null;
      vi.unstubAllGlobals();
    }
  }
});

describe('persisted account order', () => {
  test('a head stays inside its URL segment and account labels reach the backend verbatim', async () => {
    const api = await fresh();
    const order = ['work/account', 'spare account'];
    const answer = { head: 'head/one', order, effective_order: [...order].reverse() };
    const fetchMock = vi.fn(async () => reply(200, answer));
    vi.stubGlobal('fetch', fetchMock);
    expect(await api.saveAccountOrder('head/one', order)).toEqual(answer);
    const [path, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(path).toBe('/api/auth/head%2Fone/order');
    expect(init.body).toBe(JSON.stringify({ order }));
  });

  test('a single-login source is unavailable for reordering without disappearing as an account', async () => {
    const api = await fresh();
    vi.stubGlobal('fetch', vi.fn(async () => reply(409, { error: 'not a selectable account source' })));
    expect(await api.readAccountOrder('single')).toEqual({ unavailable: 'not a selectable account source' });
  });

  test('a route not installed yet is distinguishable from an unknown head', async () => {
    const api = await fresh();
    vi.stubGlobal('fetch', vi.fn(async () => reply(404, { error: 'not found' })));
    expect(await api.readAccountOrder('head')).toEqual({ unavailable: 'not found' });
    vi.stubGlobal('fetch', vi.fn(async () => reply(404, { error: 'unknown head' })));
    await expect(api.readAccountOrder('unknown')).rejects.toThrow('unknown head');
  });

  test('a rejected duplicate order cannot be reported as saved', async () => {
    const api = await fresh();
    vi.stubGlobal('fetch', vi.fn(async () => reply(400, { error: 'duplicate account' })));
    await expect(api.saveAccountOrder('head', ['same', 'same'])).rejects.toThrow('duplicate account');
  });
});
