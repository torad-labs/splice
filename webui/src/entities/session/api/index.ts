// Two jobs in one slice: the management-key gate (initSession/unlock, the shell calls these) and
// the Claude Code session registry (fetchSessions, the Sessions page calls this).
import { bindUnauthorized, getStoredKey, request, storeKey } from '@shared/api';
import { poll } from '@shared/lib';
import { sessionRegistryStore, sessionStore } from '../model/store';
import type { SessionsPayload } from '../model/types';

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
