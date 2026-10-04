// The model catalog reads: GET /api/models (every head's roster) and GET /api/models/upstream (each
// provider's published list against splice.toml).
import { useMutation, useQuery } from '@tanstack/react-query';
import { pendingOf } from './auth';
import { request } from './client';
import type { ModelsPayload, PendingRoute, UpstreamModelsPayload } from '../types/models';

/** The v0.4.0 item that serves GET /api/models: a daemon older than it answers 404. */
export const PENDING_MODELS = 'V4-127';

export const modelsKey = ['models'] as const;

/** The catalog is boot-only topology, so the poll is slow: it clears a stale page after a daemon restart,
 *  it does not catch a live edit. */
export const MODELS_POLL_MS = 30_000;

export type ModelsSlice = ModelsPayload | PendingRoute;

/** GET /api/models: the catalog, or `{ pending }` when the daemon does not serve it. */
export async function fetchModels(): Promise<ModelsSlice> {
  try {
    return await request<ModelsPayload>('/api/models');
  } catch (err) {
    const pending = pendingOf(err, PENDING_MODELS);
    if (pending !== null) return pending;
    throw err;
  }
}

export const upstreamModelsPath = (provider?: string): string =>
  `/api/models/upstream${provider === undefined ? '' : `?provider=${encodeURIComponent(provider)}`}`;

/** GET /api/models/upstream[?provider=KEY]: the providers are asked on every call, so it runs when the operator
 *  presses Compare and never on a poll. A refusal rejects with the daemon's sentence. */
export const readUpstreamModels = (provider?: string): Promise<UpstreamModelsPayload> =>
  request<UpstreamModelsPayload>(upstreamModelsPath(provider));

export const useModels = () => useQuery({ queryKey: [...modelsKey], queryFn: fetchModels, refetchInterval: MODELS_POLL_MS });

/** Compare the roster with what the providers publish: `mutate(undefined)` asks every provider, `mutate('key')` one. */
export const useCompareModels = () => useMutation({ mutationFn: (provider: string | undefined) => readUpstreamModels(provider) });
