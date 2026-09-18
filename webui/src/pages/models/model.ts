// The models page's pure half: the empties, the two views, and the provider grouping.
import type { HeadCatalog, ModelsPayload, PendingRoute } from '@entities/model';
import type { CatalogModel } from '@entities/model';
import type { View } from '@features/views';
import { S } from './strings';

/** Sentences, not labels (CONTRACTS.md section 4). */
export const EMPTIES = {
  catalog: { text: 'no catalog from the daemon yet', source: 'GET /api/models' },
  catalogPending: { text: 'the console cannot read the catalog yet', source: 'V4-127 serves /api/models' },
  noModels: { text: 'this head declares no models', source: 'the topology' },
  noProvider: { text: 'the payload reports no provider', source: 'GET /api/models' },
  noneOpen: { text: 'no model opened', source: 'the catalog' },
} as const;

/** The honest bay label for heads whose payload carries no provider. A printed sentence-ish word
 *  rather than a derived guess: the head KEY is not a provider, and printing `claudex` under a
 *  heading that says provider would be the page inventing a fact. */
export const PROVIDER_UNKNOWN = 'provider not reported';

export const DEFAULT_VIEWS: readonly View[] = [
  { id: 'by-head', name: S.byHead, layout: 'bay', filter: {}, sort: null, group: null, fields: [] },
  { id: 'by-provider', name: S.byProvider, layout: 'bay', filter: {}, sort: null, group: 'provider', fields: [] },
];

export interface ProviderGroup {
  provider: string;
  heads: HeadCatalog[];
}

/** Heads grouped by the provider the payload reports, in stable order, with the un-reported ones
 *  collected under one honest label instead of being dropped or guessed at. */
export function byProvider(heads: readonly HeadCatalog[]): ProviderGroup[] {
  const groups = new Map<string, HeadCatalog[]>();
  for (const head of heads) {
    const key = head.provider === undefined || head.provider === '' ? PROVIDER_UNKNOWN : head.provider;
    groups.set(key, [...(groups.get(key) ?? []), head]);
  }
  return [...groups.entries()]
    .map(([provider, entries]) => ({ provider, heads: entries }))
    .sort((left, right) => left.provider.localeCompare(right.provider));
}

/** The model an id names anywhere in the catalog, with the head that declares it. */
export function findModel(
  payload: ModelsPayload | PendingRoute,
  id: string | null,
): { model: CatalogModel; head: HeadCatalog } | null {
  if (id === null || 'pending' in payload) return null;
  for (const head of payload.heads) {
    const model = head.models.find((entry) => entry.id === id);
    if (model !== undefined) return { model, head };
  }
  return null;
}
