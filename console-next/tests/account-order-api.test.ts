import { AsyncLocalStorage } from 'node:async_hooks';
import { afterEach, beforeEach, describe, expect, test as runTest, vi as realVi } from 'vitest';

type ApiCase = { signal: AbortSignal; work: Promise<void>; released: boolean };
const owner = new AsyncLocalStorage<ApiCase>();
const unfinished = new Set<ApiCase>();
let active: ApiCase | null = null;

function assertOpen(scope = owner.getStore()): void {
  scope?.signal.throwIfAborted();
  if (scope?.released) throw new Error('This API test case has finished.');
}

/** A late body cannot replace another case's mocks, even after the teardown bound. */
const vi = new Proxy(realVi, {
  get(target, key, receiver) {
    const value = Reflect.get(target, key, receiver);
    if (key !== 'stubGlobal' && key !== 'unstubAllGlobals') return value;
    return new Proxy(value, {
      apply(fn, self, args) {
        assertOpen();
        return Reflect.apply(fn, self, args);
      },
    });
  },
});

/** Bind imported operations to the case that obtained them, not the next case's globals. */
function owned<T extends object>(api: T, scope: ApiCase | undefined): T {
  return new Proxy(api, {
    get(target, key, receiver) {
      const value = Reflect.get(target, key, receiver);
      if (typeof value !== 'function') return value;
      return new Proxy(value, {
        apply(fn, self, args) {
          assertOpen(scope);
          return Reflect.apply(fn, self, args);
        },
        construct(fn, args, parent) {
          assertOpen(scope);
          return Reflect.construct(fn, args, parent);
        },
      });
    },
  });
}

function test(name: string, body: () => Promise<void>, timeout?: number): void {
  runTest(name, ({ signal }) => {
    const scope: ApiCase = { signal, work: Promise.resolve(), released: false };
    active = scope;
    unfinished.add(scope);
    scope.work = owner.run(scope, () => Promise.resolve().then(body));
    const settled = (): void => { unfinished.delete(scope); };
    void scope.work.then(settled, settled);
    return scope.work;
  }, timeout);
}

const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
async function fresh() {
  const scope = owner.getStore() ?? active ?? undefined;
  const signal = scope?.signal;
  signal?.throwIfAborted();
  vi.resetModules();
  const client = await import('../src/api/client');
  signal?.throwIfAborted();
  client.storeKey('synthetic');
  const api = await import('../src/api/account-order');
  signal?.throwIfAborted();
  return owned(api, scope);
}
beforeEach(({ signal }) => {
  active = { signal, work: Promise.resolve(), released: false };
  vi.stubGlobal('localStorage', undefined);
});
afterEach(async () => {
  const scope = active;
  try {
    // One microtask checkpoint drains settled work, never a pending body or hookTimeout.
    await Promise.race([scope?.work.catch(() => undefined), Promise.resolve()]);
  } finally {
    if (scope !== null) scope.released = true;
    if (active === scope) {
      active = null;
      realVi.unstubAllGlobals();
      if (unfinished.size > 0) {
        realVi.stubGlobal('fetch', () => Promise.reject(new Error('An API test case still has unfinished work.')));
      }
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
