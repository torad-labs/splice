/** Splice-owned stores whose physical days the daemon can remove. */
export type KeptStore = 'edges' | 'labels' | 'turns';

/** GET and DELETE /api/kept/{store}, from ActivityRoutes.inventoryJson. */
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

export interface KeptRemoval<T = KeptInventory> {
  inventory: T | null;
  deleted: boolean;
  deleteError: string | null;
  readError: string | null;
}

export interface KeptInventory {
  store: KeptStore;
  state: 'on' | 'off' | 'deleted';
  reason?: string;
  days: number;
  rows: number;
  oldest: string | null;
  ages_out: string | null;
}
