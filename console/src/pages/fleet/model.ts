// The fleet page's pure half: how a saved view turns the head list into bays, how severe a head's
// attention state is, how the two pending field sources are read when they exist, and which
// accounts an opened head rides.
import { isExcluded, sevenDayUsed } from '@entities/account';
import type { AccountRow, SelectorRule } from '@entities/account';
import { headAttention, providerFamily } from '@entities/heads';
import type { HeadSignals, HeadState, ProviderFamily } from '@entities/heads';
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

// ── the opened head's account pool (M4-02) ──────────────────────────────────────────────────────

/**
 * The accounts one head rides, out of GET /api/accounts: every row whose `heads` names it.
 *
 * Read off the row rather than joined on anything the console knows, because the daemon already did
 * the join: a login two heads share is ONE row carrying both keys (AccountsRoute.merge, joined on the
 * credential path), so it belongs to both pools and appears in both.
 */
export function poolOf(accounts: readonly AccountRow[], headKey: string): AccountRow[] {
  return accounts.filter((account) => account.heads.includes(headKey));
}

/**
 * HeadSignals.accountExcluded, exactly as that field's contract states it: the head rides a pool
 * whose SELECTED account is excluded. `isExcluded` is the predicate that strikes the account's own
 * strip, so the rack and the pool can never disagree about the same account. A single login's
 * `selected` is null (no pool selects it), so it never trips this.
 */
export function selectedExcluded(pool: readonly AccountRow[], nowMs: number): boolean {
  return pool.some((account) => account.selected === true && isExcluded(account, nowMs));
}

/** Why the daemon takes its next target: the selector's rules, with the pin the selector walks first. */
export type PoolRule = 'pinned' | SelectorRule;

export interface PoolNext {
  label: string;
  rule: PoolRule;
}

/** The daemon's tie-break inside its seven-day rule: the label, in Kotlin's String order (UTF-16 code
 *  units), which is `<` on JS strings and not localeCompare. */
function byLabel(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0;
}

/**
 * The pool's next target AS THE DAEMON ANSWERED IT, and the rule that explains it.
 *
 * THE MARK IS THE DAEMON'S FLAG, NOT A RE-DERIVATION. AccountsRoute writes `next_target` from the
 * pool's own nextTargetLabel (AccountPool.kt:163), and that walks the pin first, then primary, then
 * the caller's previous account, then lowest seven-day used with ties broken by label
 * (AccountPool.kt:179-186). A console-side derivation that skipped the pin, or broke a tie by array
 * order, would mark a strip the daemon will not take — a confident wrong answer about what happens
 * next. So the flag picks the account and the order only NAMES why: pinned, then primary, then the
 * lowest seven-day account; a target that is none of those can only have been the previous one,
 * which is the selector's sticky rule.
 */
export function poolNext(pool: readonly AccountRow[]): PoolNext | null {
  const target = pool.find((account) => account.next_target === true);
  if (target === undefined || target.label === null) return null;
  if (target.pinned === true) return { label: target.label, rule: 'pinned' };
  if (target.primary) return { label: target.label, rule: 'primary' };
  const lowest = pool
    .filter((account): account is AccountRow & { label: string } => account.available === true && account.label !== null)
    .sort((left, right) => sevenDayUsed(left) - sevenDayUsed(right) || byLabel(left.label, right.label))[0];
  return { label: target.label, rule: lowest === target ? 'lowest 7-day used' : 'sticky' };
}

/** The families whose heads ride OAuth logins, so GET /api/accounts reports them (AuthKindRegistry
 *  .isOAuth, AccountsRoute.fold). Every other kind is outside the join by the daemon's own rule. */
const OAUTH_FAMILIES: ReadonlySet<ProviderFamily> = new Set<ProviderFamily>(['chatgpt', 'grok', 'kimi', 'muse']);

/**
 * What an opened head's pool section says when GET /api/accounts names no row for it. Three
 * different facts, never one blank rack:
 *   - a Claude head is `client`: launch-time selected, one login, never a pool (the accounts page's
 *     own words for the same fact);
 *   - an api-key or local head has no OAuth login at all, so it has no pool and says which kind it is;
 *   - an OAuth head with no row is the route reporting nothing for it, and the empty names the route.
 */
export function poolEmpty(authKind: string): { text: string; source: string } {
  if (authKind === 'client') return EMPTIES.claudeLogin;
  if (OAUTH_FAMILIES.has(providerFamily(authKind))) return EMPTIES.noAccounts;
  return { text: 'no oauth pool', source: `${authKind} head` };
}

/**
 * The page's honest empties, as data rather than inline JSX, so a test can assert each one names
 * its source (CONTRACTS.md section 8).
 */
export const EMPTIES = {
  /** The two field sources that are still rows: the catalog and the topology file. */
  fields: { text: 'dialect and model not built', source: 'rows V4-127 V4-128' },
  /** The pooled accounts while the store holds the route's pending marker: printed only when
   *  GET /api/accounts answered 404 (entities/account maps that to V4-132), never unconditionally. */
  pool: { text: 'account pool not built', source: 'row V4-132' },
  noHeads: { text: 'no heads configured', source: 'GET /api/heads' },
  claudeLogin: { text: 'launch-time selected, never a pool', source: 'one login per claude head' },
  noAccounts: { text: 'no accounts reported', source: 'GET /api/accounts' },
  /** A pool with labeled accounts and no next target: nothing is available, which is the state that
   *  fails the head's next turn in words naming the earliest reset. */
  noneAvailable: { text: 'no account available', source: 'next_target on GET /api/accounts' },
} as const;
