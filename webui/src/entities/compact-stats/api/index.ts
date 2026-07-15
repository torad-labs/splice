import { control } from '@shared/api';
import { poll } from '@shared/lib';
import { compactStore } from '../model/store';

export async function fetchCompact(): Promise<void> {
  compactStore.startLoading();
  try {
    compactStore.setData(await control.compact());
  } catch (err) {
    compactStore.setError(err instanceof Error ? err.message : String(err));
  }
}

export function startCompactPolling(intervalMs = 5000): () => void {
  return poll(fetchCompact, intervalMs);
}
