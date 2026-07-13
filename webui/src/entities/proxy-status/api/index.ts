import { mgmt } from '@shared/api';
import { poll } from '@shared/lib';
import { statusStore } from '../model/store';

export async function fetchStatus(): Promise<void> {
  statusStore.startLoading();
  try {
    statusStore.setData(await mgmt.status());
  } catch (err) {
    statusStore.setError(err instanceof Error ? err.message : String(err));
  }
}

export function startStatusPolling(intervalMs = 2000): () => void {
  return poll(fetchStatus, intervalMs);
}
