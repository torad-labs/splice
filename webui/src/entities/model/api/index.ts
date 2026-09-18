// The model entity's HTTP segment. The client helper carries the management key, the 401 lockout
// and the error envelope (CONTRACTS.md 8), so nothing here re-implements any of them.
import { MgmtError, request } from '@shared/api';
import { poll } from '@shared/lib';
import { modelsStore } from '../model/store';
import type { ModelsPayload, PendingRoute } from '../model/types';

/** The v0.4.0 item that will serve GET /api/models. */
export const PENDING_MODELS = 'V4-127';

/**
 * The pending signal CONTRACTS.md 8 fixes: a route the daemon has not built answers 404, or names
 * the unknown route in its own error envelope; anything else is a real error and is reported as
 * one. The same rule lives in entities/perf/api, and cannot be shared from here: a slice may not
 * import a sibling slice. The finish row can hoist it once every data row is in.
 */
export function pendingOf(err: unknown): PendingRoute | null {
  if (!(err instanceof MgmtError)) return null;
  if (err.status === 404 || /unknown route|no such route/i.test(err.message)) {
    return { pending: PENDING_MODELS };
  }
  return null;
}

/** The catalog is boot-only topology (FEATURES.md 2.3), so the default poll is slow: it exists to
 *  clear a stale page after a daemon restart, not to catch a live edit. */
export async function fetchModels(): Promise<void> {
  modelsStore.startLoading();
  try {
    modelsStore.setData(await request<ModelsPayload>('/api/models'));
  } catch (err) {
    const pending = pendingOf(err);
    if (pending !== null) {
      modelsStore.setData(pending);
      return;
    }
    modelsStore.setError(err instanceof Error ? err.message : String(err));
  }
}

export function startModelsPolling(intervalMs = 30000): () => void {
  return poll(fetchModels, intervalMs);
}
