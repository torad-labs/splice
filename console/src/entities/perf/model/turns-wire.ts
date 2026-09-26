// The one place GET /api/perf/turns' wire shape (PerfTurnsWire, one block per head) meets the page
// model (TurnRow[], one flat list with the head stamped on each row).
import type { PerfTurnsWire, TruncatedHead, TurnRow, TurnRowWire, UnreadHead } from './types';

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
  truncated: TruncatedHead[];
}

/**
 * Every head's rows as one list, newest last. The route caps each head at the `n` it was asked for,
 * so the cap is per head and nothing is cut here: a read over a window (the Teams day) keeps every
 * row each head served. V4-288: this cut the merged list to `n` across all heads, so past 2,000 fleet
 * turns since local midnight the Teams day silently began partway through.
 *
 * A head the daemon could not read (`error`, or a generation it could not open, `read_error`) is
 * named in `unread`: its rows are missing from the list, and a list that silently lost a head reads
 * exactly like a head that was idle. A head whose window held more rows than the cap (`truncated`)
 * is named in `truncated` with how many it held, for the same reason: its earliest rows are missing.
 */
export function mergeTurns(answers: readonly PerfTurnsWire[]): MergedTurns {
  const landed: TurnRow[] = [];
  const unread: UnreadHead[] = [];
  const truncated: TruncatedHead[] = [];
  for (const answer of answers) {
    for (const block of answer.heads) {
      if (block.error !== undefined) unread.push({ head: block.key, reason: block.error });
      if (block.read_error !== undefined) unread.push({ head: block.key, reason: block.read_error });
      const rows = block.rows ?? [];
      if (block.truncated === true) truncated.push({ head: block.key, count: block.count ?? null, returned: rows.length });
      for (const row of rows) landed.push(rowFromWire(block.key, row));
    }
  }
  landed.sort((left, right) => left.ts - right.ts);
  return { landed, unread, truncated };
}
