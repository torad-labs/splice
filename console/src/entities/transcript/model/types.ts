// The session transcript, typed from FEATURES.md section 6 ("GET /api/sessions/{id}/transcript: the
// session's local transcript from its head's CLAUDE_CONFIG_DIR/projects, paginated, redacted") and
// 4.4 (read as a conversation between participants, not a log tail).
//
// PENDING V4-130. The route does not exist, so this is the contract the console builds against: the
// page renders the pending empty until it does, never a mocked conversation.
import type { PendingRoute } from '@shared/api';

/** Who spoke. The daemon's reader folds the client's own event kinds into these four, because the
 *  page renders a conversation and "tool result" is a participant's turn, not a JSONL event. */
export type TranscriptRole = 'user' | 'assistant' | 'system' | 'tool';

export interface TranscriptMessage {
  /** Position in the transcript, counted from 0. Stable across pages, which is what lets a loaded
   *  page be appended without renumbering what is already on screen. */
  index: number;
  role: TranscriptRole;
  /** Epoch ms when the transcript carries it; the client does not stamp every event. */
  ts?: number;
  /** The message text, already redacted daemon-side. */
  text: string;
  /** The tool's name on a tool call or a tool result. */
  tool?: string;
  /** True on a tool RESULT, false on the call that produced it; absent on everything else. */
  result?: boolean;
}

export interface TranscriptPage {
  session_id: string;
  /** The file that answered this page. FEATURES.md 4.4: the reader falls back from the head's own
   *  config dir to the vanilla ~/.claude/projects tree, and "shows which path it read", so the page
   *  prints this rather than assuming. */
  path: string;
  messages: TranscriptMessage[];
  /** Opaque token for the next page. Absent or null means this was the last page. The console
   *  never parses it: it is the daemon's cursor to mint. */
  next?: string | null;
  /** The number of messages in the whole transcript, when the daemon counted them. */
  total?: number;
}

/** The loaded transcript, and where the next page starts. */
export interface TranscriptState {
  sessionId: string;
  path: string;
  messages: TranscriptMessage[];
  cursor: TranscriptCursor;
}

/** Where the reader is. The cursor is the POSITION, never the body: nothing here holds a page the
 *  store has not loaded, which is what keeps the entity from prefetching a conversation. */
export interface TranscriptCursor {
  sessionId: string;
  /** The daemon's opaque token for the next page; null before the first page is read. */
  next: string | null;
  /** Pages read so far, so a view can say how far in it is. */
  pages: number;
  /** True once the daemon answered without a next token. */
  complete: boolean;
}

export type TranscriptSlice = TranscriptState | PendingRoute;
