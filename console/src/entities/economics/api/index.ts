import { control } from '@shared/api';
import { poll } from '@shared/lib';
import { economicsStore } from '../model/store';

export async function fetchEconomics(): Promise<void> {
  economicsStore.startLoading();
  try {
    economicsStore.setData(await control.economics());
  } catch (err) {
    economicsStore.setError(err instanceof Error ? err.message : String(err));
  }
}

// Hourly buckets; a 30s poll is already far finer than the data's own resolution.
export function startEconomicsPolling(intervalMs = 30_000): () => void {
  return poll(fetchEconomics, intervalMs);
}
