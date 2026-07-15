import { control } from '@shared/api';
import type { HeadActionResult } from '@shared/api';
import { poll } from '@shared/lib';
import { headsStore } from '../model/store';

export async function fetchHeads(): Promise<void> {
  headsStore.startLoading();
  try {
    const res = await control.heads();
    headsStore.setData(res.heads);
  } catch (err) {
    headsStore.setError(err instanceof Error ? err.message : String(err));
  }
}

export function startHeadsPolling(intervalMs = 2000): () => void {
  return poll(fetchHeads, intervalMs);
}

/** Fire a lifecycle POST, then refresh the fleet so the next render reflects
 * the new state. Errors propagate to the caller (the plate shows them inline). */
async function lifecycle(action: (key: string) => Promise<HeadActionResult>, key: string): Promise<HeadActionResult> {
  const result = await action(key);
  await fetchHeads();
  return result;
}

export const startHead = (key: string): Promise<HeadActionResult> => lifecycle(control.startHead, key);
export const stopHead = (key: string): Promise<HeadActionResult> => lifecycle(control.stopHead, key);
export const restartHead = (key: string): Promise<HeadActionResult> => lifecycle(control.restartHead, key);
