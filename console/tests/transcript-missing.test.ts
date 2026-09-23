// A SESSION WITH NO TRANSCRIPT IS NOT A ROUTE THAT DOES NOT EXIST (M4-06). The daemon serves
// GET /api/sessions/{id}/transcript and answers 404 "no transcript for this session id" when it
// finds no file for the session (SessionsRoutes.kt). The entity mapped EVERY 404 to "route not built
// V4-130", so the conversation told the operator to wait for a work item that shipped. Only a 404
// that is not the daemon's own sentence (an older daemon with no such route) is still pending.
import { afterEach, describe, expect, test, vi } from 'vitest';

import { PENDING_TRANSCRIPT, TRANSCRIPT_MISSING, loadTranscript } from '../src/entities/transcript';
import { transcriptStore } from '../src/entities/transcript/model/store';

const answer = (status: number, body: unknown) =>
  vi.stubGlobal('fetch', () => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })));

afterEach(() => vi.unstubAllGlobals());

describe('the transcript read', () => {
  test("the daemon's no-transcript answer is its own state, printed as that", async () => {
    answer(404, { error: TRANSCRIPT_MISSING, searched: ['/home/x/.claude/projects'] });
    await loadTranscript('s-1');
    // The conversation widget prints `no transcript on disk` for exactly this error.
    expect(transcriptStore.get().error).toBe(TRANSCRIPT_MISSING);
    expect(transcriptStore.get().data).toBeNull();
  });

  test('a 404 from a daemon with no such route is still the pending route', async () => {
    answer(404, { error: 'unknown route' });
    await loadTranscript('s-2');
    expect(transcriptStore.get().data).toEqual({ pending: PENDING_TRANSCRIPT });
  });
});
