import { control } from '@shared/api';
import { poll } from '@shared/lib';
import { controlStatusStore } from '../model/store';

/** GET /api/status — server version + head registry. The payload is near-static, but the READ is
 *  not: the rule's health cell is "did the daemon answer", and a status read once at mount kept
 *  answering yes after the daemon went away (the cell printed `daemon degraded` from heads read
 *  before it died), and kept answering no after a key gate that opened the console locked. */
export async function fetchControlStatus(): Promise<void> {
  controlStatusStore.startLoading();
  try {
    controlStatusStore.setData(await control.status());
  } catch (err) {
    controlStatusStore.setError(err instanceof Error ? err.message : String(err));
  }
}

export function startControlStatusPolling(intervalMs = 10_000): () => void {
  return poll(fetchControlStatus, intervalMs);
}
