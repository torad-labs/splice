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

  // V4-175. THIS is the envelope the control plane actually writes — every refusal in
  // splice.control.api is one `buildJsonObject { put("error", message) }` — and the client only
  // read the proxy's nested one, so a string `error` has no `.message` and every daemon sentence
  // became `HTTP 409` on screen. Found on POST /api/claude-head/wrap, where the reason IS the
  // answer; the fix is shared, so the arm is here and not in the claude-head tests.
  test('non-ok surfaces the control plane flat error string', async () => {
    storeKey('k');
    fetchMock.mockResolvedValueOnce(jsonResponse(409, { error: 'claude is not currently wrapped' }));
    await expect(control.config()).rejects.toThrow('claude is not currently wrapped');
  });

  test('a body with no usable message falls back to the status', async () => {
    storeKey('k');
    fetchMock.mockResolvedValueOnce(jsonResponse(503, { error: '   ' }));
    await expect(control.config()).rejects.toThrow('HTTP 503');
  });

  // JW-06: the per-head view is reachable only if the selected head reaches the QUERY STRING. The
  // control plane folds `[heads.<key>.overrides]` into the effective view when /api/config carries
  // ?head=, which is what makes "why is kimi's maxInflight 8 when the panel says 100" answerable.
  // Every other config arm here calls control.config() with no head, so the parameter could be
  // dropped from the client and nothing on this side would notice.
  test('config carries the selected head to the query string - JW-06', async () => {
    storeKey('k');
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { effective: {}, layers: {} }));
    await control.config('kimi');
    expect(fetchMock.mock.calls[0][0]).toBe('/api/config?head=kimi');
  });

  test('config with no head asks for the global view - JW-06 bound', async () => {
    storeKey('k');
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { effective: {}, layers: {} }));
    await control.config();
    expect(fetchMock.mock.calls[0][0]).toBe('/api/config');
  });

  test('a head name is encoded, never interpolated raw - JW-06', async () => {
    storeKey('k');
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { effective: {}, layers: {} }));
    await control.config('a head/with?chars');
    expect(fetchMock.mock.calls[0][0]).toBe('/api/config?head=a%20head%2Fwith%3Fchars');
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
