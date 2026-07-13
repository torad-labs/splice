import { mgmt } from '@shared/api';
import { poll } from '@shared/lib';
import { logsStore } from '../model/store';

let tailSize = 200;

export function setLogTail(n: number): void {
  tailSize = Math.min(2000, Math.max(10, n));
  void fetchLogs();
}

export function currentLogTail(): number {
  return tailSize;
}

export async function fetchLogs(): Promise<void> {
  logsStore.startLoading();
  try {
    logsStore.setData(await mgmt.logs(tailSize));
  } catch (err) {
    logsStore.setError(err instanceof Error ? err.message : String(err));
  }
}

export function startLogsPolling(intervalMs = 5000): () => void {
  return poll(fetchLogs, intervalMs);
}
