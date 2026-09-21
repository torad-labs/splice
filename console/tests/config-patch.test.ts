// F142 (review #94): applyConfigPatch must refresh the SAME view the operator is looking at.
// A PATCH made while viewing a head used to refetch the GLOBAL layered view, leaving the
// head selector showing that head over global data until the user reselected.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

const localStore = new Map<string, string>();
vi.stubGlobal('localStorage', {
  getItem: (k: string) => localStore.get(k) ?? null,
  setItem: (k: string, v: string) => void localStore.set(k, v),
  removeItem: (k: string) => void localStore.delete(k),
});

const { applyConfigPatch } = await import('../src/entities/config/api');

const fetchMock = vi.fn();
vi.stubGlobal('fetch', fetchMock);

beforeEach(() => fetchMock.mockReset());
afterEach(() => vi.clearAllMocks());

function jsonResponse(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) };
}

const patchResult = { applied: {}, restart_required: [], persisted: 'ok', targets: [] };
const configPayload = { effective: {}, layers: { perHead: {} } };

describe('applyConfigPatch (F142)', () => {
  test('refreshes the head-scoped view when a head is given', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, patchResult))
      .mockResolvedValueOnce(jsonResponse(200, configPayload));
    await applyConfigPatch({ effort: 'high' }, 'claudex');
    expect(fetchMock.mock.calls[0][0]).toBe('/api/config');
    expect(fetchMock.mock.calls[1][0]).toBe('/api/config?head=claudex');
  });

  test('refreshes the global view when no head is given', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, patchResult))
      .mockResolvedValueOnce(jsonResponse(200, configPayload));
    await applyConfigPatch({ effort: 'high' });
    expect(fetchMock.mock.calls[1][0]).toBe('/api/config');
  });
});
