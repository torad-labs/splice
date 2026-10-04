// What splice keeps, as the daemon reports it: GET and DELETE /api/kept/{store}, and one head's trace directory.

/** Splice-owned stores whose physical days the daemon can remove. */
export type KeptStore = 'edges' | 'labels' | 'turns';

/** GET and DELETE /api/kept/{store} (ActivityRoutes.inventoryJson). `days` are whole UTC days on disk, `rows` the entries in them. */
export interface KeptInventory {
  store: KeptStore;
  state: 'on' | 'off' | 'deleted';
  reason?: string;
  days: number;
  rows: number;
  /** A UTC day `YYYY-MM-DD`, or null when nothing is kept. */
  oldest: string | null;
  ages_out: string | null;
}

/** GET and DELETE /api/heads/{head}/trace/kept. */
export interface TraceInventory {
  head: string;
  state: 'kept' | 'empty' | 'deleted';
  reason?: string;
  days: number;
  records: number;
  bytes: number;
  oldest: string | null;
  ages_out: string | null;
}
