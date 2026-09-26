// F142 (review #94): applyConfigPatch must refresh the SAME view the operator is looking at.
// A PATCH made while viewing a head used to refetch the GLOBAL layered view, leaving the
// head selector showing that head over global data until the user reselected.
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

const localStore = new Map<string, string>();
vi.stubGlobal('localStorage', {
  getItem: (k: string) => localStore.get(k) ?? null,
  setItem: (k: string, v: string) => void localStore.set(k, v),
  removeItem: (k: string) => void localStore.delete(k),
});

const { applyConfigPatch } = await import('../src/entities/config/api');
const { knobDispositions } = await import('../src/entities/config');
const { saveGlobalKnob } = await import('../src/pages/settings');
const { fixtureConfig } = await import('../src/pages/settings/fixtures/settings');
const { KnobRack } = await import('../src/widgets/knob-form');

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

// V4-310: PATCH /api/config answers `persisted: null` and `not_persisted` naming why when the value
// is live but did not reach config.json (V4-299, ConfigRoutes.kt:86-89), so a restart loses it. The
// console typed `persisted` as a string and read neither, so the knob read as saved.
describe('a knob the daemon could not save to disk says so (V4-310)', () => {
  const NOT_SAVED = '/home/op/.splice/state/config.json could not be written (No space left on device); '
    + 'the change is live until the daemon restarts, which reads the saved value again';

  /** The rack's row for [key], from its `data-knob` to the next row's. */
  function rowOf(html: string, key: string): string {
    const start = html.indexOf(`data-knob="${key}"`);
    const next = html.indexOf('data-knob="', start + 1);
    return start === -1 ? '' : html.slice(start, next === -1 ? html.length : next);
  }

  test('persisted null prints the daemon\'s reason beside that knob, and under no other', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, { ...patchResult, rejected: {}, persisted: null, not_persisted: NOT_SAVED }))
      .mockResolvedValueOnce(jsonResponse(200, fixtureConfig));

    const fault = await saveGlobalKnob('usageWarnPct', 90);

    expect(fault).toBe(NOT_SAVED);
    const html = renderToStaticMarkup(createElement(KnobRack, {
      dispositions: knobDispositions(fixtureConfig), pending: [], busyKey: null, onSave: () => undefined,
      faultOf: (knob: { key: string }) => (knob.key === 'usageWarnPct' ? fault : null),
    }));
    expect(rowOf(html, 'usageWarnPct')).toContain(NOT_SAVED);
    expect(html.split(NOT_SAVED)).toHaveLength(3);
  });

  test('a PATCH that reached config.json prints no warning', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, { ...patchResult, rejected: {}, persisted: 'state/config.json' }))
      .mockResolvedValueOnce(jsonResponse(200, fixtureConfig));

    expect(await saveGlobalKnob('usageWarnPct', 90)).toBeNull();
  });

  test('a key the daemon refused prints its refusal, which says more than the write', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, {
        ...patchResult, rejected: { usageWarnPct: 'usageWarnPct must be between 1 and 100' }, persisted: null, not_persisted: NOT_SAVED,
      }))
      .mockResolvedValueOnce(jsonResponse(200, fixtureConfig));

    expect(await saveGlobalKnob('usageWarnPct', 900)).toBe('usageWarnPct must be between 1 and 100');
  });
});
