// The key gate (S5 of the 2026-09-24 walkthrough): a wrong key pasted into the gate re-opened the
// same empty modal, with nothing to say the daemon had refused it, so a typo and "nothing happened"
// looked identical. The refusal is a fact about the 401 (it answered a request that carried a key),
// so it is pinned at the store, from a stubbed fetch through the real client; the words are pinned
// on the modal's markup, which is rendered from props because a static render only ever sees a
// store's initial state.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test, vi } from 'vitest';

const fetchMock = vi.fn();
vi.stubGlobal('fetch', fetchMock);

const { fetchSessions, initSession, unlock, useSession } = await import('../src/entities/session');
const { UnlockForm } = await import('../src/features/unlock-mgmt');

const h = React.createElement;

const unauthorized = () => ({ ok: false, status: 401, json: () => Promise.resolve({ error: 'unauthorized' }) });

describe('the key gate', () => {
  // Runs first on purpose: the client's key is module state, and this is the one case with none.
  test('a 401 with no key held is a key never given, not a key refused', async () => {
    initSession();
    fetchMock.mockResolvedValueOnce(unauthorized());
    await fetchSessions();
    expect(useSession.getState()).toMatchObject({ locked: true, refused: false });
  });

  test('a key the daemon refuses is said to be refused, until the next attempt', async () => {
    unlock('not-the-key');
    expect(useSession.getState()).toMatchObject({ locked: false, refused: false });

    fetchMock.mockResolvedValueOnce(unauthorized());
    await fetchSessions();
    expect(useSession.getState()).toMatchObject({ locked: true, refused: true });

    unlock('another-try');
    expect(useSession.getState()).toMatchObject({ locked: false, refused: false });
  });

  test('the modal prints the refusal in words, and only when there was one', () => {
    const refused = renderToStaticMarkup(h(UnlockForm, { refused: true }));
    expect(refused).toContain('that key was refused');
    expect(refused).toContain('role="alert"');
    expect(renderToStaticMarkup(h(UnlockForm, { refused: false }))).not.toContain('refused');
  });
});
