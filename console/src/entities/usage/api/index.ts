import { control } from '@shared/api';
import { poll } from '@shared/lib';
import { usageStore } from '../model/store';

export async function fetchUsage(): Promise<void> {
  usageStore.startLoading();
  try {
    usageStore.setData(await control.usage());
  } catch (err) {
    usageStore.setError(err instanceof Error ? err.message : String(err));
  }
}

export function startUsagePolling(intervalMs = 5000): () => void {
  return poll(fetchUsage, intervalMs);
}
