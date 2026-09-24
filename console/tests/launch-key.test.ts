// The key every request carries, and how it arrives. `splice dashboard` opens the console at
// `#k=<key>` through an owner-only redirect page (app/cli/status/DashboardCommand.kt); the client
// takes it at load, keeps it like a pasted key and clears it from the address. Each case imports the
// client fresh, because the fragment is read once, when the module loads, the way the page loads it.
import { afterEach, describe, expect, test, vi } from 'vitest';

/** The page's address, and the history entries the client rewrote. */
function at(hash: string): string[] {
  const replaced: string[] = [];
  vi.stubGlobal('location', { hash, pathname: '/', search: '' });
  vi.stubGlobal('history', {
    state: null,
    replaceState: (_state: unknown, _title: string, url: string) => void replaced.push(url),
  });
  return replaced;
}

/** The browser's storage, holding the key kept from an earlier session. */
function kept(key: string): Map<string, string> {
  const store = new Map<string, string>([['myx-mgmt-key', key]]);
  vi.stubGlobal('localStorage', {
    getItem: (name: string) => store.get(name) ?? null,
    setItem: (name: string, value: string) => void store.set(name, value),
  });
  return store;
}

async function freshClient() {
  vi.resetModules();
  return import('../src/shared/api');
}

afterEach(() => vi.unstubAllGlobals());

describe('the key splice dashboard hands over', () => {
  test('is taken from the fragment, kept, and cleared from the address', async () => {
    const replaced = at('#k=abc123');
    const store = kept('');
    const client = await freshClient();
    expect(client.currentKey()).toBe('abc123');
    expect(store.get('myx-mgmt-key')).toBe('abc123');
    expect(replaced).toEqual(['/#/']);
  });

  test('wins over the key kept from an earlier session, which may have rotated', async () => {
    at('#k=abc123');
    kept('older');
    expect((await freshClient()).currentKey()).toBe('abc123');
  });

  test('an ordinary address is left alone, and the kept key is used', async () => {
    const replaced = at('#/fleet');
    kept('older');
    expect((await freshClient()).currentKey()).toBe('older');
    expect(replaced).toEqual([]);
  });

  test('the request carries the handed-over key as its bearer', async () => {
    at('#k=abc123');
    kept('');
    const seen: RequestInit[] = [];
    vi.stubGlobal('fetch', (_path: unknown, init: RequestInit) => {
      seen.push(init);
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) });
    });
    await (await freshClient()).request('/api/status');
    expect((seen[0].headers as Record<string, string>).Authorization).toBe('Bearer abc123');
  });
});
