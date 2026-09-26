import { create } from 'zustand';
import { createKeyed, createResource } from '@shared/lib';
import type { BoardEdgesPayload, SessionEdgesPayload, SessionsPayload } from './types';

// THREE stores live in this slice and their names say which is which. `sessionStore` is the
// management-key gate the shell's unlock-mgmt reads (locked/hasKey); `sessionRegistryStore` is
// Claude Code's own session registry, one row per interactive session; the two edge stores are the
// message edges between those sessions.

export interface SessionState {
  locked: boolean;
  hasKey: boolean;
  /** The 401 that locked the console answered a request that CARRIED a key: the daemon refused the
   *  key, which is a different fact from never having been given one. Cleared by the next unlock. */
  refused: boolean;
}

export const sessionStore = create<SessionState>(() => ({
  locked: false,
  hasKey: false,
  refused: false,
}));

/** The registry. Not a PendingRoute union: /api/sessions exists (ControlServer.kt:154), so a 404
 *  here is an error to report, not a route to wait for. */
export const sessionRegistryStore = createResource<SessionsPayload>();

/** One session's message edges (GET /api/sessions/{id}/edges), read when it is opened, by session
 *  id: the panel of the session opened next asks for its own (V4-304). */
export const sessionEdgesStore = createKeyed<string, SessionEdgesPayload>();

/** Every registry session's edges (GET /api/sessions/edges), polled with the board. */
export const boardEdgesStore = createResource<BoardEdgesPayload>();
