import { control } from '@shared/api';
import type { AuthActionResult } from '@shared/api';
import { poll } from '@shared/lib';
import { authStore } from '../model/store';

export async function fetchAuth(): Promise<void> {
  authStore.startLoading();
  try {
    authStore.setData(await control.auth());
  } catch (err) {
    authStore.setError(err instanceof Error ? err.message : String(err));
  }
}

export function startAuthPolling(intervalMs = 15000): () => void {
  return poll(fetchAuth, intervalMs);
}

/** POST /api/auth/:head/refresh, then reload the card from the source of
 * truth (the refresh response is a transient outcome, not the full card). */
export async function refreshAuth(head: string): Promise<AuthActionResult> {
  const result = await control.refreshAuth(head);
  await fetchAuth();
  return result;
}

/** POST /api/auth/:head/login — codex only; spawns the browser OAuth flow. */
export async function loginAuth(head: string): Promise<AuthActionResult> {
  const result = await control.loginAuth(head);
  await fetchAuth();
  return result;
}
