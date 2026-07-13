// The mgmt client: bearer header attachment, 401 → unauthorized signal,
// error surfacing from Anthropic-shaped bodies.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

const localStore = new Map<string, string>();
vi.stubGlobal('localStorage', {
  getItem: (k: string) => localStore.get(k) ?? null,
  setItem: (k: string, v: string) => void localStore.set(k, v),
  removeItem: (k: string) => void localStore.delete(k),
});

const { bindUnauthorized, mgmt, MgmtError, storeKey } = await import('../src/shared/api');

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

describe('mgmt client', () => {
  test('attaches the stored bearer key', async () => {
    storeKey('sekrit');
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { proxy: 'codex-proxy' }));
    await mgmt.status();
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/mgmt/status');
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer sekrit');
  });

  test('401 fires the unauthorized signal and throws MgmtError', async () => {
    const onUnauthorized = vi.fn();
    bindUnauthorized(onUnauthorized);
    fetchMock.mockResolvedValueOnce(jsonResponse(401, { error: { message: 'nope' } }));
    await expect(mgmt.status()).rejects.toThrow(MgmtError);
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  test('non-ok surfaces the Anthropic-shaped error message', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(400, { error: { message: 'unknown key' } }));
    await expect(mgmt.config()).rejects.toThrow('unknown key');
  });

  test('PATCH serializes the patch body', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { applied: { effort: 'low' }, rejected: {}, restart_required: [], effective: {} }));
    await mgmt.patchConfig({ effort: 'low' });
    const [, init] = fetchMock.mock.calls[0];
    expect(init.method).toBe('PATCH');
    expect(JSON.parse(init.body as string)).toEqual({ effort: 'low' });
  });
});
