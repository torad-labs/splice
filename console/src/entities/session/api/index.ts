// Two jobs in one slice: the management-key gate (initSession/unlock, the shell calls these) and
// the Claude Code session registry with the message edges between its sessions (the Sessions page
// calls those).
import { bindUnauthorized, getStoredKey, request, storeKey } from '@shared/api';
import { poll } from '@shared/lib';
import { boardEdgesStore, sessionEdgesStore, sessionRegistryStore, sessionStore } from '../model/store';
import type { BoardEdgesPayload, SessionEdgesPayload, SessionsPayload } from '../model/types';

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

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
 * once it happened, and the transcript beside it is what a reader actually watches. The route is
 * served (V4-130), so a failure is reported as one, never as "not built".
 */
export async function fetchSessionEdges(sessionId: string): Promise<void> {
  sessionEdgesStore.startLoading();
  try {
    sessionEdgesStore.setData(
      await request<SessionEdgesPayload>(`/api/sessions/${encodeURIComponent(sessionId)}/edges`),
    );
  } catch (err) {
    sessionEdgesStore.setError(messageOf(err));
  }
}

/**
 * Every registry session's edges in one read (GET /api/sessions/edges), so the board's peer column
 * prints for every row rather than one request per row. An unwired edge store is the daemon's named
 * 503, which the page prints: unwatched is not the same as no hand-offs.
 */
export async function fetchBoardEdges(): Promise<void> {
  boardEdgesStore.startLoading();
  try {
    boardEdgesStore.setData(await request<BoardEdgesPayload>('/api/sessions/edges'));
  } catch (err) {
    boardEdgesStore.setError(messageOf(err));
  }
}

/** The board's edges at the registry's own cadence, so a row and its peer come from the same tick. */
export function startBoardEdgesPolling(intervalMs = 5000): () => void {
  return poll(fetchBoardEdges, intervalMs);
}
