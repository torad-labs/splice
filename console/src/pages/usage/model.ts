// The usage page's pure half: which rate card prices a head, the hourly series, and the two views.
//
// WHY THE COST SIDE IS SOMETIMES EMPTY. The dollars on this page are the daemon's exact token counts
// multiplied by a rate card the operator declared in the topology — and the ONLY route that serves a
// rate card is GET /api/models, which is pending V4-127. Until it lands there is no card to multiply
// by, so the cost inset says so rather than printing a confident zero. "No dollar figure" is the
// daemon's own answer where rates are absent (TokenCost.ratesFor returns null, never a zero card),
// and this page keeps that answer instead of improving on it.
import type { ModelsPayload, PendingRoute } from '@entities/model';
import type { View } from '@features/views';
import { costOf, sum } from '@entities/economics';
import type { CostRates, Totals } from '@entities/economics';
import type { HeadEconomics } from '@shared/api';
import { windowHours } from '@widgets/scope-chart';
import { S } from './strings';

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
 * One figure per hour of the last [hours], oldest first, summed across [heads]. An hour no head
 * wrote a bucket for reads 0: the rollup writes no bucket for an idle hour, so zero is a reading,
 * not a gap. [read] turns one head's hour into the figure, so cost can price each head by its own
 * card.
 */
export function perHour(
  heads: readonly HeadEconomics[],
  hours: number,
  now: number,
  read: (head: HeadEconomics, totals: Totals) => number,
): number[] {
  const byHour = heads.map((head) => ({ head, found: new Map(head.buckets.map((bucket) => [bucket.hour, bucket])) }));
  return windowHours(hours, now).map((at) => byHour.reduce((held, { head, found }) => {
    const bucket = found.get(at);
    return bucket === undefined ? held : held + read(head, sum([bucket]));
  }, 0));
}

/** A head's cost for [totals] at its own rate card, or 0 when it has none: an unpriced head is
 *  left out of the sum (the page says so), never priced at a guess. */
export function pricedCost(rates: ReadonlyMap<string, CostRates | null>) {
  return (head: HeadEconomics, totals: Totals): number => {
    const card = rates.get(head.key) ?? null;
    return card === null ? 0 : costOf(totals, card);
  };
}

/** Dollars at the precision a figure that small needs: cents from a dollar up, a tenth of a cent
 *  below it. */
export function fmtUsd(usd: number): string {
  return `$${usd >= 1 ? usd.toFixed(2) : usd.toFixed(3)}`;
}
