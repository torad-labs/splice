import { create } from 'zustand';
import { createResource } from '@shared/lib';
import type { SessionsPayload } from './types';

// TWO stores live in this slice and their names say which is which. `sessionStore` is the
// management-key gate the shell's unlock-mgmt reads (locked/hasKey); `sessionRegistryStore` is
// Claude Code's own session registry, one row per interactive session.

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
