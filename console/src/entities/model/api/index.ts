// The model entity's HTTP segment. The client helper carries the management key, the 401 lockout
// and the error envelope (CONTRACTS.md 8), so nothing here re-implements any of them.
import { pendingOf as sharedPendingOf, request } from '@shared/api';
import { poll } from '@shared/lib';
import { modelsStore } from '../model/store';
import type { ModelsPayload, UpstreamModelsPayload } from '../model/types';

/** The v0.4.0 item that will serve GET /api/models. */
export const PENDING_MODELS = 'V4-127';

/** The catalog is boot-only topology (FEATURES.md 2.3), so the default poll is slow: it exists to
 *  clear a stale page after a daemon restart, not to catch a live edit. */
export async function fetchModels(): Promise<void> {
  modelsStore.startLoading();
  try {
    modelsStore.setData(await request<ModelsPayload>('/api/models'));
  } catch (err) {
    const pending = sharedPendingOf(err, PENDING_MODELS);
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

/** GET /api/models/upstream (V4-239): each provider's published list against splice.toml, asked of
 *  the providers on every call, so it runs when the operator presses Compare and never on a poll. It
 *  returns to the caller and fills no store; a refusal rejects with the daemon's sentence. */
export async function readUpstreamModels(provider?: string): Promise<UpstreamModelsPayload> {
  const query = provider === undefined ? '' : `?provider=${encodeURIComponent(provider)}`;
  return request<UpstreamModelsPayload>(`/api/models/upstream${query}`);
}
