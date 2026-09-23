import { create } from 'zustand';
import { createResource } from '@shared/lib';
import type { BoardEdgesPayload, SessionEdgesPayload, SessionsPayload } from './types';

// THREE stores live in this slice and their names say which is which. `sessionStore` is the
// management-key gate the shell's unlock-mgmt reads (locked/hasKey); `sessionRegistryStore` is
// Claude Code's own session registry, one row per interactive session; the two edge stores are the
// message edges between those sessions.

export interface SessionState {
  locked: boolean;
  hasKey: boolean;
}

export const sessionStore = create<SessionState>(() => ({
  locked: false,
  hasKey: false,
}));

/** The registry. Not a PendingRoute union: /api/sessions exists (ControlServer.kt:154), so a 404
 *  here is an error to report, not a route to wait for. */
export const sessionRegistryStore = createResource<SessionsPayload>();

/** One session's message edges (GET /api/sessions/{id}/edges), read when it is opened. */
export const sessionEdgesStore = createResource<SessionEdgesPayload>();

/** Every registry session's edges (GET /api/sessions/edges), polled with the board. */
export const boardEdgesStore = createResource<BoardEdgesPayload>();
