// The control client: bearer header attachment, 401 → unauthorized signal,
// error surfacing from Anthropic-shaped bodies.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

const localStore = new Map<string, string>();
vi.stubGlobal('localStorage', {
  getItem: (k: string) => localStore.get(k) ?? null,
  setItem: (k: string, v: string) => void localStore.set(k, v),
  removeItem: (k: string) => void localStore.delete(k),
});

const { bindUnauthorized, control, MgmtError, storeKey } = await import('../src/shared/api');

const fetchMock = vi.fn();
vi.stubGlobal('fetch', fetchMock);

beforeEach(() => fetchMock.mockReset());
afterEach(() => vi.clearAllMocks());

function jsonResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  };
}

describe('control client', () => {
  test('attaches the stored bearer key', async () => {
    storeKey('sekrit');
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { server: 'control', version: '1', heads: [], registry: [] }));
    await control.status();
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/status');
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer sekrit');
  });

  test('401 fires the unauthorized signal, throws, and LOCKS the client', async () => {
    const onUnauthorized = vi.fn();
    bindUnauthorized(onUnauthorized);
    fetchMock.mockResolvedValueOnce(jsonResponse(401, { error: { message: 'nope' } }));
    await expect(control.status()).rejects.toThrow(MgmtError);
    expect(onUnauthorized).toHaveBeenCalledOnce();

    // while locked, pollers short-circuit — zero network traffic behind the key gate
    await expect(control.usage()).rejects.toThrow(MgmtError);
    await expect(control.compact()).rejects.toThrow(MgmtError);
    expect(fetchMock).toHaveBeenCalledOnce();

    // storing a key re-arms the client
    storeKey('fresh');
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { server: 'control', version: '1', heads: [], registry: [] }));
    await control.status();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect((fetchMock.mock.calls[1][1].headers as Record<string, string>).Authorization).toBe('Bearer fresh');
  });

  test('non-ok surfaces the Anthropic-shaped error message', async () => {
    storeKey('k'); // ensure unlocked
    fetchMock.mockResolvedValueOnce(jsonResponse(400, { error: { message: 'unknown key' } }));
    await expect(control.config()).rejects.toThrow('unknown key');
  });

  test('PATCH serializes the patch body', async () => {
    storeKey('k');
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { applied: { effort: 'low' }, rejected: {}, restart_required: [], targets: [], persisted: 'runtime+file' }));
    await control.patchConfig({ effort: 'low' });
    const [, init] = fetchMock.mock.calls[0];
    expect(init.method).toBe('PATCH');
    expect(JSON.parse(init.body as string)).toEqual({ effort: 'low' });
  });

  test('head lifecycle and logs hit the per-head routes', async () => {
    storeKey('k');
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { key: 'codex', running: true }));
    await control.startHead('codex');
    expect(fetchMock.mock.calls[0][0]).toBe('/api/heads/codex/start');
    expect(fetchMock.mock.calls[0][1].method).toBe('POST');

    fetchMock.mockResolvedValueOnce(jsonResponse(200, { key: 'codex', path: '/tmp/codex.log', lines: [] }));
    await control.logs('codex', 200);
    expect(fetchMock.mock.calls[1][0]).toBe('/api/logs/codex?tail=200');
  });
});
