// V4-220 item 1's console half: PUT and DELETE /api/keys/{ENV} through the real client, the reads a
// write takes again, and the line each head's answer prints. The value goes one way: it is the PUT's
// body, and no store the console keeps ever holds it.
import { afterEach, describe, expect, test, vi } from 'vitest';
import { removeKey, storeKey } from '../src/entities/auth';
import { authActionStore, authStore, keysStore } from '../src/entities/auth/model/store';
import type { KeyState, KeysPayload } from '../src/entities/auth';
import { readerLine, sourceWord } from '../src/features/api-key';
import { H, SOURCE } from '../src/features/api-key/strings';

interface Sent {
  path: string;
  method: string;
  body: string | undefined;
}

const SECRET = 'xai-demo-7f3c9a1e5b2d4c6f8a0e';

/** A daemon that answers the write with `write`, and the two reads a write takes again. */
function daemon(write: { status: number; body: unknown }, list: KeysPayload, sent: Sent[]): void {
  vi.stubGlobal('fetch', async (path: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    sent.push({ path, method, body: typeof init?.body === 'string' ? init.body : undefined });
    const [status, body] = method !== 'GET' ? [write.status, write.body]
      : path === '/api/keys' ? [200, list]
      : [200, { 'claude-grok': { kind: 'api-key', present: true, env_var: 'XAI_API_KEY', api_key_masked: 'xai-…4c6f' } }];
    return { ok: status >= 200 && status < 300, status, json: async () => body };
  });
}

const APPLIED: KeyState = { name: 'XAI_API_KEY', stored: true, heads: [{ head: 'claude-grok', source: 'store' }] };

describe('PUT and DELETE /api/keys/{ENV} through the real client', () => {
  afterEach(() => vi.unstubAllGlobals());

  test('a store is one PUT of the value on the variable\'s path, then the key list and the auth card are read again', async () => {
    const sent: Sent[] = [];
    daemon({ status: 200, body: APPLIED }, { path: '/k/keys.toml', keys: [APPLIED] }, sent);
    expect(await storeKey('XAI_API_KEY', SECRET)).toEqual(APPLIED);
    expect(sent[0]).toEqual({ path: '/api/keys/XAI_API_KEY', method: 'PUT', body: JSON.stringify({ value: SECRET }) });
    expect(sent.slice(1).map((each) => `${each.method} ${each.path}`).sort()).toEqual(['GET /api/auth', 'GET /api/keys']);
    expect(keysStore.get().data).toEqual({ path: '/k/keys.toml', keys: [APPLIED] });
    // The value went out once, in the PUT's body, and no store the console keeps holds it.
    expect(sent.filter((each) => each.body?.includes(SECRET) === true)).toHaveLength(1);
    for (const store of [keysStore, authStore, authActionStore]) expect(JSON.stringify(store.get())).not.toContain(SECRET);
  });

  test('a remove is one DELETE on the variable\'s path, and its answer is what each head reads now', async () => {
    const sent: Sent[] = [];
    const removed: KeyState = { name: 'XAI_API_KEY', stored: false, heads: [{ head: 'claude-grok', source: 'missing' }] };
    daemon({ status: 200, body: removed }, { path: '/k/keys.toml', keys: [removed] }, sent);
    expect(await removeKey('XAI_API_KEY')).toEqual(removed);
    expect(sent[0]).toEqual({ path: '/api/keys/XAI_API_KEY', method: 'DELETE', body: undefined });
  });

  test('a refusal throws the daemon\'s sentence, and nothing is read again', async () => {
    const sent: Sent[] = [];
    daemon({ status: 404, body: { error: 'XAI_API_KEY was not stored' } }, { path: '', keys: [] }, sent);
    await expect(removeKey('XAI_API_KEY')).rejects.toThrow('XAI_API_KEY was not stored');
    expect(sent).toHaveLength(1);
  });
});

describe('what a head reads after a write', () => {
  test('each link of the read chain is said as itself, and a shadowed store is never said as applied', () => {
    expect(readerLine({ head: 'claude-grok', source: 'store' }, 'XAI_API_KEY')).toBe(H.store('claude-grok'));
    expect(readerLine({ head: 'claude-grok', source: 'environment' }, 'XAI_API_KEY')).toBe(H.environment('claude-grok', 'XAI_API_KEY'));
    expect(H.environment('claude-grok', 'XAI_API_KEY')).toContain('still reads XAI_API_KEY');
    expect(readerLine({ head: 'claude-grok', source: 'file' }, 'XAI_API_KEY')).toBe(H.file('claude-grok'));
    expect(readerLine({ head: 'claude-grok', source: 'missing' }, 'XAI_API_KEY')).toBe(H.missing('claude-grok'));
    // A link this console has no words for yet prints the daemon's own.
    expect(readerLine({ head: 'claude-grok', source: 'vault' }, 'XAI_API_KEY')).toBe(H.other('claude-grok', 'vault'));
    expect(sourceWord('environment')).toBe(SOURCE.environment);
    expect(sourceWord('vault')).toBe('vault');
  });
});
