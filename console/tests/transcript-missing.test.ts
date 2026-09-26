// A SESSION WITH NO TRANSCRIPT IS NOT A ROUTE THAT DOES NOT EXIST (M4-06). The daemon serves
// GET /api/sessions/{id}/transcript and answers 404 with the directories it `searched` when it finds
// no file for the session (SessionsRoutes.kt). The entity mapped EVERY 404 to "route not built
// V4-130", so the conversation told the operator to wait for a work item that shipped. Only a 404
// that is not the daemon's own answer (an older daemon with no such route) is still pending. The
// answer is recognised by its structure, never by matching its sentence.
import { afterEach, describe, expect, test, vi } from 'vitest';

import { PENDING_TRANSCRIPT, loadTranscript } from '../src/entities/transcript';
import { transcriptStore } from '../src/entities/transcript/model/store';
import { readFor } from '../src/shared/lib';

const answer = (status: number, body: unknown) =>
  vi.stubGlobal('fetch', () => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })));

afterEach(() => vi.unstubAllGlobals());

describe('the transcript read', () => {
  test("the daemon's no-transcript answer is its own state, with where it looked", async () => {
    answer(404, { error: 'no transcript for this session id', searched: ['/home/x/.claude/projects'] });
    await loadTranscript('s-1');
    expect(readFor(transcriptStore.get(), 's-1').data).toEqual({ missing: ['/home/x/.claude/projects'] });
    expect(readFor(transcriptStore.get(), 's-1').error).toBeNull();
  });

  test('the answer is its structure: reworded, it is still missing; without `searched`, it is not', async () => {
    answer(404, { error: 'the daemon rewrote this sentence', searched: [] });
    await loadTranscript('s-3');
    expect(readFor(transcriptStore.get(), 's-3').data).toEqual({ missing: [] });
    answer(404, { error: 'no transcript for this session id' });
    await loadTranscript('s-4');
    expect(readFor(transcriptStore.get(), 's-4').data).toEqual({ pending: PENDING_TRANSCRIPT });
  });

  test('a 404 from a daemon with no such route is still the pending route', async () => {
    answer(404, { error: 'unknown route' });
    await loadTranscript('s-2');
    expect(readFor(transcriptStore.get(), 's-2').data).toEqual({ pending: PENDING_TRANSCRIPT });
  });
});
