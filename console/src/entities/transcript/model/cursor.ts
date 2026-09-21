// The transcript cursor: where a reader is in a conversation, as pure functions. No store, no
// clock, no fetching, and deliberately no accumulated body - the messages live in the store, this
// only says which page comes next and how far in the reader is.
import type { TranscriptCursor, TranscriptPage } from './types';

/** A reader that has not asked for a page yet. `next: null` means "start at the beginning", which
 *  is why the first request carries no cursor at all rather than an empty token. */
export function openCursor(sessionId: string): TranscriptCursor {
  return { sessionId, next: null, pages: 0, complete: false };
}

export interface CursorAdvance {
  cursor: TranscriptCursor;
  /** True when [page] does NOT continue the cursor's conversation: a different session. The caller
   *  REPLACES what it holds instead of appending, so two conversations can never interleave on
   *  screen, and the reader is not left half in each. */
  reset: boolean;
}

/**
 * Move the cursor onto a page. A last page (no next token, or a null one) marks the reader
 * complete, which is what stops the view from offering "load more" into an empty answer forever.
 */
export function advanceCursor(cursor: TranscriptCursor, page: TranscriptPage): CursorAdvance {
  const next = page.next ?? null;
  const reset = page.session_id !== cursor.sessionId;
  const pages = reset ? 1 : cursor.pages + 1;
  return {
    cursor: { sessionId: page.session_id, next, pages, complete: next === null },
    reset,
  };
}
