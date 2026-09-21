// Two jobs in one slice: the management-key gate (initSession/unlock, the shell calls these) and
// the Claude Code session registry (fetchSessions, the Sessions page calls this).
import { bindUnauthorized, getStoredKey, pendingOf, request, storeKey } from '@shared/api';
import { poll } from '@shared/lib';
import { sessionEdgesStore, sessionRegistryStore, sessionStore } from '../model/store';
import type { SessionEdgesPayload, SessionsPayload } from '../model/types';

/** The v0.4.0 item that will serve the message-edge route. */
export const PENDING_EDGES = 'V4-130';

/** Wire the 401 signal from the mgmt client into session state (app mount). */
export function initSession(): void {
  bindUnauthorized(() => sessionStore.setState({ locked: true }));
  sessionStore.setState({ hasKey: Boolean(getStoredKey()), locked: !getStoredKey() });
}

/** Store the pasted management key and unlock; pollers retry on their next tick. */
export function unlock(key: string): void {
  storeKey(key);
  sessionStore.setState({ locked: false, hasKey: Boolean(key.trim()) });
}

/** GET /api/sessions — the registry, re-read daemon-side on every request because Claude Code
 *  rewrites the files as sessions come and go, which is why this polls faster than the catalog. */
export async function fetchSessions(): Promise<void> {
  sessionRegistryStore.startLoading();
  try {
    sessionRegistryStore.setData(await request<SessionsPayload>('/api/sessions'));
  } catch (err) {
    sessionRegistryStore.setError(err instanceof Error ? err.message : String(err));
  }
}

export function startSessionsPolling(intervalMs = 5000): () => void {
  return poll(fetchSessions, intervalMs);
}

/**
 * One session's message edges. Read when a session is opened, never polled: a hand-off is history
 * once it happened, and the transcript beside it is what a reader actually watches.
 *
 * PENDING V4-130, so the pending state is a real outcome here rather than an error path.
 */
export async function fetchSessionEdges(sessionId: string): Promise<void> {
  sessionEdgesStore.startLoading();
  try {
    sessionEdgesStore.setData(
      await request<SessionEdgesPayload>(`/api/sessions/${encodeURIComponent(sessionId)}/edges`),
    );
  } catch (err) {
    const pending = pendingOf(err, PENDING_EDGES);
    if (pending !== null) {
      sessionEdgesStore.setData(pending);
      return;
    }
    sessionEdgesStore.setError(err instanceof Error ? err.message : String(err));
  }
}
