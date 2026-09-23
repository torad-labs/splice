// The one place GET /api/perf/turns' wire shape (PerfTurnsWire, one block per head) meets the page
// model (TurnRow[], one flat list with the head stamped on each row).
import type { PerfTurnsWire, TurnRow, TurnRowWire, UnreadHead } from './types';

/** A wire row as a page row. The daemon writes an absent session, account or cache tag as null;
 *  the page model spells the same absence by leaving the field out, which is what its readers test. */
function rowFromWire(head: string, wire: TurnRowWire): TurnRow {
  const { session, account, cache_cold: cacheCold, ...rest } = wire;
  return {
    ...rest,
    head,
    ...(session !== null ? { session } : {}),
    ...(account !== null ? { account } : {}),
    ...(cacheCold !== null ? { cache_cold: cacheCold } : {}),
  };
}

export interface MergedTurns {
  landed: TurnRow[];
  unread: UnreadHead[];
}

/**
 * Every head's rows as one list, newest last, cut to the newest `n` across all heads. Each head was
 * already asked for its own newest `n`, so the newest `n` overall are all in hand.
 *
 * A head the daemon could not read (`error`, or a generation it could not open, `read_error`) is
 * named in `unread`: its rows are missing from the list, and a list that silently lost a head reads
 * exactly like a head that was idle.
 */
export function mergeTurns(answers: readonly PerfTurnsWire[], n: number): MergedTurns {
  const landed: TurnRow[] = [];
  const unread: UnreadHead[] = [];
  for (const answer of answers) {
    for (const block of answer.heads) {
      if (block.error !== undefined) unread.push({ head: block.key, reason: block.error });
      if (block.read_error !== undefined) unread.push({ head: block.key, reason: block.read_error });
      for (const row of block.rows ?? []) landed.push(rowFromWire(block.key, row));
    }
  }
  landed.sort((left, right) => left.ts - right.ts);
  return { landed: landed.slice(-n), unread };
}
