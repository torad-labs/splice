// The key every request carries, and where it comes from. A console the daemon serves carries its
// key in a meta element (app/control/mount/ServedConsole.kt), so it never shows the gate; the gate's
// pasted key is the fallback. Each case imports the client fresh, because the served key is read
// once, when the module loads, the way the page loads it.
import { afterEach, describe, expect, test, vi } from 'vitest';

function servedPage(key: string | null): void {
  vi.stubGlobal('document', {
    querySelector: (selector: string) =>
      (key !== null && selector === 'meta[name="splice-mgmt-key"]' ? { content: key } : null),
  });
}

function pastedBefore(key: string): void {
  vi.stubGlobal('localStorage', { getItem: () => key, setItem: () => undefined });
}

async function freshClient() {
  vi.resetModules();
  return import('../src/shared/api');
}

afterEach(() => vi.unstubAllGlobals());

describe('the key every request carries', () => {
  test('the key the daemon printed into the page wins over one pasted in an earlier session', async () => {
    servedPage('served');
    pastedBefore('pasted-before');
    expect((await freshClient()).currentKey()).toBe('served');
  });

  test('a key pasted this session wins over the served one, which the gate proved stale', async () => {
    servedPage('served');
    pastedBefore('pasted-before');
    const client = await freshClient();
    client.storeKey('pasted-now');
    expect(client.currentKey()).toBe('pasted-now');
  });

  test('a page with no key (the Vite dev server, an older daemon) uses the key pasted before', async () => {
    servedPage(null);
    pastedBefore('pasted-before');
    expect((await freshClient()).currentKey()).toBe('pasted-before');
  });

  test('the request carries the served key as its bearer', async () => {
    servedPage('served');
    pastedBefore('');
    const seen: RequestInit[] = [];
    vi.stubGlobal('fetch', (_path: unknown, init: RequestInit) => {
      seen.push(init);
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) });
    });
    await (await freshClient()).request('/api/status');
    expect((seen[0].headers as Record<string, string>).Authorization).toBe('Bearer served');
  });
});
