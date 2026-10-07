// The auth api's logic that is not a hook: the paths (every segment the operator chose is URL-encoded),
// the pending-route rule (404 = the daemon does not serve it), and each write's body and method, against a
// stubbed fetch. The client keeps module state, so each test loads a fresh copy of both modules.
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
  client.storeKey('k');
  const auth = await import('../src/api/auth');
  signal?.throwIfAborted();
  return { client: owned(client, scope), auth: owned(auth, scope) };
}

const started = { id: 'L1', head: 'claudex', state: 'starting', user_code: null, verification_uri: null, browser_url: null, failure_reason: null, label: null, usage_set_aside: null };
const sent = (fetchMock: ReturnType<typeof vi.fn>): [string, RequestInit] => fetchMock.mock.calls[0] as unknown as [string, RequestInit];

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

describe('an account edit', () => {
  test('colliding native and pool ids send their own explicit location on Remove and Rename', async () => {
    const { auth } = await fresh();
    const fetchMock = vi.fn(async () => reply(200, { ok: true }));
    vi.stubGlobal('fetch', fetchMock);
    for (const kind of ['native', 'pool'] as const) {
      const target = { kind, id: 'claude' };
      await auth.removeAccount('claude-splice', target);
      await auth.relabelAccount('claude-splice', target, 'New display name');
    }
    const calls = fetchMock.mock.calls as unknown as [string, RequestInit][];
    expect(calls.map(([path, init]) => {
      const url = new URL(path, 'http://synthetic.invalid');
      return [init.method, url.pathname, url.searchParams.get('target_kind'), url.searchParams.get('target_id')];
    })).toEqual([
      ['DELETE', '/api/auth/claude-splice/accounts/claude', 'native', 'claude'],
      ['PATCH', '/api/auth/claude-splice/accounts/claude', 'native', 'claude'],
      ['DELETE', '/api/auth/claude-splice/accounts/claude', 'pool', 'claude'],
      ['PATCH', '/api/auth/claude-splice/accounts/claude', 'pool', 'claude'],
    ]);
    expect(calls.filter(([, init]) => init.method === 'PATCH').map(([, init]) => init.body)).toEqual([
      JSON.stringify({ label: 'New display name' }), JSON.stringify({ label: 'New display name' }),
    ]);
  });
  test('names the head the pool rides, and an unknown head is an error, never a route the daemon does not serve', async () => {
    const { auth } = await fresh();
    const fetchMock = vi.fn(async () => reply(200, { ok: true }));
    vi.stubGlobal('fetch', fetchMock);
    await auth.removeAccount('claudex', { kind: 'pool', id: 'work 2' });
    await auth.relabelAccount('claudex', { kind: 'pool', id: 'work 2' }, 'spare');
    expect((fetchMock.mock.calls as unknown as [string, RequestInit][]).map(([path, init]) => `${init.method} ${path}`)).toEqual([
      'DELETE /api/auth/claudex/accounts/work%202?target_kind=pool&target_id=work+2', 'PATCH /api/auth/claudex/accounts/work%202?target_kind=pool&target_id=work+2',
    ]);
    vi.stubGlobal('fetch', vi.fn(async () => reply(404, { error: 'unknown head' })));
    await expect(auth.removeAccount('chatgpt-oauth', { kind: 'pool', id: 'work' })).rejects.toMatchObject({ status: 404, message: 'unknown head' });
  });
});

describe('separate Claude login places', () => {
  test('each login and refresh targets only its own folder, while polling stays on the managed head', async () => {
    const { auth } = await fresh();
    const fetchMock = vi.fn(async () => reply(200, { ...started, head: 'claude-splice' }));
    vi.stubGlobal('fetch', fetchMock);
    await auth.startLogin('claude-splice', 'native', 'claude');
    await auth.startLogin('claude-splice', 'proxied', 'claude-splice');
    await auth.refreshClaudeLogin('claude');
    await auth.refreshClaudeLogin('claude-splice');
    await auth.fetchLoginStatus('claude-splice', 'L1');
    expect((fetchMock.mock.calls as unknown as [string, RequestInit][]).map(([path, init]) => `${init?.method ?? 'GET'} ${path}`)).toEqual([
      'POST /api/claude-logins/claude/login',
      'POST /api/claude-logins/claude-splice/login',
      'POST /api/claude-logins/claude/refresh',
      'POST /api/claude-logins/claude-splice/refresh',
      'GET /api/auth/claude-splice/login/L1',
    ]);
  });

  test('a refused place refresh stays a refusal rather than looking signed in', async () => {
    const { auth } = await fresh();
    vi.stubGlobal('fetch', vi.fn(async () => reply(409, { error: 'no credential in this place' })));
    await expect(auth.refreshClaudeLogin('claude')).rejects.toThrow('no credential in this place');
  });
});

