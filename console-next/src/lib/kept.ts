// The words of the census: a UTC day as a date, and a store's size as a sentence.
import { fmtBytes } from './format';
import { K } from './words-kept';
import type { KeptInventory, TraceInventory } from '../types/kept';

/** A UTC day `YYYY-MM-DD` as `Oct 5`, or null when it does not parse. */
export function ageOutText(day: string | null): string | null {
  if (day === null) return null;
  const at = new Date(`${day}T12:00:00Z`);
  if (Number.isNaN(at.getTime())) return null;
  return new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(at);
}

/** What a store holds now, and when it lets go, as up to three sentences. */
export function keptLines(inventory: KeptInventory | TraceInventory): string[] {
  const size = 'records' in inventory ? K.traceCount(inventory.records, fmtBytes(inventory.bytes)) : K.count(inventory.days, inventory.rows);
  const out = ageOutText(inventory.ages_out);
  return [inventory.days === 0 ? K.none : size, ...(inventory.reason === undefined ? [] : [inventory.reason]), ...(out === null || inventory.days === 0 ? [] : [K.agesOut(out)])];
}

/** How many entries a delete would remove, for its button. */
export const entriesOf = (inventory: KeptInventory | TraceInventory): number => ('records' in inventory ? inventory.records : inventory.rows);
