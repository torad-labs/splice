// The usage page's pure half: which rate card prices a head, the honest empties, and the two views.
//
// WHY THE COST SIDE IS SOMETIMES EMPTY. The dollars on this page are the daemon's exact token counts
// multiplied by a rate card the operator declared in the topology — and the ONLY route that serves a
// rate card is GET /api/models, which is pending V4-127. Until it lands there is no card to multiply
// by, so the cost inset says so rather than printing a confident zero. "No dollar figure" is the
// daemon's own answer where rates are absent (TokenCost.ratesFor returns null, never a zero card),
// and this page keeps that answer instead of improving on it.
import type { ModelsPayload, PendingRoute } from '@entities/model';
import type { View } from '@features/views';
import type { CostRates } from '@entities/economics';
import type { HeadEconomics } from '@shared/api';
import type { HourRow } from '@widgets/scope-chart';
import { S } from './strings';

/** Sentences, not labels (CONTRACTS.md section 4), so they live here beside the page. */
export const EMPTIES = {
  economics: { text: 'no economics from the daemon yet', source: 'GET /api/economics' },
  catalogPending: { text: 'the console cannot read the catalog yet', source: 'V4-127 serves /api/models' },
  noHeads: { text: 'no heads report economics', source: 'GET /api/economics' },
  noModels: { text: 'this head declares no models', source: 'the topology' },
  noRows: { text: 'no turns in window', source: 'the hourly rollup' },
} as const;

export const DEFAULT_VIEWS: readonly View[] = [
  { id: 'by-head', name: S.byHead, layout: 'bay', filter: {}, sort: null, group: null, fields: [] },
  { id: 'by-model', name: S.byModel, layout: 'bay', filter: {}, sort: null, group: null, fields: [] },
];

/** The rate card that prices a head: its PINNED model's, which is the one every turn runs on
 *  unless the client asks otherwise. Null while the catalog route is pending, or when the head
 *  declares no rates at all — both of which read as "no dollar figure" and never as zero. */
export function ratesFor(
  catalog: ModelsPayload | PendingRoute | null,
  headKey: string,
): CostRates | null {
  if (catalog === null || 'pending' in catalog) return null;
  const head = catalog.heads.find((entry) => entry.head === headKey);
  if (head === undefined) return null;
  const pinned = head.models.find((model) => model.pinned) ?? head.models[0];
  return pinned?.rates ?? null;
}

/** The heads a payload holds, in key order, so a rerender cannot shuffle the racks. */
export function sortedHeads(heads: readonly HeadEconomics[]): HeadEconomics[] {
  return [...heads].sort((left, right) => left.key.localeCompare(right.key));
}

/**
 * The one-line read of a window, used under each head's charts. Every value here is a sum the
 * daemon already computed; the page derives ratios and prints them, never a new measurement.
 */
export function windowSummary(rows: readonly HourRow[]): { peak: number; busiest: number } {
  let peak = 0;
  let busiest = 0;
  for (const row of rows) {
    const total = row.values.fresh ?? 0 + (row.values.cached ?? 0) + (row.values.write ?? 0);
    if (total > peak) {
      peak = total;
      busiest = row.at;
    }
  }
  return { peak, busiest };
}
