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
    const key = head.provider === '' ? PROVIDER_UNKNOWN : head.provider;
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

/** A head's windows as its topology declares them (FEATURES 4.8 "Windows"): the forced head-wide
 *  window, its provider's default, and how many extra windows and prefix rules that provider
 *  carries. Read from GET /api/topology because GET /api/models reports each MODEL's window and its
 *  source, never the head's own (ModelsRoute.row); a number the topology does not set is null, and
 *  the page prints the absence rather than the daemon's internal zero. */
export interface HeadWindows {
  headWindow: number | null;
  defaultWindow: number | null;
  extraWindows: number;
  windowRules: number;
}

function asTable(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

/** The head's windows, or null while the topology has not been read or does not name the head. */
export function headWindows(topology: Record<string, unknown> | null, head: HeadCatalog): HeadWindows | null {
  const headTable = asTable(asTable(topology?.heads)?.[head.head]);
  const providerTable = asTable(asTable(topology?.providers)?.[head.provider]);
  if (headTable === null || providerTable === null) return null;
  const tokens = (value: unknown): number | null => (typeof value === 'number' && value > 0 ? value : null);
  const count = (value: unknown): number => (Array.isArray(value) ? value.length : 0);
  return {
    headWindow: tokens(headTable.context_window),
    defaultWindow: tokens(providerTable.default_context_window),
    extraWindows: count(providerTable.extra_windows),
    windowRules: count(providerTable.window_rules),
  };
}
