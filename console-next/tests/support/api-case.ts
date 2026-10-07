import { AsyncLocalStorage } from 'node:async_hooks';
import { afterEach, beforeEach, test as runTest, vi as realVi } from 'vitest';

type ApiCase = { signal: AbortSignal; work: Promise<void>; released: boolean };

/** Register a file's hooks with its own async ownership and unfinished-work state. */
export function registerApiCases() {
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

  return { test, vi, owned, scope: () => owner.getStore() ?? active ?? undefined };
}
