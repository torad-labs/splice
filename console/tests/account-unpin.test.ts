// S6's unpin, through the real client: the DELETE the console sends, what a daemon that serves it
// answers, and what an older daemon answers (405, because the path exists for POST). And the button
// is offered only on a pinned account, since there is no pin to drop anywhere else.
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { unpinAccount } from '../src/entities/auth';
import { AccountActions } from '../src/features/account-login';

interface Sent {
  path: string;
  method: string | undefined;
}

function transport(status: number, body: unknown, sent: Sent[]): void {
  vi.stubGlobal('fetch', async (path: string, init?: RequestInit) => {
    sent.push({ path, method: init?.method });
    return { ok: status >= 200 && status < 300, status, json: async () => body };
  });
}

describe('DELETE /api/auth/{head}/switch through the real client', () => {
  afterEach(() => vi.unstubAllGlobals());

  test('an unpin is one DELETE on the head\'s switch path, and ok is the action\'s result', async () => {
    const sent: Sent[] = [];
    transport(200, { ok: true }, sent);
    const outcome = await unpinAccount('claudex');
    expect(sent).toEqual([{ path: '/api/auth/claudex/switch', method: 'DELETE' }]);
    expect(outcome).toEqual({ action: 'unpin', result: { ok: true } });
  });

  test('a daemon older than the route answers 405, which reads as this version cannot, not as a fault', async () => {
    transport(405, { error: 'method not allowed' }, []);
    const outcome = await unpinAccount('claudex');
    expect(outcome).toHaveProperty('pending');
  });
});

describe('the unpin key', () => {
  test('is offered on a pinned account, per head, and nowhere else', () => {
    const pinned = renderToStaticMarkup(createElement(AccountActions, { kind: 'chatgpt-oauth', label: 'work', heads: ['claudex'], pinned: true }));
    expect(pinned).toContain('unpin claudex');
    const free = renderToStaticMarkup(createElement(AccountActions, { kind: 'chatgpt-oauth', label: 'work', heads: ['claudex'] }));
    expect(free).not.toContain('unpin');
  });
});