describe('the paths', () => {
  test('a head, id, kind, label or key name with a slash or a space cannot leave its segment', async () => {
    const { auth } = await fresh();
    expect(auth.loginPath('claude x')).toBe('/api/auth/claude%20x/login');
    expect(auth.loginStatusPath('a/b', '../c?d')).toBe('/api/auth/a%2Fb/login/..%2Fc%3Fd');
    expect(auth.refreshPath('a/b')).toBe('/api/auth/a%2Fb/refresh');
    expect(auth.switchPath('a/b')).toBe('/api/auth/a%2Fb/switch');
    expect(auth.accountPath('claude', { kind: 'pool', id: 'work 2/x' })).toBe('/api/auth/claude/accounts/work%202%2Fx?target_kind=pool&target_id=work+2%2Fx');
    expect(auth.keyPath('A/B')).toBe('/api/keys/A%2FB');
  });
});

describe('the pending-route rule', () => {
  test('a 404, or the daemon naming an unknown route, is pending; any other failure is not', async () => {
    const { client, auth } = await fresh();
    expect(auth.pendingOf(new client.MgmtError(404, 'HTTP 404'), 'V4-132')).toEqual({ pending: 'V4-132' });
    // a 404 the daemon's own handler wrote (`{"error": "unknown head"}`) is a refusal about a thing, not a route that is not served
    expect(auth.pendingOf(new client.MgmtError(404, 'unknown head', { error: 'unknown head' }), 'V4-132')).toBeNull();
    expect(auth.pendingOf(new client.MgmtError(400, 'Unknown route /api/x'), 'V4-132')).toEqual({ pending: 'V4-132' });
    expect(auth.pendingOf(new client.MgmtError(409, 'a login is already running'), 'V4-132')).toBeNull();
    expect(auth.pendingOf(new client.MgmtError(0, client.NOT_ANSWERING), 'V4-132')).toBeNull();
    expect(auth.pendingOf(new Error('boom'), 'V4-132')).toBeNull();
  });
});

describe('the login writes', () => {
  test('start posts the label to the head and answers the login view tagged as a login', async () => {
    const { auth } = await fresh();
    const fetchMock = vi.fn(async () => reply(200, started));
    vi.stubGlobal('fetch', fetchMock);
    const outcome = await auth.startLogin('claudex', 'work');
    const [path, init] = sent(fetchMock);
    expect(path).toBe('/api/auth/claudex/login');
    expect(init.method).toBe('POST');
    expect(init.body).toBe(JSON.stringify({ label: 'work' }));
    expect(outcome).toEqual({ action: 'login', result: started });
  });

  test('a daemon that does not serve the login route answers pending for the start and the poll', async () => {
    const { auth } = await fresh();
    vi.stubGlobal('fetch', vi.fn(async () => reply(404, { error: 'not found' })));
    expect(await auth.startLogin('claudex', 'work')).toEqual({ pending: 'V4-132' });
    expect(await auth.fetchLoginStatus('claudex', 'L1')).toEqual({ pending: 'V4-132' });
  });

  test("a refusal rejects with the daemon's own sentence, not a pending row", async () => {
    const { auth } = await fresh();
    vi.stubGlobal('fetch', vi.fn(async () => reply(409, { error: 'a login is already running for claudex' })));
    await expect(auth.startLogin('claudex', 'work')).rejects.toThrow('a login is already running for claudex');
  });

  test('the poll reads the login by the id the start answered with', async () => {
    const { auth } = await fresh();
    const fetchMock = vi.fn(async () => reply(200, { ...started, state: 'waiting', user_code: 'AB-12' }));
    vi.stubGlobal('fetch', fetchMock);
    const status = await auth.fetchLoginStatus('claudex', 'L1');
    expect(sent(fetchMock)[0]).toBe('/api/auth/claudex/login/L1');
    expect(auth.isPendingRoute(status)).toBe(false);
    expect(status).toMatchObject({ state: 'waiting', user_code: 'AB-12' });
  });

  test('unpin on a daemon older than the route (405) is pending, like a 404', async () => {
    const { auth } = await fresh();
    vi.stubGlobal('fetch', vi.fn(async () => reply(405, { error: 'method not allowed' })));
    expect(await auth.unpinAccount('claudex')).toEqual({ pending: 'V4-132' });
  });

  test('refresh is not pending: the route predates the rebuild, so a 404 rejects', async () => {
    const { auth } = await fresh();
    vi.stubGlobal('fetch', vi.fn(async () => reply(404, { error: 'unknown head' })));
    await expect(auth.refreshLogin('nope')).rejects.toThrow('unknown head');
  });

  test('a key write carries the value as the body only, to an encoded name', async () => {
    const { auth } = await fresh();
    const fetchMock = vi.fn(async () => reply(200, { name: 'A B', stored: true, heads: [] }));
    vi.stubGlobal('fetch', fetchMock);
    await auth.putKey('A B', 'sk-secret');
    const [path, init] = sent(fetchMock);
    expect(path).toBe('/api/keys/A%20B');
    expect(init.method).toBe('PUT');
    expect(init.body).toBe(JSON.stringify({ value: 'sk-secret' }));
  });
});
