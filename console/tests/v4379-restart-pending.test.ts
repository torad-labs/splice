import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { applyConfigPatch, clearRestartPending, markRestartPending, probeTopologyStale, restartStore } from '../src/entities/config';
import { needsOf } from '../src/pages/needs-you';
import type { NeedInputs, Read } from '../src/pages/needs-you';
import { PendingRestartCell } from '../src/widgets/rule';

const NOW = 1_790_000_000_000;
const unread: Read<never> = { data: null, error: null, lastUpdated: null };
const read = <T>(data: T): Read<T> => ({ data, error: null, lastUpdated: NOW });

function needs(pending: readonly string[]) {
  const inputs: NeedInputs = {
    heads: read([]), auth: unread, accounts: unread, usage: unread,
    sessions: unread, teams: unread, doctor: unread,
    topology: read(false), restartPending: pending,
  };
  return needsOf(inputs, NOW).needs;
}

describe('a pending setting belongs to one daemon boot', () => {
  afterEach(() => { clearRestartPending(); vi.unstubAllGlobals(); });

  test('a later boot clears the strip and Needs you; the same boot preserves both', async () => {
    let bootedAtEpochMillis = 1_000;
    vi.stubGlobal('fetch', async () => ({ ok: true, json: async () => ({ topologyStale: false, bootedAtEpochMillis }) }));
    await probeTopologyStale();
    markRestartPending(['trace']);
    await probeTopologyStale();
    expect(restartStore.get().pending).toEqual(['trace']);
    expect(needs(restartStore.get().pending).filter((need) => need.key === 'daemon')).toHaveLength(1);
    bootedAtEpochMillis = 2_000;
    await probeTopologyStale();
    expect(restartStore.get().pending).toEqual([]);
    expect(needs(restartStore.get().pending).filter((need) => need.key === 'daemon')).toEqual([]);
    expect(renderToStaticMarkup(createElement(PendingRestartCell, { pending: restartStore.get().pending }))).toBe('');
  });

  test('the PATCH anchors pending keys to the boot observed after the write', async () => {
    const calls: string[] = [];
    vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
      calls.push(`${init?.method ?? 'GET'} ${url}`);
      if (url === '/health') return { ok: true, json: async () => ({ topologyStale: false, bootedAtEpochMillis: 3_000 }) };
      if (init?.method === 'PATCH') return { ok: true, json: async () => ({
        applied: { trace: true }, rejected: {}, restart_required: ['trace'], targets: [], persisted: '/work/splice.toml',
      }) };
      return { ok: true, json: async () => ({ effective: {}, layers: {}, restart_required_keys: [], source: 'file' }) };
    });
    await applyConfigPatch({ trace: true });
    expect(calls).toEqual(['PATCH /api/config', 'GET /health', 'GET /api/config']);
    expect(restartStore.get().pending).toEqual(['trace']);
    expect(restartStore.get().pendingAtEpochMillis).toBe(3_000);
  });

  test('a restart before PATCH does not clear a write accepted by the new boot', async () => {
    let stamp = 7_000;
    vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
      if (url === '/health') return { ok: true, json: async () => ({ topologyStale: false, bootedAtEpochMillis: stamp }) };
      if (init?.method === 'PATCH') {
        stamp = 8_000;
        return { ok: true, json: async () => ({
          applied: {}, rejected: {}, restart_required: ['trace'], targets: [], persisted: '/work/splice.toml',
        }) };
      }
      return { ok: true, json: async () => ({ effective: {}, layers: {}, restart_required_keys: [], source: 'file' }) };
    });
    await probeTopologyStale();
    await applyConfigPatch({ trace: true });
    await probeTopologyStale();
    expect(restartStore.get().pending).toEqual(['trace']);
    expect(restartStore.get().pendingAtEpochMillis).toBe(8_000);
  });

  test('a failed post-write health read cannot fake a completed restart', async () => {
    let stamp = 5_000;
    let firstHealth = true;
    vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
      if (url === '/health') {
        if (firstHealth) { firstHealth = false; return { ok: false, status: 503 }; }
        return { ok: true, json: async () => ({ topologyStale: false, bootedAtEpochMillis: stamp }) };
      }
      if (init?.method === 'PATCH') return { ok: true, json: async () => ({
        applied: {}, rejected: {}, restart_required: ['trace'], targets: [], persisted: '/work/splice.toml',
      }) };
      return { ok: true, json: async () => ({ effective: {}, layers: {}, restart_required_keys: [], source: 'file' }) };
    });
    await applyConfigPatch({ trace: true });
    expect(restartStore.get().pending).toEqual(['trace']);
    await probeTopologyStale();
    expect(restartStore.get().pending).toEqual(['trace']);
    stamp = 6_000;
    await probeTopologyStale();
    expect(restartStore.get().pending).toEqual([]);
  });

  test('an older in-flight health reply cannot undo a newer boot observation', async () => {
    let releaseOld: () => void = () => undefined;
    const held = new Promise<void>((resolve) => { releaseOld = resolve; });
    let calls = 0;
    vi.stubGlobal('fetch', async () => {
      const call = ++calls;
      if (call === 2) await held;
      return { ok: true, json: async () => ({ topologyStale: false, bootedAtEpochMillis: call === 3 ? 2_000 : 1_000 }) };
    });
    await probeTopologyStale();
    const oldRead = probeTopologyStale();
    await probeTopologyStale();
    markRestartPending(['trace']);
    releaseOld();
    await oldRead;
    expect(restartStore.get().pending).toEqual(['trace']);
    expect(restartStore.get().pendingAtEpochMillis).toBe(2_000);
  });

  test('a single key reads as one setting waiting, and an unread boot never clears it', async () => {
    markRestartPending(['trace']);
    expect(needs(restartStore.get().pending).find((need) => need.key === 'daemon')?.finding).toContain('1 setting waiting');
    vi.stubGlobal('fetch', async () => ({ ok: true, json: async () => ({ topologyStale: false }) }));
    await probeTopologyStale();
    expect(restartStore.get().pending).toEqual(['trace']);
  });
});
