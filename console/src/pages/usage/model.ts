// The usage page's pure half: the hourly series and the two views.
//
// WHERE THE DOLLARS COME FROM. The daemon prices each turn when it records it, at the card of the
// model that turn ran (V4-221), and sums the dollars into the turn's hour. This page adds those sums
// up and multiplies nothing. A turn with no card, and every turn of an hour recorded before the
// daemon priced turns, is counted as unpriced beside the dollars, never priced at zero.
import type { View } from '@features/views';
import { sum } from '@entities/economics';
import type { Totals } from '@entities/economics';
import type { HeadEconomics } from '@shared/api';
import { windowHours } from '@widgets/scope-chart';
import { S } from './strings';

export const DEFAULT_VIEWS: readonly View[] = [
  { id: 'by-head', name: S.byHead, layout: 'bay', filter: {}, sort: null, group: null, fields: [] },
  { id: 'by-model', name: S.byModel, layout: 'bay', filter: {}, sort: null, group: null, fields: [] },
];

/** The heads a payload holds, in key order, so a rerender cannot shuffle the racks. */
export function sortedHeads(heads: readonly HeadEconomics[]): HeadEconomics[] {
  return [...heads].sort((left, right) => left.key.localeCompare(right.key));
}

/**
 * One figure per hour of the last [hours], oldest first, summed across [heads]. An hour no head
 * wrote a bucket for reads 0: the rollup writes no bucket for an idle hour, so zero is a reading,
 * not a gap. [read] turns one head's hour into the figure.
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

/** Dollars at the precision a figure that small needs: cents from a dollar up, a tenth of a cent
 *  below it. */
export function fmtUsd(usd: number): string {
  return `$${usd >= 1 ? usd.toFixed(2) : usd.toFixed(3)}`;
}
