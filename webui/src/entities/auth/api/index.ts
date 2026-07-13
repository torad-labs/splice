import { mgmt } from '@shared/api';
import { poll } from '@shared/lib';
import { authStore } from '../model/store';

export async function fetchAuth(): Promise<void> {
  authStore.startLoading();
  try {
    authStore.setData(await mgmt.auth());
  } catch (err) {
    authStore.setError(err instanceof Error ? err.message : String(err));
  }
}

/** POST /mgmt/auth/refresh, then reflect the fresh introspection in the store. */
export async function refreshAuth(): Promise<boolean> {
  try {
    const result = await mgmt.refreshAuth();
    authStore.setData(result);
    return Boolean(result.refreshed);
  } catch (err) {
    authStore.setError(err instanceof Error ? err.message : String(err));
    return false;
  }
}

export function startAuthPolling(intervalMs = 15000): () => void {
  return poll(fetchAuth, intervalMs);
}
