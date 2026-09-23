import { control } from '@shared/api';
import { poll } from '@shared/lib';
import { logsStore } from '../model/store';

// No head until the page names one from the daemon's own registry. This read 'codex' until
// 2026-09-22, a head name only some installs carry: every other daemon answered 404 on the first
// poll, and a read of a guessed head is not a read the operator asked for.
let currentHead: string | null = null;
let tailSize = 200;

export function setLogHead(head: string): void {
  currentHead = head;
  void fetchLogs();
}

export function currentLogHead(): string | null {
  return currentHead;
}

export function setLogTail(n: number): void {
  tailSize = Math.min(2000, Math.max(10, n));
  void fetchLogs();
}

export function currentLogTail(): number {
  return tailSize;
}

export async function fetchLogs(): Promise<void> {
  if (currentHead === null) return;
  logsStore.startLoading();
  try {
    logsStore.setData(await control.logs(currentHead, tailSize));
  } catch (err) {
    logsStore.setError(err instanceof Error ? err.message : String(err));
  }
}

export function startLogsPolling(intervalMs = 5000): () => void {
  return poll(fetchLogs, intervalMs);
}
