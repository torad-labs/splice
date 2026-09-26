import { control, request } from '@shared/api';
import type { HeadActionResult } from '@shared/api';
import { poll } from '@shared/lib';
import { headsStore } from '../model/store';
import type { LiveTurnsPayload, StopTurnResult } from '../model/turns';

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

/** The head's live turns (V4-319), read by the opened head's detail while it is open. */
export function fetchLiveTurns(head: string): Promise<LiveTurnsPayload> {
  return request<LiveTurnsPayload>(`/api/heads/${encodeURIComponent(head)}/turns/live`);
}

/** Stops one live turn: its client reads an error saying the operator stopped it, and its one
 *  re-send is refused. Rejects with the daemon's sentence, a 404 naming a turn that already ended. */
export function stopTurn(head: string, id: string): Promise<StopTurnResult> {
  return request<StopTurnResult>(
    `/api/heads/${encodeURIComponent(head)}/turns/${encodeURIComponent(id)}/stop`,
    { method: 'POST' },
  );
}
