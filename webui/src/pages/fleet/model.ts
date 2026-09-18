// The fleet page's pure half: how a saved view turns the head list into bays, how severe a head's
// attention state is, and how the two pending field sources are read when they exist.
import { headAttention, providerFamily } from '@entities/heads';
import type { HeadSignals, HeadState } from '@entities/heads';
import type { HeadStatus } from '@shared/api';
import type { View } from '@features/views';

export interface HeadGroup {
  key: string;
  heads: HeadStatus[];
}

/**
 * How severe a head's state is, for the `attention first` view. Higher sorts first.
 *
 * `down` outranks everything: a stopped head is not waiting on the operator to fix something, and
 * burying it under a swarm of amber warnings is exactly the failure this view exists to prevent.
 * `ok` is the floor so a healthy head never appears above a broken one.
 */
const SEVERITY: Record<HeadState, number> = {
  down: 7,
  unhealthy: 6,
  'version mismatch': 5,
  'token missing': 5,
  'token expired': 5,
  'account excluded': 4,
  'queue at max': 3,
  'topology stale': 2,
  ok: 0,
};

export function attentionRank(head: HeadStatus, signals: HeadSignals): number {
  return SEVERITY[headAttention(head, signals).cause];
}

/**
 * The bay layout for one saved view.
 *
 * `by head` (group: null, no sort) is the fleet itself: one bay, one strip per head, in the order
 * the daemon reports. `by provider` makes one bay per family. `attention first` sorts by severity
 * inside whatever grouping the view carries, so a head that needs the operator is never below the
 * fold.
 */
export function arrangeHeads(
  heads: readonly HeadStatus[],
  view: View,
  signalsFor: (head: HeadStatus) => HeadSignals,
): HeadGroup[] {
  const grouped = new Map<string, HeadStatus[]>();
  for (const head of heads) {
    const key = view.group === 'provider' ? providerFamily(head.authKind) : '';
    const bucket = grouped.get(key);
    if (bucket === undefined) grouped.set(key, [head]);
    else bucket.push(head);
  }

  const groups = [...grouped.entries()].map(([key, rows]) => ({ key, heads: rows }));
  for (const group of groups) {
    if (view.sort?.field === 'attention') {
      group.heads = [...group.heads].sort((left, right) => {
        const delta = attentionRank(right, signalsFor(right)) - attentionRank(left, signalsFor(left));
        // Ties break on the key so two equally-severe heads do not swap places between polls and
        // make the rack flicker under the operator's cursor.
        return delta !== 0 ? delta : left.key.localeCompare(right.key);
      });
    } else {
      group.heads = [...group.heads].sort((left, right) => left.key.localeCompare(right.key));
    }
  }
  groups.sort((left, right) => left.key.localeCompare(right.key));
  return groups;
}

/** Which columns a view shows. An empty list means every column. */
export function columnsOf(view: View, every: readonly string[]): readonly string[] {
  return view.fields.length === 0 ? every : view.fields;
}

/**
 * A head's dialect, out of the topology payload.
 *
 * Two hops, and both are in the file the daemon reads: `heads.<key>.provider` names a
 * `[providers.<name>]` block and that block carries `dialect` (FEATURES.md 2.3). Pure over a plain
 * object so it is testable while GET /api/topology is still V4-128 and gives nothing to read.
 */
export function dialectOf(topology: Record<string, unknown> | null, headKey: string): string | null {
  const heads = asTable(topology?.heads);
  const head = asTable(heads?.[headKey]);
  const provider = typeof head?.provider === 'string' ? head.provider : null;
  if (provider === null) return null;
  const providers = asTable(topology?.providers);
  const block = asTable(providers?.[provider]);
  return typeof block?.dialect === 'string' ? block.dialect : null;
}

function asTable(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

/**
 * The page's honest empties, as data rather than inline JSX, so a test can assert each one names
 * its source (CONTRACTS.md section 8).
 */
export const EMPTIES = {
  /** The two field sources that are still rows: the catalog and the topology file. */
  fields: { text: 'dialect and model not built', source: 'rows V4-127 V4-128' },
  /** The pooled accounts, for the detail column's pool section. */
  pool: { text: 'account pool not built', source: 'row V4-132' },
  noHeads: { text: 'no heads configured', source: 'GET /api/heads' },
  /** The daemon-level restart is its own row, distinct from a head restart. */
  daemonRestart: { text: 'daemon restart not built', source: 'row V4-74' },
} as const;
