import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

// The client keeps module state (the held key, the 401 lock), so each test loads a fresh copy.
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

describe('the error envelope', () => {
  test('reads both shapes the daemon speaks, and falls back to the status', async () => {
    const { errorMessage } = await fresh();
    expect(errorMessage({ error: 'claude is not currently wrapped' }, 409)).toBe('claude is not currently wrapped');
    expect(errorMessage({ error: { message: 'over limit' } }, 429)).toBe('over limit');
    expect(errorMessage({ error: '  ' }, 500)).toBe('HTTP 500');
    expect(errorMessage(null, 502)).toBe('HTTP 502');
  });
});

describe('request', () => {
  test('carries the held key as the bearer', async () => {
    const { request, storeKey } = await fresh();
    const fetchMock = vi.fn(async () => reply(200, { ok: true }));
    vi.stubGlobal('fetch', fetchMock);
    storeKey(' abc123 ');
    await request('/api/status');
    const init = (fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1];
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer abc123');
  });

  test('a 401 locks: nothing more goes out until a key is stored again', async () => {
    const { request, storeKey, isLocked } = await fresh();
    const fetchMock = vi.fn(async () => reply(401, {}));
    vi.stubGlobal('fetch', fetchMock);
    storeKey('stale');
    await expect(request('/api/heads')).rejects.toMatchObject({ status: 401 });
    expect(isLocked()).toBe(true);
    await expect(request('/api/heads')).rejects.toMatchObject({ status: 401 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    storeKey('fresh');
    expect(isLocked()).toBe(false);
  });

  test('a late 401 for a key that is no longer held locks nothing', async () => {
    const { noteUnauthorized, storeKey, isLocked } = await fresh();
    storeKey('new');
    noteUnauthorized('old');
    expect(isLocked()).toBe(false);
  });

  test('a key a header cannot carry reopens the unlock screen and never reaches fetch', async () => {
    const { request, storeKey, isLocked } = await fresh();
    const fetchMock = vi.fn(async () => reply(200, {}));
    vi.stubGlobal('fetch', fetchMock);
    storeKey('smart“quote');
    await expect(request('/api/heads')).rejects.toMatchObject({ status: 401 });
    expect(isLocked()).toBe(true);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  test('no answer at all is status 0 with the daemon-not-answering sentence, never the browser\'s words', async () => {
    const { request, storeKey, NOT_ANSWERING } = await fresh();
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
    storeKey('k');
    await expect(request('/api/heads')).rejects.toMatchObject({ status: 0, message: NOT_ANSWERING });
  });

  test('a refusal carries the daemon\'s own sentence and its whole envelope', async () => {
    const { request, storeKey } = await fresh();
    vi.stubGlobal('fetch', vi.fn(async () => reply(409, { error: 'the head is taken', searched: ['/x'] })));
    storeKey('k');
    await expect(request('/api/add', { method: 'POST' })).rejects.toMatchObject({
      status: 409, message: 'the head is taken', body: { error: 'the head is taken', searched: ['/x'] },
    });
  });

  test('with no key held the console is locked before any request', async () => {
    const { isLocked } = await fresh();
    expect(isLocked()).toBe(true);
  });
});

describe('what a failure says', () => {
  test('the daemon\'s sentence comes through, and a non-error is printed', async () => {
    const { failureText, MgmtError } = await import('../src/api/client');
    expect(failureText(new MgmtError(409, 'That head is not running.'))).toBe('That head is not running.');
    expect(failureText('plain')).toBe('plain');
  });
});
