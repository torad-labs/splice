// The transcript entity's HTTP segment: two calls, both explicit. There is no poller and nothing
// runs on import, because FEATURES.md 4.4 reads a conversation from disk on demand and this entity
// must never prefetch a body the operator did not ask for.
import { MgmtError, pendingOf, request } from '@shared/api';
import { advanceCursor, openCursor } from '../model/cursor';
import { transcriptStore } from '../model/store';
import type { TranscriptPage, TranscriptState } from '../model/types';

/** The v0.4.0 item that serves the transcript route, named for a daemon older than it. */
export const PENDING_TRANSCRIPT = 'V4-130';

/** The daemon's own answer for a session with no transcript on disk (SessionsRoutes.kt, a 404). The
 *  route exists and answered: that is not the pending route a 404 from an older daemon means. */
export const TRANSCRIPT_MISSING = 'no transcript for this session id';

/** The loaded state, or null when nothing is loaded yet or the route is pending. */
function loadedState(): TranscriptState | null {
  const data = transcriptStore.get().data;
  if (data === null || 'pending' in data) return null;
  return data;
}

function resolveFailure(err: unknown): void {
  if (err instanceof MgmtError && err.status === 404 && err.message === TRANSCRIPT_MISSING) {
    transcriptStore.setError(TRANSCRIPT_MISSING);
    return;
  }
  const pending = pendingOf(err, PENDING_TRANSCRIPT);
  if (pending !== null) {
    transcriptStore.setData(pending);
    return;
  }
  transcriptStore.setError(err instanceof Error ? err.message : String(err));
}

/** The cursor travels as a query token; the daemon mints it and the console never parses it. */
function pagePath(sessionId: string, cursor: string | null): string {
  const path = `/api/sessions/${encodeURIComponent(sessionId)}/transcript`;
  return cursor === null ? path : `${path}?cursor=${encodeURIComponent(cursor)}`;
}

/** The FIRST page of a session's transcript. Always starts at the beginning: a transcript is read
 *  from the top, and a re-read after a failure must not resume mid-conversation. */
export async function loadTranscript(sessionId: string): Promise<void> {
  transcriptStore.startLoading();
  try {
    const page = await request<TranscriptPage>(pagePath(sessionId, null));
    const { cursor } = advanceCursor(openCursor(sessionId), page);
    transcriptStore.setData({
      sessionId: page.session_id,
      path: page.path,
      messages: page.messages,
      cursor,
    });
  } catch (err) {
    resolveFailure(err);
  }
}

/**
 * The next page of what is already loaded. A no-op when nothing is loaded, when the daemon already
 * said the transcript ended, or when a page is still in flight for another session: the cursor
 * belongs to ONE conversation and this never starts a second one behind the reader's back.
 */
export async function loadMoreTranscript(): Promise<void> {
  const state = loadedState();
  if (state === null) return;
  const token = state.cursor.next;
  if (state.cursor.pages === 0 || token === null) return;
  try {
    const page = await request<TranscriptPage>(pagePath(state.sessionId, token));
    const { cursor, reset } = advanceCursor(state.cursor, page);
    if (reset) {
      transcriptStore.setData({ sessionId: page.session_id, path: page.path, messages: page.messages, cursor });
      return;
    }
    transcriptStore.setData({
      sessionId: state.sessionId,
      path: page.path,
      messages: [...state.messages, ...page.messages],
      cursor,
    });
  } catch (err) {
    resolveFailure(err);
  }
}
