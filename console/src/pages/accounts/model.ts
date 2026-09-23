// The accounts page's pure half: how a saved view turns the payload into bays, and how a capture
// fixture is selected. Kept out of the component so both are testable without a renderer, and so
// the sort rule below is pinned rather than eyeballed.
import type { AccountRow } from '@entities/account';
import { PENDING_ACCOUNTS, nearestWindow } from '@entities/account';
import type { View } from '@features/views';
import { ACCOUNT_COLUMNS } from '@widgets/account-strip';
import { S } from './strings';

export interface AccountGroup {
  key: string;
  accounts: AccountRow[];
}

/**
 * The page's honest empties, as data rather than as inline JSX, so a test can assert that each one
 * names its source. CONTRACTS.md section 8: a route the daemon has not built renders an empty
 * naming the v0.4.0 row, never a mocked row and never a blank pane.
 */
export const EMPTIES = {
  /** GET /api/accounts is still a row: the per-head auth cards above this are the real answer. */
  pooledPending: { text: 'pooled accounts not built', source: `row ${PENDING_ACCOUNTS}` },
  noAccounts: { text: 'no accounts pooled', source: 'GET /api/accounts' },
} as const;

/** The head-group key for an account no head is riding. Its own group rather than dropped: an
 *  account in the pool that nothing is riding is a fact the operator wants to see. */
export const NO_HEAD_GROUP = S.noHeads;

/**
 * How far into its nearest window an account is, for the `nearest exhaustion` sort.
 *
 * An account whose provider reports no figure ranks BELOW every account that reported one, which
 * is the opposite of treating the absence as zero. FEATURES 4.5 states the rule for the page
 * ("unknown shown as unknown, never sorted first") and the reason is the operator's: a console
 * that sorts the unknown to the top trains the eye to skip the top, which is exactly where the
 * account that is about to fail belongs.
 */
export function exhaustionRank(account: AccountRow): number {
  return nearestWindow(account)?.used_percent ?? Number.NEGATIVE_INFINITY;
}

function byExhaustion(left: AccountRow, right: AccountRow): number {
  const delta = exhaustionRank(right) - exhaustionRank(left);
  if (delta !== 0) return delta;
  // A stable, meaningful tiebreak: the label, so two equally-spent accounts do not swap places
  // between polls and make the rack flicker.
  return (left.label ?? '').localeCompare(right.label ?? '');
}

function keysFor(account: AccountRow, group: string | null): string[] {
  if (group === 'head') return account.heads.length === 0 ? [NO_HEAD_GROUP] : [...account.heads].sort();
  if (group === 'provider') return [account.kind];
  return [''];
}

/**
 * The bay layout for one saved view.
 *
 * Grouping is by provider family (the default), by head, or not at all; sorting is applied INSIDE
 * a group, never across groups, because a rack's order is its grouping and re-sorting across it
 * would put an account in a bay that does not name it.
 *
 * One account can appear in more than one bay under `by head`, and that is the honest rendering:
 * a login under two heads is one pool row riding both, and hiding it from one of them would make
 * the page disagree with the daemon about which heads are on it.
 */
export function arrangeAccounts(accounts: readonly AccountRow[], view: View): AccountGroup[] {
  const grouped = new Map<string, AccountRow[]>();
  for (const account of accounts) {
    for (const key of keysFor(account, view.group)) {
      const bucket = grouped.get(key);
      if (bucket === undefined) grouped.set(key, [account]);
      else bucket.push(account);
    }
  }

  const groups = [...grouped.entries()].map(([key, rows]) => ({ key, accounts: rows }));
  for (const group of groups) {
    if (view.sort?.field === 'exhaustion') {
      const ranked = [...group.accounts].sort(byExhaustion);
      group.accounts = view.sort.dir === 'desc' ? ranked : ranked.reverse();
    }
  }
  groups.sort((left, right) => left.key.localeCompare(right.key));
  return groups;
}

/** Which columns a view shows. An empty list means every column, so a view that never touched its
 *  fields is not a view that hides everything. */
export function columnsOf(view: View): readonly string[] {
  return view.fields.length === 0 ? ACCOUNT_COLUMNS : view.fields;
}

/**
 * The capture fixture's name, or null.
 *
 * Gated on `dev` explicitly rather than reading `import.meta.env` here, so the rule is testable:
 * a fixture must never be reachable in a shipped artifact, and the caller passes the real
 * `import.meta.env.DEV`. CONTRACTS.md section 4 fixes the rest: the address carries
 * `?fixture=<name>`, and a rendered fixture is labelled `sample data` in its bay.
 */
export function fixtureName(search: string, dev: boolean): string | null {
  if (!dev) return null;
  const name = new URLSearchParams(search).get('fixture');
  if (name === null) return null;
  const trimmed = name.trim();
  return trimmed === '' ? null : trimmed;
}
