// The auth api's logic that is not a hook: the paths (every segment the operator chose is URL-encoded),
// the pending-route rule (404 = the daemon does not serve it), and each write's body and method, against a
// stubbed fetch. The client keeps module state, so each test loads a fresh copy of both modules.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

async function fresh() {
  vi.resetModules();
  const client = await import('../src/api/client');
  client.storeKey('k');
  return { client, auth: await import('../src/api/auth') };
}

const started = { id: 'L1', head: 'claudex', state: 'starting', user_code: null, verification_uri: null, browser_url: null, failure_reason: null, label: null, usage_set_aside: null };
const sent = (fetchMock: ReturnType<typeof vi.fn>): [string, RequestInit] => fetchMock.mock.calls[0] as unknown as [string, RequestInit];

beforeEach(() => {
  vi.stubGlobal('localStorage', undefined);
});
afterEach(() => {
  vi.unstubAllGlobals();
});

describe('an account edit', () => {
  test('names the head the pool rides, and an unknown head is an error, never a route the daemon does not serve', async () => {
    const { auth } = await fresh();
    const fetchMock = vi.fn(async () => reply(200, { ok: true }));
    vi.stubGlobal('fetch', fetchMock);
    await auth.removeAccount('claudex', 'work 2');
    await auth.relabelAccount('claudex', 'work 2', 'spare');
    expect((fetchMock.mock.calls as unknown as [string, RequestInit][]).map(([path, init]) => `${init.method} ${path}`)).toEqual([
      'DELETE /api/auth/claudex/accounts/work%202', 'PATCH /api/auth/claudex/accounts/work%202',
    ]);
    vi.stubGlobal('fetch', vi.fn(async () => reply(404, { error: 'unknown head' })));
    await expect(auth.removeAccount('chatgpt-oauth', 'work')).rejects.toMatchObject({ status: 404, message: 'unknown head' });
  });
});

describe('the paths', () => {
  test('a head, id, kind, label or key name with a slash or a space cannot leave its segment', async () => {
    const { auth } = await fresh();
    expect(auth.loginPath('claude x')).toBe('/api/auth/claude%20x/login');
    expect(auth.loginStatusPath('a/b', '../c?d')).toBe('/api/auth/a%2Fb/login/..%2Fc%3Fd');
    expect(auth.refreshPath('a/b')).toBe('/api/auth/a%2Fb/refresh');
    expect(auth.switchPath('a/b')).toBe('/api/auth/a%2Fb/switch');
    expect(auth.accountPath('claude', 'work 2/x')).toBe('/api/auth/claude/accounts/work%202%2Fx');
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
